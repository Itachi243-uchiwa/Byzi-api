package com.buzi.api.security.jwt;

import com.buzi.api.domain.RefreshToken;
import com.buzi.api.domain.Role;
import com.buzi.api.domain.User;
import com.buzi.api.exception.InvalidRefreshTokenException;
import com.buzi.api.repository.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cycle de vie des refresh tokens : emission, rotation, revocation.
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    private final JwtProperties properties = new JwtProperties(
            "unit-test-secret-key-at-least-32-bytes-long", 900, 2592000, "byzi-api-test");

    private RefreshTokenService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new RefreshTokenService(refreshTokenRepository, properties);
        user = User.builder().id(UUID.randomUUID()).appleSub("sub").role(Role.USER).build();
    }

    @Test
    void issuedTokenIsNeverStoredInClear() {
        when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String raw = service.issue(user);

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(saved.capture());
        // Si la base fuitait, un token stocke en clair serait directement rejouable.
        assertThat(saved.getValue().getTokenHash()).isNotEqualTo(raw);
        assertThat(saved.getValue().getTokenHash()).hasSize(64); // SHA-256 en hexadecimal
    }

    @Test
    void issuedTokensAreUnique() {
        when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.issue(user)).isNotEqualTo(service.issue(user));
    }

    @Test
    void refreshTokenUsesRefreshTtlNotAccessTtl() {
        when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.issue(user);

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(saved.capture());
        // 30 jours, pas les 15 minutes de l'access token : se tromper de TTL deconnecterait
        // les utilisateurs toutes les 15 minutes.
        assertThat(saved.getValue().getExpiresAt())
                .isAfter(Instant.now().plus(29, ChronoUnit.DAYS));
    }

    @Test
    void rotateIssuesNewTokenAndRevokesTheOldOne() {
        String raw = "un-refresh-token";
        RefreshToken stored = RefreshToken.builder()
                .id(UUID.randomUUID()).user(user).tokenHash(hashOf(raw))
                .expiresAt(Instant.now().plus(10, ChronoUnit.DAYS)).revoked(false).build();
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(stored));
        when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RefreshTokenService.RotationResult result = service.rotate(raw);

        assertThat(stored.isRevoked()).isTrue();
        assertThat(result.newToken()).isNotEqualTo(raw);
        assertThat(result.user()).isEqualTo(user);
    }

    @Test
    void rotateRejectsUnknownToken() {
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotate("inconnu"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void rotateRejectsExpiredToken() {
        RefreshToken expired = RefreshToken.builder()
                .id(UUID.randomUUID()).user(user).tokenHash("h")
                .expiresAt(Instant.now().minus(1, ChronoUnit.DAYS)).revoked(false).build();
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.rotate("perime"))
                .isInstanceOf(InvalidRefreshTokenException.class);
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void replayedRevokedTokenTriggersDefensiveRevocationOfEveryToken() {
        // Un token deja revoque qui revient signale un vol probable : on coupe tout, plutot
        // que de se contenter de refuser cette requete-la.
        RefreshToken revoked = RefreshToken.builder()
                .id(UUID.randomUUID()).user(user).tokenHash("h")
                .expiresAt(Instant.now().plus(10, ChronoUnit.DAYS)).revoked(true).build();
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(revoked));

        assertThatThrownBy(() -> service.rotate("vole"))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokenRepository).revokeAllActiveForUser(user.getId());
    }

    @Test
    void revokeAllForUserDelegatesToRepository() {
        UUID userId = UUID.randomUUID();

        service.revokeAllForUser(userId);

        verify(refreshTokenRepository).revokeAllActiveForUser(userId);
    }

    /** Reproduit le hachage interne pour construire un jeton stocke coherent. */
    private String hashOf(String raw) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
