package com.byzi.api.security.apple;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Verification du nonce anti-rejeu Sign in with Apple (story 01.2).
 *
 * <p>Le client genere un nonce aleatoire, passe {@code SHA-256(nonce)} en hexadecimal a la
 * requete Apple (Apple le recopie dans le claim {@code nonce} du token), et envoie le nonce
 * <em>brut</em> au backend. Le serveur re-hashe le nonce brut et exige l'egalite avec le
 * claim : un token intercepte ne peut donc pas etre rejoue sans le nonce brut correspondant,
 * qui n'a jamais transite en clair cote Apple.
 */
public final class AppleNonce {

    private AppleNonce() {
    }

    /**
     * @param rawNonce        nonce brut recu du client (peut etre nul/vide).
     * @param tokenNonceClaim claim {@code nonce} du token verifie (peut etre nul).
     * @return {@code true} si {@code SHA-256(rawNonce)} en hex correspond, en temps constant,
     * a {@code tokenNonceClaim}. {@code false} si l'un des deux manque.
     */
    public static boolean matches(String rawNonce, String tokenNonceClaim) {
        if (rawNonce == null || rawNonce.isBlank() || tokenNonceClaim == null || tokenNonceClaim.isBlank()) {
            return false;
        }
        String hashed = sha256Hex(rawNonce);
        return MessageDigest.isEqual(
                hashed.getBytes(StandardCharsets.UTF_8),
                tokenNonceClaim.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 fait partie de toute JVM standard : cette branche ne peut pas arriver.
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }
}
