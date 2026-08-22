package com.byzi.api.security.jwt;

import com.byzi.api.domain.RefreshToken;
import com.byzi.api.domain.User;
import com.byzi.api.exception.InvalidRefreshTokenException;
import com.byzi.api.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {
    private static final int TOKEN_BYTE_LENGTH = 64;

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProperties jwtProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public String issue(User user) {
        String rawToken = generateRawToken();
        RefreshToken entity = RefreshToken.builder()
                .user(user)
                .tokenHash(hash(rawToken))
                .expiresAt(Instant.now().plus(jwtProperties.refreshTokenTtlSeconds(), ChronoUnit.SECONDS))
                .build();
        refreshTokenRepository.save(entity);
        return rawToken;
    }

    @Transactional
    public RotationResult rotate(String presentedRawToken) {
        String presentedHash = hash(presentedRawToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(presentedHash)
                .orElseThrow(() -> new InvalidRefreshTokenException("Token invalide"));

        if (stored.isRevoked()) {
            log.warn("Refresh token deja revoque presente pour l'utilisateur {} - possible vol de token, "
            + "revocation preventive de tous les tokens actifs ", stored.getUser().getId());
            refreshTokenRepository.revokeAllActiveForUser(stored.getUser().getId(), Instant.now());
            throw new InvalidRefreshTokenException("Refresh token revoque");
        }

        if (stored.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidRefreshTokenException("Refresh token expire");

        }

        // revokedAt distinct de createdAt : c'est cette date que la purge nocturne
        // (RefreshTokenCleanupJob) utilise pour ne garder un token revoque que 7 jours, au lieu
        // d'attendre son TTL de 30 jours.
        stored.setRevoked(true);
        stored.setRevokedAt(Instant.now());
        refreshTokenRepository.save(stored);

        String newToken = issue(stored.getUser());
        return new RotationResult(stored.getUser(), newToken);

    }

    @Transactional
    public void revokeAllForUser(UUID userId) {
        refreshTokenRepository.revokeAllActiveForUser(userId, Instant.now());
    }

    private String generateRawToken() {

        byte[] bytes = new byte[TOKEN_BYTE_LENGTH];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible sur cette JVM", e);
        }
    }

    public record RotationResult(User user, String newToken) {
    }
}
