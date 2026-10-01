package com.creditsense.auth;

import com.creditsense.domain.Role;
import com.creditsense.domain.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {}

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 8, max = 72)
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "password must contain letters and digits")
            String password,
            @NotBlank @Size(max = 120) String fullName) {}

    public record GoogleRequest(@NotBlank String credential) {}

    /** What the sign-in page needs to know before showing its buttons. */
    public record AuthOptions(String googleClientId, boolean passwordLogin) {}

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}

    /** For API clients without a cookie jar; browsers send the refresh token as an HttpOnly cookie instead. */
    public record RefreshRequest(String refreshToken) {}

    public record UserDto(Long id, String email, String fullName, Role role) {
        public static UserDto of(User u) {
            return new UserDto(u.getId(), u.getEmail(), u.getFullName(), u.getRole());
        }
    }

    /** Internal result of a sign-in or rotation; the refresh token leaves the server only as a cookie. */
    public record TokenResponse(String accessToken, String refreshToken, String tokenType, long expiresIn, UserDto user) {
        public SessionResponse session() {
            return new SessionResponse(accessToken, tokenType, expiresIn, user);
        }
    }

    /** What the client sees: a short-lived access token and the signed-in user. */
    public record SessionResponse(String accessToken, String tokenType, long expiresIn, UserDto user) {}
}
