package com.byzi.api.security.jwt;

import com.byzi.api.domain.Role;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private final JwtProperties properties = new JwtProperties(
            "unit-test-secret-key-at-least-32-bytes-long",
            900,
            2592000,
            "byzi-api-test"
    );
    private final JwtService jwtService = new JwtService(properties);

    @Test
    void generateAccessToken_thenParseAndValidate_returnsSameClaims() {
        UUID userId = UUID.randomUUID();

        String token = jwtService.generateAccessToken(userId, Role.USER);
        Optional<AccessTokenClaims> parsed = jwtService.parseAndValidate(token);

        assertThat(parsed).isPresent();
        assertThat(parsed.get().userId()).isEqualTo(userId);
        assertThat(parsed.get().role()).isEqualTo(Role.USER);
    }

    @Test
    void parseAndValidate_withTamperedToken_returnsEmpty() {
        UUID userId = UUID.randomUUID();
        String token = jwtService.generateAccessToken(userId, Role.USER);

        // Altere un caractere AU MILIEU de la signature.
        //
        // Le test alterait le DERNIER caractere, et echouait environ une fois sur trente
        // (constate le 2026-09-09 sur une execution complete). Une signature HMAC-SHA256 fait
        // 32 octets, soit 43 caracteres base64url : le dernier n'en porte que 4 significatifs,
        // les 2 derniers bits etant du bourrage. Or 'a' (011010) et 'b' (011011) ne different
        // QUE par ce dernier bit. Quand le jeton se terminait deja par 'a' ou 'b', la
        // "falsification" ne changeait donc aucun octet decode, la signature restait valide,
        // et le test tombait - en accusant le code au lieu de lui-meme.
        //
        // Au milieu de la signature, chaque bit compte : la falsification est reelle.
        int signatureStart = token.lastIndexOf('.') + 1;
        int target = signatureStart + (token.length() - signatureStart) / 2;
        String tampered = token.substring(0, target)
                + (token.charAt(target) == 'a' ? 'b' : 'a')
                + token.substring(target + 1);

        assertThat(jwtService.parseAndValidate(tampered)).isEmpty();
    }

    @Test
    void parseAndValidate_withTokenFromDifferentIssuer_returnsEmpty() {
        JwtProperties otherIssuerProperties = new JwtProperties(
                properties.secret(), 900, 2592000, "some-other-issuer");
        JwtService otherIssuerJwtService = new JwtService(otherIssuerProperties);

        String tokenFromOtherIssuer = otherIssuerJwtService.generateAccessToken(UUID.randomUUID(), Role.USER);

        assertThat(jwtService.parseAndValidate(tokenFromOtherIssuer)).isEmpty();
    }

    @Test
    void constructor_rejectsSecretShorterThan256Bits() {
        JwtProperties weakSecretProperties = new JwtProperties("too-short", 900, 2592000, "byzi-api-test");

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> new JwtService(weakSecretProperties));
    }
}