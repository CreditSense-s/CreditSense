package com.creditsense.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.creditsense.domain.Role;
import java.util.List;
import org.junit.jupiter.api.Test;

class AccessPropertiesTest {

    @Test
    void adminAndOfficerMatchCaseInsensitivelyAndEveryoneElseIsAnApplicant() {
        var p = new AccessProperties(false, "cid", List.of(" Admin@Gmail.com "), List.of("officer@gmail.com"));
        assertThat(p.roleFor("ADMIN@gmail.com")).isEqualTo(Role.ADMIN);
        assertThat(p.roleFor("Officer@Gmail.com")).isEqualTo(Role.LOAN_OFFICER);
        assertThat(p.roleFor("someone@gmail.com")).isEqualTo(Role.APPLICANT);
    }

    @Test
    void nothingIsElevatedWhenTheListsAreEmptyAndGoogleIsOffWithoutAClientId() {
        var p = new AccessProperties(true, null, null, List.of("", " "));
        assertThat(p.roleFor("admin@gmail.com")).isEqualTo(Role.APPLICANT);
        assertThat(p.googleEnabled()).isFalse();
    }
}
