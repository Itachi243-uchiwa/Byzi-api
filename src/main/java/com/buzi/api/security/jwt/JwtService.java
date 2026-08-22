package com.buzi.api.security.jwt;

import com.buzi.api.domain.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class JwtService {
    private static final String CLAIM_ROLE = "role";
    private final SecretKey signingKey;
    private final JwtProperties properties;

    public JwtService(JwtProperties properties) {
        this.properties = properties;

        byte[] secretBytes = properties.secret().getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            // Cas le plus frequent au demarrage : la variable d'environnement n'est pas
            // definie, et Spring transmet alors le placeholder litteral "${JWT_SECRET}".
            // Le detecter explicitement evite de laisser croire a un secret trop court alors
            // qu'il n'y a simplement aucun secret configure.
            String cause = properties.secret().startsWith("${")
                    ? "la variable d'environnement JWT_SECRET n'est pas definie (placeholder recu tel quel : "
                      + properties.secret() + ")"
                    : "il ne fait que " + secretBytes.length + " octets";
            throw new IllegalStateException(
                    "byzi.security.jwt.secret doit faire au moins 256 bits (32 octets) : " + cause
                    + ". Definissez JWT_SECRET, ou lancez le profil de demonstration qui fournit "
                    + "ses propres valeurs : ./mvnw spring-boot:run -Dspring-boot.run.profiles=demo"
            );
        }
        this.signingKey = Keys.hmacShaKeyFor(secretBytes);
    }

    public String generateAccessToken(UUID userId, Role role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(properties.issuer())
                .subject(userId.toString())
                .claim(CLAIM_ROLE, role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(properties.accessTokenTtlSeconds())))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();

    }

    public Optional<AccessTokenClaims> parseAndValidate(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(properties.issuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            UUID userId = UUID.fromString(claims.getSubject());
            Role role = Role.valueOf(claims.get(CLAIM_ROLE, String.class));
            return Optional.of(new AccessTokenClaims(userId, role));
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Access token invalide ou expire : {} ", e.getMessage() );
            return Optional.empty();
        }
    }
}
