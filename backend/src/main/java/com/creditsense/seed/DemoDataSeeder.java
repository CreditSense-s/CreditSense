package com.creditsense.seed;

import com.creditsense.admin.AdminService;
import com.creditsense.application.DecisionRequest;
import com.creditsense.application.LoanApplicationService;
import com.creditsense.application.SubmitApplicationRequest;
import com.creditsense.application.SubmitApplicationRequest.*;
import com.creditsense.audit.AuditService;
import com.creditsense.common.AppClock;
import com.creditsense.compliance.Gstin;
import com.creditsense.domain.*;
import com.creditsense.repo.LoanApplicationRepository;
import com.creditsense.repo.UserRepository;
import com.creditsense.risk.MlClient;
import com.creditsense.security.AuthUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates demo users (one per role) and a year of demo applications in every state, by driving
 * the real services: submission, compliance gate, ML scoring and officer decisions. Timestamps
 * are shifted with {@link AppClock} so that dates line up across applications, checks,
 * assessments and the audit trail. Runs once, on an empty database, when SEED_DEMO_DATA=true.
 */
@Component
@ConditionalOnProperty(name = "creditsense.seed.enabled", havingValue = "true")
public class DemoDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    public static final String APPLICANT_EMAIL = "applicant@creditsense.demo";
    public static final String OFFICER_EMAIL = "officer@creditsense.demo";
    public static final String ADMIN_EMAIL = "admin@creditsense.demo";

    private record City(String name, String stateCode, String udyamState) {}

    private static final List<City> CITIES = List.of(
            new City("Hyderabad", "36", "TS"), new City("Bengaluru", "29", "KA"), new City("Pune", "27", "MH"),
            new City("Chennai", "33", "TN"), new City("Ahmedabad", "24", "GJ"), new City("Jaipur", "08", "RJ"),
            new City("Kochi", "32", "KL"), new City("Lucknow", "09", "UP"), new City("Indore", "23", "MP"),
            new City("Coimbatore", "33", "TN"), new City("Visakhapatnam", "37", "AP"), new City("Nagpur", "27", "MH"));
    private static final String[] OWNERS = {"Aarav Mehta", "Diya Nair", "Kabir Singh", "Ananya Iyer", "Vihaan Reddy",
            "Ishita Das", "Arjun Rao", "Meera Pillai", "Rohan Gupta", "Sneha Kulkarni", "Aditya Joshi", "Kavya Menon",
            "Farhan Qureshi", "Pooja Agarwal", "Nikhil Bhat", "Tara Chatterjee", "Siddharth Jain", "Lakshmi Varma",
            "Imran Sheikh", "Neha Saxena", "Varun Malhotra", "Riya Banerjee", "Karthik Subramanian", "Zoya Khan",
            "Manish Patel", "Anjali Deshpande", "Harsh Vardhan", "Priyanka Bose", "Suresh Yadav", "Nandini Hegde",
            "Rahul Kapoor", "Fatima Ansari", "Gaurav Sinha", "Swati Mishra", "Deepak Choudhary", "Asha Krishnan"};
    private static final Map<Sector, String[]> BUSINESS_WORDS = Map.of(
            Sector.MANUFACTURING, new String[]{"Precision Components", "Polymers", "Castings", "Packaging Works"},
            Sector.TRADING, new String[]{"Traders", "Distributors", "Wholesale Agencies", "Impex"},
            Sector.SERVICES, new String[]{"Tech Services", "Logistics", "Facility Services", "Consulting"},
            Sector.RETAIL, new String[]{"Mart", "Fashion House", "Electronics", "Super Store"},
            Sector.AGRI_ALLIED, new String[]{"Agro Foods", "Dairy Farms", "Cold Storage", "Seeds & Fertilisers"},
            Sector.HOSPITALITY, new String[]{"Residency", "Caterers", "Restaurant", "Travel & Stays"});

    private final UserRepository users;
    private final LoanApplicationRepository applications;
    private final LoanApplicationService service;
    private final AdminService admin;
    private final MlClient ml;
    private final AuditService audit;
    private final AppClock clock;
    private final PasswordEncoder encoder;
    private final TransactionTemplate tx;
    private final String password;
    private final Random rnd = new Random(20260924L);
    private final Instant now = Instant.now();
    private String passwordHash;

    public DemoDataSeeder(UserRepository users, LoanApplicationRepository applications, LoanApplicationService service,
            AdminService admin, MlClient ml, AuditService audit, AppClock clock, PasswordEncoder encoder,
            TransactionTemplate tx, @Value("${creditsense.seed.password}") String password) {
        this.users = users;
        this.applications = applications;
        this.service = service;
        this.admin = admin;
        this.ml = ml;
        this.audit = audit;
        this.clock = clock;
        this.encoder = encoder;
        this.tx = tx;
        this.password = password;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (users.count() > 0) {
            log.info("demo seed skipped: database already has users");
            return;
        }
        Thread t = new Thread(this::seed, "demo-seeder");
        t.setDaemon(true);
        t.start();
    }

    void seed() {
        try {
            passwordHash = encoder.encode(password);
            User officer = user(OFFICER_EMAIL, "Rahul Verma", Role.LOAN_OFFICER);
            user(ADMIN_EMAIL, "Anita Rao", Role.ADMIN);
            User demoApplicant = user(APPLICANT_EMAIL, "Priya Sharma", Role.APPLICANT);
            log.info("demo users created ({} / {} / {}), waiting for the ML service", APPLICANT_EMAIL, OFFICER_EMAIL, ADMIN_EMAIL);
            waitForMl();

            AuthUser officerAuth = new AuthUser(officer.getId(), officer.getEmail(), officer.getRole());
            seedPortfolio(officerAuth);
            seedComplianceFailures();
            seedDemoApplicant(demoApplicant, officerAuth);

            var maturity = admin.simulateMaturity(120);
            log.info("demo loans matured: {}", maturity);
            try {
                admin.retrain();
            } catch (RuntimeException e) {
                log.warn("demo retrain skipped: {}", e.getMessage());
            }
            log.info("demo seed complete: {} applications", applications.count());
        } catch (RuntimeException e) {
            log.error("demo seed failed", e);
        }
    }

    // ------------------------------------------------------------------ scenarios
    private void seedPortfolio(AuthUser officer) {
        int n = 40;
        for (int i = 0; i < n; i++) {
            // spread submissions over the past eleven months; the newest few stay in the queue
            Instant submitted = now.minus(Duration.ofDays(335 - (long) i * 8)).minus(Duration.ofHours(rnd.nextInt(10)));
            City city = CITIES.get(rnd.nextInt(CITIES.size()));
            Sector sector = pickSector();
            String owner = OWNERS[i % OWNERS.length];
            User u = user(String.format("msme%02d@creditsense.demo", i + 1), owner, Role.APPLICANT);
            Profile p = profile(sector, city, owner, i);
            Long id = submitAndProcess(u, p.request(), submitted);
            boolean inQueue = i >= n - 5;
            if (!inQueue) {
                decide(id, officer, submitted.plus(Duration.ofHours(20 + rnd.nextInt(60))));
            }
        }
    }

    private void seedComplianceFailures() {
        Instant recent = now.minus(Duration.ofDays(6));
        City hyd = CITIES.get(0);

        // 1. PAN on the internal blacklist (seeded by migration V2)
        submitAndProcess(user("blocked.pan@creditsense.demo", "Vikram Shetty", Role.APPLICANT),
                profileWithIdentity(Sector.TRADING, hyd, "Vikram Shetty", "Shetty Wholesale Agencies", "AADCW5566K",
                        hyd.stateCode()).request(), recent);

        // 2. The same PAN as an existing portfolio applicant, on a new account
        String reusedPan = tx.execute(st -> applications.findAll().get(0).getApplicant().getPan());
        submitAndProcess(user("dup.pan@creditsense.demo", "Ramesh Kumar", Role.APPLICANT),
                profileWithIdentity(Sector.RETAIL, hyd, "Ramesh Kumar", "RK Super Store", reusedPan, hyd.stateCode())
                        .request(), recent.plus(Duration.ofHours(3)));

        // 3. GSTIN registered in Maharashtra, business address declared in Telangana
        Profile mismatch = profileWithIdentity(Sector.SERVICES, hyd, "Sunita Pawar", "Pawar Logistics",
                randomPan('F', 'P'), "27");
        submitAndProcess(user("addr.mismatch@creditsense.demo", "Sunita Pawar", Role.APPLICANT), mismatch.request(),
                recent.plus(Duration.ofHours(5)));

        // 4. GSTIN with a typing error: one digit changed, check character no longer matches
        Profile typo = profile(Sector.MANUFACTURING, hyd, "Anil Kumble", 99);
        String g = typo.gstin;
        char wrong = g.charAt(8) == '9' ? '8' : (char) (g.charAt(8) + 1);
        typo.gstin = g.substring(0, 8) + wrong + g.substring(9);
        submitAndProcess(user("gstin.typo@creditsense.demo", "Anil Kumble", Role.APPLICANT), typo.request(),
                recent.plus(Duration.ofHours(7)));

        // 5. Velocity: a fourth application within 30 days
        User rapid = user("rapid.fire@creditsense.demo", "Tanvi Arora", Role.APPLICANT);
        Profile rp = profile(Sector.HOSPITALITY, CITIES.get(1), "Tanvi Arora", 77);
        for (int k = 0; k < 4; k++) {
            submitAndProcess(rapid, rp.request(), now.minus(Duration.ofDays(24 - k * 6L)));
        }
    }

    private void seedDemoApplicant(User demo, AuthUser officer) {
        City hyd = CITIES.get(0);
        Profile p = profileWithIdentity(Sector.MANUFACTURING, hyd, "Priya Sharma", "Sharma Textiles", randomPan('P', 'S'),
                hyd.stateCode());
        p.vintageYears = 9.5;
        p.delinquency = 0;
        p.gstPct = 96;

        // An older loan, approved, which will mature and carry an outcome
        Instant old = now.minus(Duration.ofDays(210));
        Long first = submitAndProcess(demo, p.request(), old);
        decide(first, officer, old.plus(Duration.ofDays(1)));

        // A recent application waiting for the officer, with its score and explanation
        p.amount = p.amount.multiply(BigDecimal.valueOf(1.8)).setScale(2, RoundingMode.HALF_UP);
        p.purpose = LoanPurpose.EXPANSION;
        submitAndProcess(demo, p.request(), now.minus(Duration.ofDays(3)));

        // An application stopped by the KYC rule: three months of bank statements, no Udyam certificate
        Profile incomplete = p.copy();
        incomplete.bankMonths = 3;
        incomplete.includeUdyam = false;
        incomplete.purpose = LoanPurpose.EQUIPMENT;
        submitAndProcess(demo, incomplete.request(), now.minus(Duration.ofDays(1)));
    }

    // ------------------------------------------------------------------ helpers
    private Long submitAndProcess(User u, SubmitApplicationRequest req, Instant at) {
        AuthUser auth = new AuthUser(u.getId(), u.getEmail(), u.getRole());
        return clock.at(at, () -> {
            Long id = service.submit(auth, req).getId();
            if (service.runCompliance(id, com.creditsense.common.Actor.SYSTEM).passed()) {
                service.runRisk(id, com.creditsense.common.Actor.SYSTEM);
            }
            return id;
        });
    }

    private void decide(Long id, AuthUser officer, Instant at) {
        clock.at(at, () -> {
            LoanApplication app = applications.findById(id).orElseThrow();
            if (!ApplicationStatus.DECIDABLE.contains(app.getStatus())) {
                return null;
            }
            Decision rec = app.getModelRecommendation();
            DecisionRequest req;
            if (rec == null) {
                req = new DecisionRequest(Decision.APPROVE,
                        "Reviewed manually: bank statements and GST returns verified by the underwriter.");
            } else if (rnd.nextDouble() < 0.12) {
                req = rec == Decision.APPROVE
                        ? new DecisionRequest(Decision.REJECT,
                                "Local sector downturn and a recent cheque bounce on file; applicant may reapply after two GST quarters.")
                        : new DecisionRequest(Decision.APPROVE,
                                "Long banking relationship and collateral offered; approved with a reduced limit and quarterly monitoring.");
            } else {
                req = new DecisionRequest(rec, null);
            }
            service.decide(id, officer, req);
            return null;
        });
    }

    private User user(String email, String name, Role role) {
        return tx.execute(s -> {
            User u = new User();
            u.setEmail(email);
            u.setFullName(name);
            u.setRole(role);
            u.setPasswordHash(passwordHash);
            users.save(u);
            audit.record(com.creditsense.common.Actor.SYSTEM, "USER_PROVISIONED", "User", u.getId(), null,
                    Map.of("email", email, "role", role, "source", "demo seed"));
            return u;
        });
    }

    private void waitForMl() {
        for (int i = 0; i < 300 && !ml.healthy(); i++) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private Sector pickSector() {
        double r = rnd.nextDouble();
        double[] cum = {0.24, 0.46, 0.66, 0.82, 0.92, 1.0};
        Sector[] s = Sector.values();
        for (int i = 0; i < cum.length; i++) if (r < cum[i]) return s[i];
        return Sector.MANUFACTURING;
    }

    private String randomPan(char holderType, char nameInitial) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) sb.append((char) ('A' + rnd.nextInt(26)));
        sb.append(holderType).append(nameInitial);
        sb.append(String.format("%04d", rnd.nextInt(10000)));
        sb.append((char) ('A' + rnd.nextInt(26)));
        return sb.toString();
    }

    private static String gstinFor(String stateCode, String pan) {
        String body = stateCode + pan + "1Z";
        return body + Gstin.checkCharacter(body);
    }

    private Profile profile(Sector sector, City city, String owner, int i) {
        String[] words = BUSINESS_WORDS.get(sector);
        String surname = owner.substring(owner.lastIndexOf(' ') + 1);
        String business = surname + " " + words[(i + rnd.nextInt(words.length)) % words.length];
        return profileWithIdentity(sector, city, owner, business,
                randomPan(rnd.nextDouble() < 0.6 ? 'P' : 'F', Character.toUpperCase(surname.charAt(0))), city.stateCode());
    }

    /** Financials drawn to resemble the population the model was trained on. */
    private Profile profileWithIdentity(Sector sector, City city, String owner, String business, String pan,
            String gstinState) {
        Profile p = new Profile();
        p.sector = sector;
        p.city = city;
        p.owner = owner;
        p.business = business;
        p.pan = pan;
        p.gstin = gstinFor(gstinState, pan);
        p.udyam = String.format("UDYAM-%s-%02d-%07d", city.udyamState(), 1 + rnd.nextInt(33), rnd.nextInt(10_000_000));
        boolean seasonal = sector == Sector.AGRI_ALLIED || sector == Sector.HOSPITALITY;
        p.vintageYears = 0.8 + 16 * Math.pow(rnd.nextDouble(), 1.7);
        p.monthlyRevenue = Math.exp(1.2 + 0.35 * Math.log1p(p.vintageYears) + rnd.nextGaussian() * 0.7);
        p.cv = 0.06 + 0.32 * rnd.nextDouble() + (seasonal ? 0.12 : 0) + 0.2 / Math.sqrt(p.vintageYears);
        p.gstPct = Math.min(100, 55 + 45 * Math.pow(rnd.nextDouble(), 0.45));
        p.debtRatio = 0.03 + 0.75 * Math.pow(rnd.nextDouble(), 1.6);
        p.inflowOutflow = 1.05 + rnd.nextGaussian() * 0.09;
        p.balanceCover = 0.08 + 1.3 * Math.pow(rnd.nextDouble(), 1.4);
        p.tradeRefs = Math.max(0, (int) Math.round(1.5 + 0.25 * Math.min(p.vintageYears, 12) + rnd.nextGaussian()));
        double d = rnd.nextDouble();
        p.delinquency = d < 0.62 ? 0 : d < 0.86 ? 1 : d < 0.96 ? 2 : 3;
        double loanRatio = Math.min(2.0, Math.exp(Math.log(0.25) + rnd.nextGaussian() * 0.5));
        p.amount = BigDecimal.valueOf(Math.max(0.5, loanRatio * 12 * p.monthlyRevenue)).setScale(2, RoundingMode.HALF_UP);
        p.digital = (int) Math.round(Math.exp(3.8 + (sector == Sector.RETAIL || sector == Sector.SERVICES ? 0.5 : 0)
                + rnd.nextGaussian() * 0.8));
        p.bankMonths = 6 + rnd.nextInt(7);
        p.includeGstCertificate = rnd.nextDouble() < 0.7;
        p.purpose = LoanPurpose.values()[rnd.nextInt(LoanPurpose.values().length)];
        p.tenure = 12 * (1 + rnd.nextInt(5));
        return p;
    }

    private final class Profile {
        Sector sector;
        City city;
        String owner, business, pan, gstin, udyam;
        double vintageYears, monthlyRevenue, cv, gstPct, debtRatio, inflowOutflow, balanceCover;
        int tradeRefs, delinquency, digital, bankMonths, tenure;
        boolean includeGstCertificate, includeUdyam = true;
        BigDecimal amount;
        LoanPurpose purpose;
        List<BigDecimal> revenues;

        Profile copy() {
            Profile c = new Profile();
            c.sector = sector; c.city = city; c.owner = owner; c.business = business; c.pan = pan; c.gstin = gstin;
            c.udyam = udyam; c.vintageYears = vintageYears; c.monthlyRevenue = monthlyRevenue; c.cv = cv;
            c.gstPct = gstPct; c.debtRatio = debtRatio; c.inflowOutflow = inflowOutflow; c.balanceCover = balanceCover;
            c.tradeRefs = tradeRefs; c.delinquency = delinquency; c.digital = digital; c.bankMonths = bankMonths;
            c.tenure = tenure; c.includeGstCertificate = includeGstCertificate; c.includeUdyam = includeUdyam;
            c.amount = amount; c.purpose = purpose; c.revenues = revenues;
            return c;
        }

        SubmitApplicationRequest request() {
            if (revenues == null) {
                revenues = new ArrayList<>();
                for (int k = 0; k < 6; k++) {
                    double r = Math.max(0.1, monthlyRevenue * (1 + cv * rnd.nextGaussian()));
                    revenues.add(BigDecimal.valueOf(r).setScale(2, RoundingMode.HALF_UP));
                }
            }
            double outflow = monthlyRevenue * 0.95;
            LocalDate start = LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(Math.round(vintageYears * 365.25));
            List<DocumentInput> docs = new ArrayList<>();
            docs.add(new DocumentInput(DocumentType.PAN, pan, null));
            if (includeUdyam) docs.add(new DocumentInput(DocumentType.UDYAM, udyam, null));
            docs.add(new DocumentInput(DocumentType.ADDRESS_PROOF, "EB-" + (1_000_000 + rnd.nextInt(8_999_999)), null));
            docs.add(new DocumentInput(DocumentType.BANK_STATEMENT, "STMT-" + (100_000 + rnd.nextInt(899_999)), bankMonths));
            if (includeGstCertificate) docs.add(new DocumentInput(DocumentType.GST_CERTIFICATE, gstin, null));
            return new SubmitApplicationRequest(
                    new BusinessProfile(business, owner, sector, pan, gstin, includeUdyam ? udyam : null,
                            (10 + rnd.nextInt(480)) + ", Industrial Estate Road", city.name(), city.stateCode(),
                            String.valueOf(500001 + rnd.nextInt(400000)), start),
                    new LoanRequest(amount, purpose, tenure),
                    new Financials(revenues,
                            bd(debtRatio * 12 * monthlyRevenue), bd(outflow * inflowOutflow), bd(outflow),
                            bd(balanceCover * outflow), bd(gstPct), tradeRefs, delinquency, digital),
                    docs, true);
        }
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(Math.max(0.01, v)).setScale(2, RoundingMode.HALF_UP);
    }
}
