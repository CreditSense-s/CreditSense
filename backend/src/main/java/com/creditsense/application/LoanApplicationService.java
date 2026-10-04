package com.creditsense.application;

import com.creditsense.application.ApplicationDtos.*;
import com.creditsense.audit.AuditService;
import com.creditsense.common.Actor;
import com.creditsense.common.ApiException;
import com.creditsense.common.AppClock;
import com.creditsense.compliance.ComplianceGateService;
import com.creditsense.domain.*;
import com.creditsense.repo.*;
import com.creditsense.risk.RiskAssessmentService;
import com.creditsense.security.AuthUser;
import jakarta.persistence.criteria.Predicate;
import java.security.SecureRandom;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanApplicationService {

    private static final String REF_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private final SecureRandom random = new SecureRandom();

    private final LoanApplicationRepository applications;
    private final ApplicantRepository applicants;
    private final UserRepository users;
    private final ComplianceCheckRepository checks;
    private final RiskAssessmentRepository assessments;
    private final AuditLogRepository auditLogs;
    private final ComplianceGateService compliance;
    private final RiskAssessmentService risk;
    private final ApplicationAccessPolicy access;
    private final AuditService audit;
    private final AppClock clock;

    public LoanApplicationService(LoanApplicationRepository applications, ApplicantRepository applicants,
            UserRepository users, ComplianceCheckRepository checks, RiskAssessmentRepository assessments,
            AuditLogRepository auditLogs, ComplianceGateService compliance, RiskAssessmentService risk,
            ApplicationAccessPolicy access, AuditService audit, AppClock clock) {
        this.applications = applications;
        this.applicants = applicants;
        this.users = users;
        this.checks = checks;
        this.assessments = assessments;
        this.auditLogs = auditLogs;
        this.compliance = compliance;
        this.risk = risk;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ submission
    @Transactional
    public LoanApplication submit(AuthUser user, SubmitApplicationRequest req) {
        User owner = users.findById(user.id()).orElseThrow(() -> ApiException.notFound("user"));
        Applicant applicant = applicants.findByUserId(owner.getId()).orElseGet(() -> {
            Applicant a = new Applicant();
            a.setUser(owner);
            return a;
        });
        var b = req.business();
        applicant.setBusinessName(b.businessName().strip());
        applicant.setOwnerName(b.ownerName().strip());
        applicant.setSector(b.sector());
        applicant.setPan(b.pan().strip().toUpperCase());
        applicant.setGstin(b.gstin().strip().toUpperCase());
        applicant.setUdyamNumber(b.udyamNumber() == null || b.udyamNumber().isBlank() ? null : b.udyamNumber().strip().toUpperCase());
        applicant.setAddressLine(b.addressLine().strip());
        applicant.setCity(b.city().strip());
        applicant.setStateCode(b.stateCode());
        applicant.setPincode(b.pincode());
        applicant.setBusinessStartDate(b.businessStartDate());
        applicants.save(applicant);

        var f = req.financials();
        LoanApplication app = new LoanApplication();
        app.setApplicant(applicant);
        app.setReference(newReference());
        app.setAmountRequested(req.loan().amount());
        app.setPurpose(req.loan().purpose());
        app.setTenureMonths(req.loan().tenureMonths());
        app.setStatus(ApplicationStatus.SUBMITTED);
        app.setMonthlyRevenues(new ArrayList<>(f.monthlyRevenues()));
        app.setExistingDebt(f.existingDebt());
        app.setAvgMonthlyInflow(f.avgMonthlyInflow());
        app.setAvgMonthlyOutflow(f.avgMonthlyOutflow());
        app.setAvgBankBalance(f.avgBankBalance());
        app.setGstOnTimeFilingPct(f.gstOnTimeFilingPct());
        app.setTradeReferences(f.tradeReferences());
        app.setDelinquencyEvents(f.delinquencyEvents());
        app.setDigitalTxnPerMonth(f.digitalTxnPerMonth());
        app.setSubmittedAt(clock.now());
        app.setConsentAt(clock.now());
        for (var d : req.documents()) {
            KycDocument doc = new KycDocument();
            doc.setDocType(d.type());
            doc.setDocumentNumber(d.documentNumber().strip().toUpperCase());
            doc.setMonthsCovered(d.monthsCovered());
            app.addDocument(doc);
        }
        applications.save(app);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("reference", app.getReference());
        after.put("status", app.getStatus());
        after.put("amountRequested", app.getAmountRequested());
        after.put("purpose", app.getPurpose());
        after.put("documents", req.documents().stream().map(d -> d.type().name()).toList());
        audit.record(user.actor(), "APPLICATION_SUBMITTED", "LoanApplication", app.getId(), null, after);
        return app;
    }

    private String newReference() {
        String prefix = "CS-" + DateTimeFormatter.ofPattern("yyMM").withZone(ZoneOffset.UTC).format(clock.now()) + "-";
        StringBuilder sb = new StringBuilder(prefix);
        for (int i = 0; i < 6; i++) {
            sb.append(REF_ALPHABET.charAt(random.nextInt(REF_ALPHABET.length())));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ pipeline steps (each its own transaction)
    /** Runs the compliance gate. The actor is SYSTEM for the automatic pipeline, or the officer who re-ran it. */
    @Transactional
    public ComplianceGateService.Outcome runCompliance(Long id, Actor actor) {
        LoanApplication app = load(id);
        if (app.getStatus() == ApplicationStatus.APPROVED || app.getStatus() == ApplicationStatus.REJECTED) {
            throw ApiException.conflict("a decided application cannot be re-checked");
        }
        return compliance.evaluate(app, actor);
    }

    @Transactional
    public RiskAssessmentService.Result runRisk(Long id, Actor actor) {
        return risk.assess(load(id), actor);
    }

    /**
     * Before an officer's Re-score: if earlier calls opened the circuit breaker (typically while the ML
     * service was asleep) and the service now answers, close the breaker so the score is attempted at once.
     * Runs outside a transaction so no database connection is held during the health check.
     */
    public void reconnectModelIfBack() {
        risk.reconnectModelIfBack();
    }

    // ------------------------------------------------------------------ decision
    @Transactional
    public LoanApplication decide(Long id, AuthUser officer, DecisionRequest req) {
        LoanApplication app = load(id);
        if (!ApplicationStatus.DECIDABLE.contains(app.getStatus())) {
            throw ApiException.conflict("only risk-scored or manual-review applications can be decided (status is "
                    + app.getStatus() + ")");
        }
        boolean manual = app.getStatus() == ApplicationStatus.MANUAL_REVIEW;
        boolean override = !manual && req.decision() != app.getModelRecommendation();
        String reason = req.reason() == null ? "" : req.reason().strip();
        if ((override || manual) && reason.length() < 10) {
            throw ApiException.badRequest(manual
                    ? "a reason (at least 10 characters) is required when deciding without a model score"
                    : "a reason (at least 10 characters) is required when overriding the model's recommendation");
        }
        Map<String, Object> before = Map.of("status", app.getStatus());
        app.setDecision(req.decision());
        app.setDecisionReason(reason.isEmpty() ? null : reason);
        app.setDecisionOverride(override);
        app.setDecidedBy(users.getReferenceById(officer.id()));
        app.setDecidedAt(clock.now());
        app.setStatus(req.decision() == Decision.APPROVE ? ApplicationStatus.APPROVED : ApplicationStatus.REJECTED);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", app.getStatus());
        after.put("decision", req.decision());
        after.put("modelRecommendation", app.getModelRecommendation());
        after.put("override", override);
        after.put("reason", app.getDecisionReason());
        audit.record(officer.actor(), "DECISION_RECORDED", "LoanApplication", app.getId(), before, after);
        return app;
    }

    // ------------------------------------------------------------------ queries
    @Transactional(readOnly = true)
    public Detail detail(Long id, AuthUser user) {
        LoanApplication a = applications.findById(id)
                .filter(app -> access.canView(user, app))
                .orElseThrow(() -> ApiException.notFound("application"));
        List<Check> complianceChecks = checks.findByApplicationIdOrderByIdAsc(id).stream().map(Check::of).toList();
        Risk riskDto = assessments.findFirstByApplicationIdOrderByAssessedAtDescIdDesc(id).map(Risk::of).orElse(null);
        DecisionInfo decision = a.getDecision() == null ? null : new DecisionInfo(a.getDecision(), a.getDecisionReason(),
                a.getDecisionOverride(), a.getDecidedBy() == null ? null : a.getDecidedBy().getFullName(), a.getDecidedAt());
        List<TimelineEntry> timeline = user.isStaff()
                ? auditLogs.findByEntityTypeAndEntityIdOrderByCreatedAtAscIdAsc("LoanApplication", String.valueOf(id))
                        .stream().map(TimelineEntry::of).toList()
                : List.of();
        return new Detail(a.getId(), a.getReference(), a.getStatus(), a.getAmountRequested(), a.getPurpose(),
                a.getTenureMonths(), a.getSubmittedAt(), Business.of(a.getApplicant()), Financials.of(a),
                a.getDocuments().stream().map(Document::of).toList(), a.getKycScore(), complianceChecks, riskDto,
                a.getModelRecommendation(), a.getManualReviewReason(), RiskAssessmentService.awaitingModel(a), decision,
                a.getOutcome(),
                a.getOutcomeRecordedAt(), timeline);
    }

    @Transactional(readOnly = true)
    public List<Summary> mine(AuthUser user) {
        return applications.findByApplicantUserIdOrderBySubmittedAtDesc(user.id()).stream().map(Summary::of).toList();
    }

    @Transactional(readOnly = true)
    public Page<Summary> queue(List<ApplicationStatus> statuses, List<RiskBand> bands, String query, Pageable pageable) {
        Specification<LoanApplication> spec = (root, q, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (statuses != null && !statuses.isEmpty()) p.add(root.get("status").in(statuses));
            if (bands != null && !bands.isEmpty()) p.add(root.get("latestRiskBand").in(bands));
            if (query != null && !query.isBlank()) {
                String like = "%" + query.strip().toLowerCase() + "%";
                var applicant = root.join("applicant");
                p.add(cb.or(cb.like(cb.lower(root.get("reference")), like),
                        cb.like(cb.lower(applicant.get("businessName")), like)));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        return applications.findAll(spec, pageable).map(Summary::of);
    }

    private LoanApplication load(Long id) {
        return applications.findById(id).orElseThrow(() -> ApiException.notFound("application"));
    }
}
