package com.buzi.api.security.jwt;

import com.buzi.api.domain.Role;
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

        // Altere un caractere de la signature : doit etre rejete, pas "presque valide".
        String tampered = token.substring(0, token.length() - 1)
                + (token.charAt(token.length() - 1) == 'a' ? 'b' : 'a');

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