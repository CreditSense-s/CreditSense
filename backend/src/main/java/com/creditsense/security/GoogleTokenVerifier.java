package com.creditsense.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

import java.util.Set;

/** Verifies a Google "Sign in with Google" ID token: signature, expiry, issuer, audience and verified email. */
@Component
public class GoogleTokenVerifier {
  public record Identity(String email, String name) {}

  private static final Set<String> ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");
  private final JwtDecoder decoder;
  private final String clientId;

  @Autowired
  public GoogleTokenVerifier(@Value("${google.client-id:}") String clientId,
                             @Value("${google.jwks-uri:https://www.googleapis.com/oauth2/v3/certs}") String jwksUri) {
    this(clientId, NimbusJwtDecoder.withJwkSetUri(jwksUri).build());
  }

  /** Visible for tests, which supply a decoder backed by a local key instead of Google's. */
  GoogleTokenVerifier(String clientId, JwtDecoder decoder) {
    this.clientId = clientId;
    if (decoder instanceof NimbusJwtDecoder nimbus) {
      OAuth2TokenValidator<Jwt> claims = jwt -> {
        boolean issuerOk = ISSUERS.contains(jwt.getClaimAsString("iss"));
        boolean audienceOk = jwt.getAudience() != null && jwt.getAudience().contains(this.clientId);
        boolean emailOk = Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified")) && jwt.getClaimAsString("email") != null;
        return issuerOk && audienceOk && emailOk
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Google token rejected", null));
      };
      nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(), claims));
    }
    this.decoder = decoder;
  }

  /** Returns the verified identity, or throws {@link JwtException} for anything that is not a valid token for this app. */
  public Identity verify(String credential) {
    if (clientId.isBlank()) throw new JwtException("Google sign-in is not configured");
    Jwt jwt = decoder.decode(credential);
    return new Identity(jwt.getClaimAsString("email").toLowerCase(), jwt.getClaimAsString("name"));
  }
}
