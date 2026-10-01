package com.creditsense.security;

import com.creditsense.domain.Role;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AccessPolicyTest {
  static final String SECRET = "a-real-random-secret-with-32-or-more-chars";

  @Test void admin_and_officer_are_matched_case_insensitively_and_everyone_else_is_an_applicant() {
    var p = new AccessPolicy(false, "cid", SECRET, List.of(" Admin@Gmail.com "), List.of("officer@gmail.com"));
    assertEquals(Role.ADMIN, p.roleFor("ADMIN@gmail.com"));
    assertEquals(Role.LOAN_OFFICER, p.roleFor("officer@gmail.com"));
    assertEquals(Role.APPLICANT, p.roleFor("random@gmail.com"));
  }
  @Test void password_sign_in_exists_only_in_demo_mode() {
    assertFalse(new AccessPolicy(false, "cid", SECRET, List.of("a@x.com"), List.of()).passwordLoginEnabled());
    assertTrue(new AccessPolicy(true, "", AccessPolicy.DEFAULT_JWT_SECRET, List.of(), List.of()).passwordLoginEnabled());
  }
  @Test void production_start_up_is_refused_without_google_a_real_secret_and_an_admin() {
    assertThrows(IllegalStateException.class, () -> new AccessPolicy(false, "", SECRET, List.of("a@x.com"), List.of()).failFastOnUnsafeProduction());
    assertThrows(IllegalStateException.class, () -> new AccessPolicy(false, "cid", AccessPolicy.DEFAULT_JWT_SECRET, List.of("a@x.com"), List.of()).failFastOnUnsafeProduction());
    assertThrows(IllegalStateException.class, () -> new AccessPolicy(false, "cid", SECRET, List.of(), List.of()).failFastOnUnsafeProduction());
    assertDoesNotThrow(() -> new AccessPolicy(false, "cid", SECRET, List.of("a@x.com"), List.of()).failFastOnUnsafeProduction());
  }
}
