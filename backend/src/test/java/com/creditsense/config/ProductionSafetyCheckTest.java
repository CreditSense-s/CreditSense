package com.creditsense.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.creditsense.risk.MlProperties;
import com.creditsense.security.AccessProperties;
import com.creditsense.security.JwtProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionSafetyCheckTest {

    static final AccessProperties PASSWORDS = new AccessProperties(true, "", List.of(), List.of());
    static final AccessProperties GOOGLE_ONLY = new AccessProperties(false, "cid.apps.googleusercontent.com",
            List.of("owner@gmail.com"), List.of());

    static ProductionSafetyCheck check(String jwtSecret, String mlToken, List<String> cors, String... profiles) {
        return check(jwtSecret, mlToken, cors, false, PASSWORDS, profiles);
    }

    static ProductionSafetyCheck check(String jwtSecret, String mlToken, List<String> cors, boolean seed,
            AccessProperties access, String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return new ProductionSafetyCheck(new JwtProperties(jwtSecret, null, null, null),
                new MlProperties(null, mlToken, null, 0), env, cors, seed, access);
    }

    @Test
    void refusesDemoAccountsInProd() {
        var c = check("7cW2nP9qLx4vR8tY1zB6mK3sD5fH0jG2", "svc-8f3a1c9e7b2d4a6f", List.of("https://credit.example.in"),
                true, GOOGLE_ONLY, "prod");
        assertThat(c.problems()).singleElement().asString().contains("SEED_DEMO_DATA");
    }

    @Test
    void googleOnlyProdNeedsAClientIdAndAnAdmin() {
        var none = new AccessProperties(false, "", List.of(), List.of());
        var c = check("7cW2nP9qLx4vR8tY1zB6mK3sD5fH0jG2", "svc-8f3a1c9e7b2d4a6f", List.of("https://credit.example.in"),
                false, none, "prod");
        assertThat(c.problems()).hasSize(2);
        assertThatThrownBy(c::verify).hasMessageContaining("GOOGLE_CLIENT_ID").hasMessageContaining("ADMIN_EMAILS");
        assertThat(check("7cW2nP9qLx4vR8tY1zB6mK3sD5fH0jG2", "svc-8f3a1c9e7b2d4a6f", List.of("https://credit.example.in"),
                false, GOOGLE_ONLY, "prod").problems()).isEmpty();
    }

    @Test
    void refusesToStartInProdWithPublishedSecrets() {
        var c = check("local-dev-jwt-secret-change-me-in-production", "local-dev-ml-token",
                List.of("https://credit.example.in"), "prod");
        assertThat(c.problems()).hasSize(2);
        assertThatThrownBy(c::verify).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET").hasMessageContaining("ML_SERVICE_TOKEN");
    }

    @Test
    void refusesAnEmptyServiceTokenAndWildcardCors() {
        var c = check("a-freshly-generated-secret-of-enough-length", " ", List.of("*"), "prod");
        assertThat(c.problems()).hasSize(2);
    }

    @Test
    void onlyWarnsOutsideProd() {
        var c = check("dev-only-secret-change-me-please-32bytes!!", "", List.of("http://localhost:3000"));
        assertThat(c.problems()).isNotEmpty();
        assertThatNoException().isThrownBy(c::verify);
    }

    @Test
    void acceptsRealSecretsInProd() {
        var c = check("7cW2nP9qLx4vR8tY1zB6mK3sD5fH0jG2", "svc-8f3a1c9e7b2d4a6f",
                List.of("https://credit.example.in"), "prod");
        assertThat(c.problems()).isEmpty();
        assertThatNoException().isThrownBy(c::verify);
    }
}
