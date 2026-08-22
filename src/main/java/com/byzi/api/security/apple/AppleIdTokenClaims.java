package com.byzi.api.security.apple;

/**
 * emailVerified reflete le claim Apple "email_verified" : un email non verifie ne doit pas
 * ecraser une donnee de contact fiable deja en base (cf. AuthService.touchLastLogin).
 */
public record AppleIdTokenClaims(String subject, String email, boolean emailVerified) {
}
