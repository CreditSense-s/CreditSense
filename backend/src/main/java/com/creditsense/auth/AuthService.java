package com.creditsense.auth;

import com.creditsense.audit.AuditService;
import com.creditsense.auth.AuthDtos.*;
import com.creditsense.common.Actor;
import com.creditsense.common.ApiException;
import com.creditsense.common.RateLimitedException;
import com.creditsense.domain.Role;
import com.creditsense.domain.User;
import com.creditsense.repo.UserRepository;
import com.creditsense.repo.ApplicantRepository;
import com.creditsense.repo.LoanApplicationRepository;
import com.creditsense.security.AccessProperties;
import com.creditsense.security.AuthUser;
import com.creditsense.security.GoogleTokenVerifier;
import com.creditsense.security.JwtService;
import com.creditsense.security.LoginThrottle;
import com.creditsense.security.RefreshTokenService;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {


    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final RefreshTokenService refreshTokens;
    private final AuditService audit;
    private final LoginThrottle throttle;
    private final AccessProperties access;
    private final GoogleTokenVerifier google;
    private final ApplicantRepository applicants;
    private final LoanApplicationRepository applications;
    // Compared against when the email is unknown, so response time does not reveal which emails exist.
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt,
            RefreshTokenService refreshTokens, AuditService audit, LoginThrottle throttle, AccessProperties access,
            GoogleTokenVerifier google, ApplicantRepository applicants, LoanApplicationRepository applications) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.refreshTokens = refreshTokens;
        this.audit = audit;
        this.throttle = throttle;
        this.access = access;
        this.google = google;
        this.applicants = applicants;
        this.applications = applications;
        this.dummyHash = encoder.encode(java.util.UUID.randomUUID().toString());
    }

    /** Self-registration always creates an APPLICANT; staff accounts are provisioned separately. */
    @Transactional
    public TokenResponse register(RegisterRequest req) {
        requirePasswordLogin();
        String email = req.email().strip().toLowerCase();
        if (users.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("an account with this email already exists");
        }
        User u = new User();
        u.setEmail(email);
        u.setFullName(req.fullName().strip());
        u.setPasswordHash(encoder.encode(req.password()));
        u.setRole(Role.APPLICANT);
        users.save(u);
        audit.record(Actor.of(u), "USER_REGISTERED", "User", u.getId(), null,
                Map.of("email", email, "role", u.getRole()));
        return tokens(u);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public TokenResponse login(LoginRequest req, String client) {
        requirePasswordLogin();
        String email = req.email().strip().toLowerCase();
        Duration wait = throttle.retryAfter(email, client);
        if (!wait.isZero()) {
            long minutes = Math.max(1, (wait.toSeconds() + 59) / 60);
            throw new RateLimitedException("too many failed sign-in attempts; try again in " + minutes
                    + (minutes == 1 ? " minute" : " minutes"), wait);
        }
        User u = users.findByEmailIgnoreCase(email).orElse(null);
        boolean ok = encoder.matches(req.password(), u == null ? dummyHash : u.getPasswordHash());
        if (u == null || !ok || !u.isEnabled()) {
            throttle.failed(email, client);
            audit.record(new Actor(u == null ? null : u.getId(), email, u == null ? "UNKNOWN" : u.getRole().name()),
                    "LOGIN_FAILED", "User", u == null ? null : u.getId(), null, null);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid email or password");
        }
        throttle.succeeded(email);
        audit.record(Actor.of(u), "LOGIN_SUCCEEDED", "User", u.getId(), null, null);
        return tokens(u);
    }

    public AuthOptions options() {
        return new AuthOptions(access.googleClientId(), access.passwordLoginEnabled());
    }

    /**
     * Signs in with a Google ID token. The first sign-in creates the account; the role always follows the
     * configured admin/officer lists, so removing an address there demotes the person at their next sign-in.
     */
    @Transactional
    public TokenResponse googleLogin(String credential) {
        GoogleTokenVerifier.Identity who;
        try {
            who = google.verify(credential);
        } catch (JwtException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Google sign-in could not be verified");
        }
        Role role = access.roleFor(who.email());
        User u = users.findByEmailIgnoreCase(who.email()).orElse(null);
        if (u == null) {
            u = new User();
            u.setEmail(who.email());
            u.setFullName(who.name() == null || who.name().isBlank() ? who.email() : who.name().strip());
            u.setPasswordHash(encoder.encode(java.util.UUID.randomUUID().toString())); // Google accounts have no password
            u.setRole(role);
            users.save(u);
            audit.record(Actor.of(u), "USER_REGISTERED", "User", u.getId(), null,
                    Map.of("email", who.email(), "role", role, "provider", "google"));
        } else {
            if (!u.isEnabled()) throw new ApiException(HttpStatus.UNAUTHORIZED, "this account is disabled");
            if (u.getRole() != role) {
                Role before = u.getRole();
                u.setRole(role);
                audit.record(Actor.of(u), "ROLE_SYNCED", "User", u.getId(), Map.of("role", before), Map.of("role", role));
            }
        }
        audit.record(Actor.of(u), "LOGIN_SUCCEEDED", "User", u.getId(), null, Map.of("provider", "google"));
        return tokens(u);
    }

    /**
     * Erases an applicant account and everything they submitted. Audit entries are append-only (enforced in
     * the database) and stay as a security log; the privacy notice says so.
     */
    @Transactional
    public void deleteAccount(AuthUser user) {
        if (user.role() != Role.APPLICANT) {
            throw ApiException.badRequest("only applicant accounts can be erased here; staff accounts are managed by an administrator");
        }
        audit.record(user.actor(), "ACCOUNT_ERASED", "User", user.id(), null, null);
        applications.deleteAll(applications.findByApplicantUserIdOrderBySubmittedAtDesc(user.id()));
        applications.flush();
        applicants.findByUserId(user.id()).ifPresent(applicants::delete);
        applicants.flush();
        users.deleteById(user.id()); // refresh tokens go with it (ON DELETE CASCADE)
    }

    private void requirePasswordLogin() {
        if (!access.passwordLoginEnabled()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "password sign-in is turned off; sign in with Google");
        }
    }

    /** noRollbackFor: detecting a reused token must still commit the revocation of every session. */
    @Transactional(noRollbackFor = ApiException.class)
    public TokenResponse refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "no session to refresh; sign in again");
        }
        RefreshTokenService.Rotated r = refreshTokens.rotate(refreshToken);
        return new TokenResponse(jwt.issueAccessToken(r.user()), r.refreshToken(), "Bearer",
                jwt.accessTokenTtlSeconds(), UserDto.of(r.user()));
    }

    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            refreshTokens.revoke(refreshToken);
        }
    }

    @Transactional(readOnly = true)
    public UserDto me(Long userId) {
        return users.findById(userId).map(UserDto::of).orElseThrow(() -> ApiException.notFound("user"));
    }

    private TokenResponse tokens(User u) {
        return new TokenResponse(jwt.issueAccessToken(u), refreshTokens.issue(u), "Bearer",
                jwt.accessTokenTtlSeconds(), UserDto.of(u));
    }
}
