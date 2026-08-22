package com.byzi.api.security.apple;

import com.byzi.api.exception.InvalidAppleTokenException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 01.2 - validation du token Apple.
 * <p>
 * Ces tests couvrent le contrat de securite verifiable hors ligne : un token dont la signature
 * ne provient pas du JWKS d'Apple doit etre refuse. Le chemin nominal (signature valide)
 * exigerait de joindre le JWKS reel d'Apple, donc un appel reseau : il n'a pas sa place dans
 * une suite unitaire et est couvert par les tests d'integration cote client.
 * <p>
 * Le point defendu ici est le plus important : aucun token forge ne doit passer.
 */
class AppleTokenVerifierTest {

    private static final AppleAuthProperties PROPERTIES = new AppleAuthProperties(
            "https://appleid.apple.com", "com.byzi.app", "https://appleid.apple.com/auth/keys");

    private final AppleTokenVerifier verifier = new AppleTokenVerifier(PROPERTIES);

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void rejectsMissingToken(String token) {
        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(InvalidAppleTokenException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "pas-du-tout-un-jwt",
            "a.b.c",
            "eyJhbGciOiJub25lIn0.eyJzdWIiOiJhdHRhcXVhbnQifQ."
    })
    void rejectsMalformedOrUnsignedTokens(String token) {
        // Le dernier cas est un JWT "alg: none" : la faille classique consistant a presenter
        // un token sans signature en esperant que le serveur lise quand meme le payload.
        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(InvalidAppleTokenException.class);
    }

    @Test
    void rejectsTokenSignedByAnAttackerKey() throws Exception {
        // Token parfaitement forme, claims corrects, mais signe avec une cle qui n'est pas
        // celle d'Apple : c'est exactement le scenario qu'une verification de signature doit
        // arreter. L'accepter reviendrait a laisser n'importe qui s'authentifier comme
        // n'importe quel utilisateur.
        RSAKey attackerKey = new RSAKeyGenerator(2048).keyID("attaquant").generate();

        SignedJWT forged = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .keyID(attackerKey.getKeyID()).type(JOSEObjectType.JWT).build(),
                new JWTClaimsSet.Builder()
                        .issuer("https://appleid.apple.com")
                        .audience("com.byzi.app")
                        .subject("victime-apple-sub")
                        .issueTime(new Date())
                        .expirationTime(new Date(System.currentTimeMillis() + 600_000))
                        .build());
        forged.sign(new RSASSASigner(attackerKey.toPrivateKey()));

        assertThatThrownBy(() -> verifier.verify(forged.serialize()))
                .isInstanceOf(InvalidAppleTokenException.class);
    }

    @Test
    void constructorRejectsMalformedJwksUrl() {
        AppleAuthProperties broken = new AppleAuthProperties(
                "https://appleid.apple.com", "com.byzi.app", "ceci-nest-pas-une-url");

        assertThatThrownBy(() -> new AppleTokenVerifier(broken))
                .isInstanceOf(IllegalStateException.class);
    }
}
