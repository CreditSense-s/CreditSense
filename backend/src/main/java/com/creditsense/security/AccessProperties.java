package com.creditsense.security;

import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Who may sign in and with what role. People who sign in with Google become APPLICANTs unless their
 * verified email is listed as an administrator or loan officer.
 */
@ConfigurationProperties("creditsense.access")
public record AccessProperties(boolean passwordLoginEnabled, String googleClientId, List<String> adminEmails,
        List<String> officerEmails) {

    public AccessProperties {
        googleClientId = googleClientId == null ? "" : googleClientId.strip();
        adminEmails = normalise(adminEmails);
        officerEmails = normalise(officerEmails);
    }

    public boolean googleEnabled() {
        return !googleClientId.isBlank();
    }

    public com.creditsense.domain.Role roleFor(String email) {
        String e = email.strip().toLowerCase(Locale.ROOT);
        if (adminEmails.contains(e)) return com.creditsense.domain.Role.ADMIN;
        if (officerEmails.contains(e)) return com.creditsense.domain.Role.LOAN_OFFICER;
        return com.creditsense.domain.Role.APPLICANT;
    }

    private static List<String> normalise(List<String> raw) {
        return raw == null ? List.of()
                : raw.stream().map(x -> x.strip().toLowerCase(Locale.ROOT)).filter(x -> !x.isEmpty()).toList();
    }
}
