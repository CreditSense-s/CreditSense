package com.creditsense.security;

import com.creditsense.domain.Role;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/** Decides who is admin/officer and whether password sign-in (demo accounts) exists at all. */
@Component
public class AccessPolicy {
  static final String DEFAULT_JWT_SECRET = "local-development-secret-must-be-at-least-32-bytes";

  private final boolean demoMode;
  private final String googleClientId;
  private final String jwtSecret;
  private final List<String> adminEmails;
  private final List<String> officerEmails;

  public AccessPolicy(@Value("${app.demo-mode:false}") boolean demoMode,
                      @Value("${google.client-id:}") String googleClientId,
                      @Value("${jwt.secret}") String jwtSecret,
                      @Value("${auth.admin-emails:}") List<String> adminEmails,
                      @Value("${auth.officer-emails:}") List<String> officerEmails) {
    this.demoMode = demoMode;
    this.googleClientId = googleClientId;
    this.jwtSecret = jwtSecret;
    this.adminEmails = normalise(adminEmails);
    this.officerEmails = normalise(officerEmails);
  }

  /** A public deployment must have Google sign-in, a real secret and an admin; it must not be in demo mode. */
  @PostConstruct
  void failFastOnUnsafeProduction() {
    if (demoMode) return;
    if (googleClientId.isBlank()) throw new IllegalStateException("GOOGLE_CLIENT_ID is required when APP_DEMO_MODE is off");
    if (DEFAULT_JWT_SECRET.equals(jwtSecret) || jwtSecret.length() < 32) throw new IllegalStateException("Set JWT_SECRET to a random value of at least 32 characters");
    if (adminEmails.isEmpty()) throw new IllegalStateException("ADMIN_EMAILS must list at least one admin Gmail address");
  }

  public boolean passwordLoginEnabled() { return demoMode; }
  public boolean demoMode() { return demoMode; }
  public String googleClientId() { return googleClientId; }

  public Role roleFor(String email) {
    String e = email.toLowerCase(Locale.ROOT);
    if (adminEmails.contains(e)) return Role.ADMIN;
    if (officerEmails.contains(e)) return Role.LOAN_OFFICER;
    return Role.APPLICANT;
  }

  private static List<String> normalise(List<String> raw) {
    return raw.stream().map(x -> x.trim().toLowerCase(Locale.ROOT)).filter(x -> !x.isEmpty()).toList();
  }
}
