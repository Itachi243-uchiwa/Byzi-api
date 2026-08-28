package com.byzi.api.security.apple;

/**
 * emailVerified reflete le claim Apple "email_verified" : un email non verifie ne doit pas
 * ecraser une donnee de contact fiable deja en base (cf. AuthService.touchLastLogin).
 *
 * <p>nonce est le claim "nonce" du token (deja {@code SHA-256(nonce brut)} cote Apple), ou
 * {@code null} si le token n'en porte pas. {@link com.byzi.api.service.AuthService} le
 * compare au nonce brut fourni par le client (story 01.2, anti-rejeu).
 */
public record AppleIdTokenClaims(String subject, String email, boolean emailVerified, String nonce) {

    /** Compatibilite : constructions historiques sans nonce (tests). */
    public AppleIdTokenClaims(String subject, String email, boolean emailVerified) {
        this(subject, email, emailVerified, null);
    }
}
