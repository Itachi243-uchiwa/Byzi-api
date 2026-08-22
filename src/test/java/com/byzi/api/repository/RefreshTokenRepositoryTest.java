package com.byzi.api.repository;

import com.byzi.api.domain.RefreshToken;
import com.byzi.api.domain.Role;
import com.byzi.api.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HAUT-02 : la purge nocturne (RefreshTokenCleanupJob) delegue entierement sa selection de
 * lignes a deleteExpiredOrRevokedBefore. Une requete JPQL fausse n'est detectable ni par un
 * test unitaire mock (qui ne verifie que l'appel, jamais le resultat), ni par une simple
 * lecture de code : il faut l'executer contre une vraie base pour savoir si elle supprime les
 * bonnes lignes - et seulement celles-la.
 */
@SpringBootTest
@ActiveProfiles("test")
class RefreshTokenRepositoryTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    private User persistUser() {
        return userRepository.save(User.builder()
                .id(UUID.randomUUID())
                .appleSub("sub-" + UUID.randomUUID())
                .role(Role.USER)
                .build());
    }

    private RefreshToken persistToken(User user, Instant expiresAt, boolean revoked, Instant revokedAt) {
        // Pas d'id explicite : RefreshToken porte @GeneratedValue(UUID), et un id deja
        // renseigne ferait passer save() par merge() plutot que persist() - donc un UPDATE
        // d'une ligne qui n'existe pas encore. C'est aussi ce que fait RefreshTokenService.issue().
        return refreshTokenRepository.save(RefreshToken.builder()
                .user(user)
                .tokenHash(UUID.randomUUID().toString())
                .expiresAt(expiresAt)
                .revoked(revoked)
                .revokedAt(revokedAt)
                .build());
    }

    @Test
    void purgeRemovesExpiredTokensPastRetentionButKeepsRecentlyExpiredOnes() {
        User user = persistUser();
        Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);

        RefreshToken longExpired = persistToken(user, cutoff.minus(1, ChronoUnit.DAYS), false, null);
        RefreshToken recentlyExpired = persistToken(user, cutoff.plus(1, ChronoUnit.DAYS), false, null);

        int removed = refreshTokenRepository.deleteExpiredOrRevokedBefore(cutoff);

        assertThat(removed).isEqualTo(1);
        assertThat(refreshTokenRepository.findById(longExpired.getId())).isEmpty();
        // Encore dans la fenetre de retention : la purge doit l'epargner.
        assertThat(refreshTokenRepository.findById(recentlyExpired.getId())).isPresent();
    }

    @Test
    void purgeRemovesRevokedTokensPastRetentionButKeepsRecentlyRevokedAndActiveOnes() {
        User user = persistUser();
        Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);
        // TTL encore loin dans le futur : seule la revocation doit rendre ces lignes eligibles,
        // pas leur expiration naturelle.
        Instant farFutureExpiry = Instant.now().plus(20, ChronoUnit.DAYS);

        RefreshToken oldRevoked = persistToken(user, farFutureExpiry, true, cutoff.minus(3, ChronoUnit.DAYS));
        RefreshToken recentlyRevoked = persistToken(user, farFutureExpiry, true, cutoff.plus(3, ChronoUnit.DAYS));
        RefreshToken active = persistToken(user, farFutureExpiry, false, null);

        int removed = refreshTokenRepository.deleteExpiredOrRevokedBefore(cutoff);

        assertThat(removed).isEqualTo(1);
        assertThat(refreshTokenRepository.findById(oldRevoked.getId())).isEmpty();
        assertThat(refreshTokenRepository.findById(recentlyRevoked.getId())).isPresent();
        // Un token actif ne doit jamais etre purge, quelle que soit son anciennete de creation.
        assertThat(refreshTokenRepository.findById(active.getId())).isPresent();
    }

    @Test
    void purgeFallsBackToCreatedAtForRevokedTokensWithoutRevokedAt() {
        User user = persistUser();
        Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);
        Instant farFutureExpiry = Instant.now().plus(20, ChronoUnit.DAYS);

        // Reproduit une ligne revoquee avant l'introduction de revoked_at (V4, colonne
        // nullable) : sans repli sur created_at, elle ne serait plus jamais eligible a la purge.
        // Pas d'id explicite, pour la meme raison que dans persistToken : @GeneratedValue(UUID)
        // s'en charge, et un id deja renseigne ferait passer save() par merge().
        RefreshToken legacyRevoked = refreshTokenRepository.save(RefreshToken.builder()
                .user(user).tokenHash(UUID.randomUUID().toString())
                .expiresAt(farFutureExpiry).revoked(true).revokedAt(null)
                .createdAt(cutoff.minus(1, ChronoUnit.DAYS)).build());

        int removed = refreshTokenRepository.deleteExpiredOrRevokedBefore(cutoff);

        assertThat(removed).isEqualTo(1);
        assertThat(refreshTokenRepository.findById(legacyRevoked.getId())).isEmpty();
    }
}
