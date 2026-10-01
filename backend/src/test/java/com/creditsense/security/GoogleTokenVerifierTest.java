package com.creditsense.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Uses a locally generated key as a stand-in for Google's signing keys. */
class GoogleTokenVerifierTest {
  static final String CLIENT_ID = "test-client.apps.googleusercontent.com";
  static KeyPair keys;
  static GoogleTokenVerifier verifier;

  @BeforeAll static void setUp() throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("RSA"); g.initialize(2048); keys = g.generateKeyPair();
    verifier = new GoogleTokenVerifier(CLIENT_ID, NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.getPublic()).build());
  }

  static String token(KeyPair signer, String iss, String aud, boolean emailVerified, long expiresInMs) throws Exception {
    JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(iss).audience(aud).subject("1234")
        .claim("email", "Person@Gmail.com").claim("email_verified", emailVerified).claim("name", "Person")
        .issueTime(new Date()).expirationTime(new Date(System.currentTimeMillis() + expiresInMs)).build();
    SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
    jwt.sign(new RSASSASigner(signer.getPrivate()));
    return jwt.serialize();
  }

  @Test void accepts_a_valid_token_and_lowercases_the_email() throws Exception {
    var id = verifier.verify(token(keys, "https://accounts.google.com", CLIENT_ID, true, 60_000));
    assertEquals("person@gmail.com", id.email());
  }
  @Test void accepts_the_short_issuer_form() throws Exception {
    assertDoesNotThrow(() -> verifier.verify(token(keys, "accounts.google.com", CLIENT_ID, true, 60_000)));
  }
  @Test void rejects_a_token_for_another_app() throws Exception {
    assertThrows(JwtException.class, () -> verifier.verify(token(keys, "https://accounts.google.com", "someone-else", true, 60_000)));
  }
  @Test void rejects_a_wrong_issuer() throws Exception {
    assertThrows(JwtException.class, () -> verifier.verify(token(keys, "https://evil.example", CLIENT_ID, true, 60_000)));
  }
  @Test void rejects_an_unverified_email() throws Exception {
    assertThrows(JwtException.class, () -> verifier.verify(token(keys, "https://accounts.google.com", CLIENT_ID, false, 60_000)));
  }
  @Test void rejects_an_expired_token() throws Exception {
    assertThrows(JwtException.class, () -> verifier.verify(token(keys, "https://accounts.google.com", CLIENT_ID, true, -3_600_000)));
  }
  @Test void rejects_a_token_signed_with_a_different_key() throws Exception {
    KeyPairGenerator g = KeyPairGenerator.getInstance("RSA"); g.initialize(2048);
    assertThrows(JwtException.class, () -> verifier.verify(token(g.generateKeyPair(), "https://accounts.google.com", CLIENT_ID, true, 60_000)));
  }
  @Test void rejects_everything_when_google_is_not_configured() throws Exception {
    var off = new GoogleTokenVerifier("", NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.getPublic()).build());
    assertThrows(JwtException.class, () -> off.verify(token(keys, "https://accounts.google.com", CLIENT_ID, true, 60_000)));
  }
}
