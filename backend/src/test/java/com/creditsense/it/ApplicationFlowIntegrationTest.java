package com.creditsense.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creditsense.domain.Role;
import com.creditsense.domain.User;
import com.creditsense.repo.UserRepository;
import com.creditsense.risk.TestPredictions;
import com.creditsense.security.GoogleTokenVerifier;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import com.creditsense.risk.MlClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** End-to-end API flow against a real PostgreSQL (Testcontainers) and a stubbed ML service. */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApplicationFlowIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static final MockWebServer ML = new MockWebServer();
    static final AtomicInteger ML_STATUS = new AtomicInteger(200);

    /** A valid Telangana GSTIN for the given PAN (each test uses its own PAN: duplicates are a fraud signal). */
    static String gstin(String pan) {
        String body = "36" + pan + "1Z";
        return body + com.creditsense.compliance.Gstin.checkCharacter(body);
    }

    static {
        ML.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (ML_STATUS.get() != 200) return new MockResponse().setResponseCode(ML_STATUS.get());
                return new MockResponse().setHeader("Content-Type", "application/json")
                        .setBody(TestPredictions.json(0.27, "HIGH"));
            }
        });
        try {
            ML.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("creditsense.ml.base-url", () -> ML.url("/").toString());
        r.add("creditsense.jwt.secret", () -> "integration-test-secret-at-least-32-bytes!");
        r.add("creditsense.access.admin-emails", () -> "Owner.Admin@gmail.com");
        r.add("creditsense.access.officer-emails", () -> "officer.g@gmail.com");
        r.add("creditsense.ml.outage-check-interval", () -> "PT1S");
    }

    @AfterAll
    static void stopMl() throws IOException {
        ML.shutdown();
    }

    /** Google's real verification is covered by GoogleTokenVerifierTest; here the verified identity is stubbed. */
    @MockitoBean GoogleTokenVerifier google;
    @Autowired TestRestTemplate http;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired MlClient ml;
    @Autowired com.creditsense.security.RefreshTokenService refreshTokens;

    @BeforeEach
    void staff() {
        ML_STATUS.set(200);
        for (var s : List.of(new String[]{"officer@it.test", "LOAN_OFFICER"}, new String[]{"admin@it.test", "ADMIN"})) {
            if (users.findByEmailIgnoreCase(s[0]).isEmpty()) {
                User u = new User();
                u.setEmail(s[0]);
                u.setFullName(s[1]);
                u.setRole(Role.valueOf(s[1]));
                u.setPasswordHash(encoder.encode("Passw0rd!"));
                users.save(u);
            }
        }
    }

    // ------------------------------------------------------------------ helpers
    String register(String email) {
        var body = Map.of("email", email, "password", "Passw0rd1", "fullName", "Test Owner");
        var res = http.postForEntity("/api/auth/register", body, Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) res.getBody().get("accessToken");
    }

    String login(String email) {
        var res = http.postForEntity("/api/auth/login", Map.of("email", email, "password", "Passw0rd!"), Map.class);
        return (String) res.getBody().get("accessToken");
    }

    <T> ResponseEntity<T> call(HttpMethod method, String url, String token, Object body, Class<T> type) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(url, method, new HttpEntity<>(body, h), type);
    }

    static Map<String, Object> application(String gstin, String pan) {
        return Map.of(
                "business", Map.ofEntries(Map.entry("businessName", "Rao Precision Components"),
                        Map.entry("ownerName", "Neha Rao"), Map.entry("sector", "MANUFACTURING"), Map.entry("pan", pan),
                        Map.entry("gstin", gstin), Map.entry("udyamNumber", "UDYAM-TS-02-0012345"),
                        Map.entry("addressLine", "Plot 12, IDA Uppal"), Map.entry("city", "Hyderabad"),
                        Map.entry("stateCode", "36"), Map.entry("pincode", "500039"),
                        Map.entry("businessStartDate", "2017-04-01")),
                "loan", Map.of("amount", 18.5, "purpose", "EQUIPMENT", "tenureMonths", 36),
                "financials", Map.of("monthlyRevenues", List.of(9.2, 8.7, 10.1, 9.6, 9.9, 10.4), "existingDebt", 22,
                        "avgMonthlyInflow", 9.8, "avgMonthlyOutflow", 9.1, "avgBankBalance", 6.5,
                        "gstOnTimeFilingPct", 92, "tradeReferences", 4, "delinquencyEvents", 0,
                        "digitalTxnPerMonth", 140),
                "documents", List.of(Map.of("type", "PAN", "documentNumber", pan),
                        Map.of("type", "UDYAM", "documentNumber", "UDYAM-TS-02-0012345"),
                        Map.of("type", "ADDRESS_PROOF", "documentNumber", "EB-4471920"),
                        Map.of("type", "BANK_STATEMENT", "documentNumber", "STMT-88121", "monthsCovered", 12)),
                "consent", true);
    }

    // ------------------------------------------------------------------ tests
    @Test
    void applicantToDecisionWithAuditTrail() {
        String applicant = register("owner1@it.test");
        var submitted = call(HttpMethod.POST, "/api/applications", applicant, application(gstin("AKTPR4821K"), "AKTPR4821K"), Map.class);
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> app = submitted.getBody();
        assertThat(app.get("status")).isEqualTo("RISK_SCORED");
        assertThat((List<?>) ((Map<?, ?>) app.get("riskAssessment")).get("contributions")).hasSize(13);
        assertThat(app.get("modelRecommendation")).isEqualTo("REJECT");
        assertThat((List<?>) app.get("timeline")).isEmpty(); // audit trail is staff-only
        Number id = (Number) app.get("id");

        // another applicant cannot see it, not even that it exists
        String stranger = register("owner2@it.test");
        assertThat(call(HttpMethod.GET, "/api/applications/" + id, stranger, null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.GET, "/api/applications", applicant, null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        String officer = login("officer@it.test");
        var override = call(HttpMethod.POST, "/api/applications/" + id + "/decision", officer,
                Map.of("decision", "APPROVE"), Map.class);
        assertThat(override.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        var decided = call(HttpMethod.POST, "/api/applications/" + id + "/decision", officer,
                Map.of("decision", "APPROVE", "reason", "Collateral offered and long banking relationship"), Map.class);
        assertThat(decided.getBody().get("status")).isEqualTo("APPROVED");
        assertThat(((Map<?, ?>) decided.getBody().get("decision")).get("override")).isEqualTo(true);

        String admin = login("admin@it.test");
        var audit = call(HttpMethod.GET, "/api/audit-logs?entityType=LoanApplication&entityId=" + id + "&sort=createdAt,asc&sort=id,asc",
                admin, null, Map.class);
        List<Object> actions = ((List<?>) audit.getBody().get("content")).stream().map(e -> (Object) ((Map<?, ?>) e).get("action")).toList();
        assertThat(actions).containsExactly("APPLICATION_SUBMITTED", "COMPLIANCE_EVALUATED", "RISK_ASSESSED",
                "DECISION_RECORDED");
        List<Object> actors = ((List<?>) audit.getBody().get("content")).stream()
                .map(e -> (Object) ((Map<?, ?>) e).get("actorEmail")).toList();
        assertThat(actors).containsExactly("owner1@it.test", "system@creditsense", "system@creditsense", "officer@it.test");
    }

    @Test
    void complianceFailureIsTerminalAndExplained() {
        String applicant = register("owner3@it.test");
        String good = gstin("BLMPS1234Q");
        String typo = good.substring(0, 14) + (good.charAt(14) == 'A' ? 'B' : 'A');
        Map<?, ?> app = call(HttpMethod.POST, "/api/applications", applicant, application(typo, "BLMPS1234Q"), Map.class)
                .getBody();
        assertThat(app.get("status")).isEqualTo("COMPLIANCE_FAILED");
        assertThat((List<?>) app.get("complianceChecks")).anySatisfy(c -> {
            assertThat(((Map<?, ?>) c).get("checkType")).isEqualTo("GST_VALIDITY");
            assertThat(((Map<?, ?>) c).get("passed")).isEqualTo(false);
        });
        assertThat(app.get("riskAssessment")).isNull();

        String officer = login("officer@it.test");
        var scoring = call(HttpMethod.POST, "/api/applications/" + app.get("id") + "/risk-assessment", officer, null, Map.class);
        assertThat(scoring.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void modelOutageRoutesToManualReview() {
        ML_STATUS.set(503);
        String applicant = register("owner4@it.test");
        Map<?, ?> app = call(HttpMethod.POST, "/api/applications", applicant, application(gstin("CQRFT5678Z"), "CQRFT5678Z"),
                Map.class).getBody();
        assertThat(app.get("status")).isEqualTo("MANUAL_REVIEW");
        assertThat((String) app.get("manualReviewReason")).startsWith("Risk model unavailable");
    }

    @Test
    void reScoreWorksAsSoonAsTheModelIsBackEvenIfTheCircuitBreakerOpened() {
        ML_STATUS.set(503); // the model is asleep or starting
        String applicant = register("owner4b@it.test");
        Map<?, ?> app = call(HttpMethod.POST, "/api/applications", applicant, application(gstin("CQRFT5679A"), "CQRFT5679A"),
                Map.class).getBody();
        assertThat(app.get("status")).isEqualTo("MANUAL_REVIEW");
        assertThat(app.get("awaitingModel")).isEqualTo(true);
        String officer = login("officer@it.test");
        String rescore = "/api/applications/" + app.get("id") + "/risk-assessment";
        call(HttpMethod.POST, rescore, officer, null, Map.class);
        assertThat(ml.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);

        ML_STATUS.set(200); // the model is up again, while the breaker would still fail fast for 30 s
        Map<?, ?> scored = call(HttpMethod.POST, rescore, officer, null, Map.class).getBody();
        assertThat(scored.get("status")).isEqualTo("RISK_SCORED");
        assertThat(ml.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void anApplicationThatWaitedForASleepingModelIsScoredOnItsOwnOnceTheModelAnswers() throws Exception {
        ML_STATUS.set(503); // asleep
        String applicant = register("owner4c@it.test");
        Map<?, ?> app = call(HttpMethod.POST, "/api/applications", applicant, application(gstin("CQRFT5680B"), "CQRFT5680B"),
                Map.class).getBody();
        assertThat(app.get("status")).isEqualTo("MANUAL_REVIEW");
        assertThat(app.get("awaitingModel")).isEqualTo(true);

        ML_STATUS.set(200); // woken up, with nobody clicking Re-score
        String url = "/api/applications/" + app.get("id");
        Map<?, ?> now = app;
        for (int i = 0; i < 60 && !"RISK_SCORED".equals(now.get("status")); i++) {
            Thread.sleep(500);
            now = call(HttpMethod.GET, url, applicant, null, Map.class).getBody();
        }
        assertThat(now.get("status")).isEqualTo("RISK_SCORED");
        assertThat(now.get("awaitingModel")).isEqualTo(false);
        assertThat(now.get("riskAssessment")).isNotNull();
    }

    @Test
    void malformedInputIsRejectedWithFieldErrors() {
        String applicant = register("owner5@it.test");
        var res = call(HttpMethod.POST, "/api/applications", applicant, application("NOT-A-GSTIN", "DSTPU9012W"),
                Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((List<?>) res.getBody().get("fieldErrors"))
                .anySatisfy(f -> assertThat(((Map<?, ?>) f).get("field")).isEqualTo("business.gstin"));
    }

    @Test
    void auditTrailIsAppendOnly() {
        register("owner6@it.test");
        assertThatThrownBy(() -> jdbc.update("update audit_logs set action = 'TAMPERED'"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from audit_logs")).hasMessageContaining("append-only");
    }

    static String refreshCookie(ResponseEntity<?> res) {
        return res.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).stream()
                .filter(c -> c.startsWith("cs_refresh=")).reduce((a, b) -> b).orElseThrow();
    }

    static String cookieValue(String setCookie) {
        return setCookie.substring("cs_refresh=".length(), setCookie.indexOf(';'));
    }

    ResponseEntity<Map> postWithCookie(String url, String refreshToken) {
        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.COOKIE, "cs_refresh=" + refreshToken);
        return http.exchange(url, HttpMethod.POST, new HttpEntity<>(null, h), Map.class);
    }

    @Test
    void refreshTokenLivesInAnHttpOnlyCookieAndRotatesWithReuseDetection() {
        register("owner7@it.test");
        var login = http.postForEntity("/api/auth/login", Map.of("email", "owner7@it.test", "password", "Passw0rd1"),
                Map.class);
        assertThat(login.getBody()).containsKey("accessToken").doesNotContainKey("refreshToken");
        String set = refreshCookie(login);
        assertThat(set).contains("HttpOnly", "SameSite=Strict", "Path=/api/auth", "Max-Age=");
        String first = cookieValue(set);

        var rotated = postWithCookie("/api/auth/refresh", first);
        assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rotated.getBody()).containsKey("accessToken").doesNotContainKey("refreshToken");
        String second = cookieValue(refreshCookie(rotated));
        assertThat(second).isNotEqualTo(first);

        var reused = postWithCookie("/api/auth/refresh", first);
        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refreshCookie(reused)).contains("Max-Age=0"); // the browser drops the dead cookie
        // reuse revoked every session of the user
        assertThat(postWithCookie("/api/auth/refresh", second).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // API clients without a cookie jar can send the token in the body
        var again = http.postForEntity("/api/auth/login", Map.of("email", "owner7@it.test", "password", "Passw0rd1"),
                Map.class);
        String third = cookieValue(refreshCookie(again));
        var viaBody = http.postForEntity("/api/auth/refresh", Map.of("refreshToken", third), Map.class);
        assertThat(viaBody.getStatusCode()).isEqualTo(HttpStatus.OK);
        String fourth = cookieValue(refreshCookie(viaBody));

        var logout = postWithCookie("/api/auth/logout", fourth);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(refreshCookie(logout)).contains("Max-Age=0");
        assertThat(postWithCookie("/api/auth/refresh", fourth).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.postForEntity("/api/auth/refresh", null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.postForEntity("/api/auth/login", Map.of("email", "owner7@it.test", "password", "wrong"),
                Map.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anApplicationWithoutConsentIsRejected() {
        String applicant = register("owner10@it.test");
        var body = new java.util.HashMap<>(application(gstin("BKTPR4821L"), "BKTPR4821L"));
        body.put("consent", false);
        var res = call(HttpMethod.POST, "/api/applications", applicant, body, Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(res.getBody())).contains("consent");
    }

    @Test
    void anApplicantCanEraseTheirAccountAndEverythingTheySubmitted() {
        String applicant = register("owner11@it.test");
        var submitted = call(HttpMethod.POST, "/api/applications", applicant, application(gstin("CKTPR4821M"), "CKTPR4821M"), Map.class);
        Number id = (Number) submitted.getBody().get("id");
        assertThat(jdbc.queryForObject("select count(*) from loan_applications where consent_at is not null and id = ?",
                Integer.class, id.longValue())).isEqualTo(1);

        assertThat(call(HttpMethod.DELETE, "/api/auth/me", applicant, null, Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(users.findByEmailIgnoreCase("owner11@it.test")).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from loan_applications where id = ?", Integer.class, id.longValue())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from risk_assessments where application_id = ?", Integer.class, id.longValue())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'ACCOUNT_ERASED' and actor_email = 'owner11@it.test'",
                Integer.class)).isEqualTo(1);
        // a still-unexpired access token (15 minutes at most) can read nothing and create nothing for the erased user
        assertThat(call(HttpMethod.POST, "/api/applications", applicant, application(gstin("DKTPR4821N"), "DKTPR4821N"), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void staffAccountsCannotBeErasedThroughTheApplicantEndpoint() {
        String officer = login("officer@it.test");
        assertThat(call(HttpMethod.DELETE, "/api/auth/me", officer, null, Map.class).getStatusCode())
                .isIn(HttpStatus.BAD_REQUEST, HttpStatus.FORBIDDEN);
        assertThat(users.findByEmailIgnoreCase("officer@it.test")).isPresent();
    }

    @Test
    void googleSignInGivesRolesFromTheConfiguredEmailListsAndSetsTheRefreshCookie() {
        org.mockito.Mockito.when(google.verify("admin-token")).thenReturn(new GoogleTokenVerifier.Identity("owner.admin@gmail.com", "Owner"));
        org.mockito.Mockito.when(google.verify("officer-token")).thenReturn(new GoogleTokenVerifier.Identity("officer.g@gmail.com", "Officer"));
        org.mockito.Mockito.when(google.verify("user-token")).thenReturn(new GoogleTokenVerifier.Identity("somebody@gmail.com", "Somebody"));
        org.mockito.Mockito.when(google.verify("bad-token")).thenThrow(new org.springframework.security.oauth2.jwt.JwtException("bad"));

        var admin = http.postForEntity("/api/auth/google", Map.of("credential", "admin-token"), Map.class);
        assertThat(admin.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) admin.getBody().get("user")).get("role")).isEqualTo("ADMIN");
        assertThat(String.join(";", admin.getHeaders().get(HttpHeaders.SET_COOKIE))).contains("cs_refresh=", "HttpOnly");
        var officer = http.postForEntity("/api/auth/google", Map.of("credential", "officer-token"), Map.class);
        assertThat(((Map<?, ?>) officer.getBody().get("user")).get("role")).isEqualTo("LOAN_OFFICER");
        var user = http.postForEntity("/api/auth/google", Map.of("credential", "user-token"), Map.class);
        String userToken = (String) user.getBody().get("accessToken");
        assertThat(((Map<?, ?>) user.getBody().get("user")).get("role")).isEqualTo("APPLICANT");
        assertThat(http.postForEntity("/api/auth/google", Map.of("credential", "bad-token"), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // an ordinary Google user cannot reach staff endpoints; the admin can
        assertThat(call(HttpMethod.GET, "/api/audit-logs", userToken, null, Map.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(HttpMethod.GET, "/api/audit-logs", (String) admin.getBody().get("accessToken"), null, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        // signing in again keeps the same account
        assertThat(http.postForEntity("/api/auth/google", Map.of("credential", "user-token"), Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("select count(*) from users where email = 'somebody@gmail.com'", Integer.class)).isEqualTo(1);
    }

    @Test
    void theSignInPageCanDiscoverTheOptions() {
        var res = http.getForEntity("/api/auth/options", Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsKeys("googleClientId", "passwordLogin");
    }

    @Test
    void repeatedFailedSignInsAreThrottledEvenWithTheRightPassword() {
        register("owner8@it.test");
        var wrong = Map.of("email", "owner8@it.test", "password", "not-my-password");
        for (int i = 0; i < 5; i++) {
            assertThat(http.postForEntity("/api/auth/login", wrong, Map.class).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        var blocked = http.postForEntity("/api/auth/login",
                Map.of("email", "owner8@it.test", "password", "Passw0rd1"), Map.class);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(blocked.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
        assertThat((String) blocked.getBody().get("message")).contains("too many failed sign-in attempts");
    }

    @Test
    void expiredRefreshTokensArePurgedButLiveAndRevokedOnesAreKept() {
        register("owner9@it.test");
        Long userId = users.findByEmailIgnoreCase("owner9@it.test").orElseThrow().getId();
        jdbc.update("insert into refresh_tokens (user_id, token_hash, expires_at, revoked) values "
                + "(?, 'expired-hash-it', now() - interval '1 day', true), "
                + "(?, 'revoked-live-hash-it', now() + interval '1 day', true)", userId, userId);
        refreshTokens.purgeExpired();
        List<String> left = jdbc.queryForList("select token_hash from refresh_tokens where user_id = ?", String.class,
                userId);
        assertThat(left).doesNotContain("expired-hash-it").contains("revoked-live-hash-it");
        assertThat(left).hasSize(2); // plus the session created at registration
    }
}
