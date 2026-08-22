package com.byzi.api.security.jwt;

import com.byzi.api.domain.RefreshToken;
import com.byzi.api.domain.User;
import com.byzi.api.exception.InvalidRefreshTokenException;
import com.byzi.api.repository.RefreshTokenRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
    public RotationResult rotate(String presentedTawToken) {
        String presentedHash = hash(presentedTawToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(presentedHash)
                .orElseThrow(() -> new InvalidRefreshTokenException("Token invalide"));

        if (stored.isRevoked()) {
            log.warn("Refresh token deja revoque presente pour l'utilisateur {} - possible vol de token, "
            + "revocation preventive de tous les tokens actifs ", stored.getUser().getId());
            refreshTokenRepository.revokeAllActiveForUser(stored.getUser().getId());
            throw new InvalidRefreshTokenException("Refresh token revoque");
        }

        if (stored.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidRefreshTokenException("Refresh token expire");

        }

        stored.setRevoked(true);
        refreshTokenRepository.save(stored);

        String newToken = issue(stored.getUser());
        return new RotationResult(stored.getUser(), newToken);

    }

    @Transactional
    public void revokeAllForUser(UUID userId) {
        refreshTokenRepository.revokeAllActiveForUser(userId);
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
