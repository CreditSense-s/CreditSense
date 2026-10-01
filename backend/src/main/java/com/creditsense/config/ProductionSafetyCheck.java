package com.creditsense.config;

import com.creditsense.risk.MlProperties;
import com.creditsense.security.AccessProperties;
import com.creditsense.security.JwtProperties;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Stops a production start with the development secrets that ship in this repository. Outside the
 * {@code prod} profile the same findings are only logged, so {@code docker compose up} keeps working.
 */
@Component
public class ProductionSafetyCheck {

    private static final Logger log = LoggerFactory.getLogger(ProductionSafetyCheck.class);

    /** Every default secret that appears in application.yml, docker-compose.yml or .env.example. */
    static final Set<String> KNOWN_JWT_SECRETS = Set.of(
            "dev-only-secret-change-me-please-32bytes!!",
            "local-dev-jwt-secret-change-me-in-production",
            "replace-with-at-least-32-random-bytes-of-secret");
    static final Set<String> KNOWN_ML_TOKENS = Set.of(
            "local-dev-ml-token", "replace-with-a-random-service-token");

    private final JwtProperties jwt;
    private final MlProperties ml;
    private final Environment env;
    private final List<String> corsOrigins;
    private final boolean seedEnabled;
    private final AccessProperties access;

    public ProductionSafetyCheck(JwtProperties jwt, MlProperties ml, Environment env,
            @Value("${creditsense.cors.allowed-origins:}") List<String> corsOrigins,
            @Value("${creditsense.seed.enabled:false}") boolean seedEnabled, AccessProperties access) {
        this.access = access;
        this.jwt = jwt;
        this.ml = ml;
        this.env = env;
        this.corsOrigins = corsOrigins;
        this.seedEnabled = seedEnabled;
    }

    /** Runs while the context starts, before the web server accepts requests or demo data is seeded. */
    @PostConstruct
    public void verify() {
        List<String> problems = problems();
        boolean prod = env.acceptsProfiles(Profiles.of("prod"));
        if (problems.isEmpty()) {
            return;
        }
        if (prod) {
            throw new IllegalStateException("Refusing to start with the prod profile: " + String.join("; ", problems));
        }
        problems.forEach(p -> log.warn("Not safe for production: {}", p));
        if (seedEnabled) {
            log.warn("Demo data seeding is enabled; demo accounts use a published password");
        }
    }

    List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (KNOWN_JWT_SECRETS.contains(jwt.secret())) {
            problems.add("JWT_SECRET is a published development default");
        }
        String token = ml.serviceToken();
        if (token == null || token.isBlank()) {
            problems.add("ML_SERVICE_TOKEN is empty, so the ML service accepts unauthenticated calls");
        } else if (KNOWN_ML_TOKENS.contains(token)) {
            problems.add("ML_SERVICE_TOKEN is a published development default");
        }
        if (corsOrigins.stream().anyMatch(o -> o.strip().equals("*"))) {
            problems.add("CORS_ALLOWED_ORIGINS must list origins explicitly, not '*'");
        }
        if (seedEnabled) {
            problems.add("SEED_DEMO_DATA creates demo accounts with a published password; turn it off");
        }
        if (!access.passwordLoginEnabled()) {
            // Google is then the only way in, so it has to be configured and someone has to be admin.
            if (!access.googleEnabled()) problems.add("GOOGLE_CLIENT_ID is required when password sign-in is off");
            if (access.adminEmails().isEmpty()) problems.add("ADMIN_EMAILS must list at least one admin Gmail address");
        }
        return problems;
    }
}
