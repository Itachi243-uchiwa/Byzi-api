package com.byzi.api.service.maintenance;

import com.byzi.api.repository.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HAUT-02 : la decision testee ici est la fenetre de retention (7 jours), pas la requete SQL
 * elle-meme - couverte, elle, par RefreshTokenRepositoryTest contre une vraie base H2/Postgres.
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenCleanupJobTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    private RefreshTokenCleanupJob job;

    @BeforeEach
    void setUp() {
        job = new RefreshTokenCleanupJob(refreshTokenRepository);
    }

    @Test
    void purgeDelegatesToRepositoryWithASevenDayRetentionCutoff() {
        when(refreshTokenRepository.deleteExpiredOrRevokedBefore(any())).thenReturn(3);

        job.purge();

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(refreshTokenRepository).deleteExpiredOrRevokedBefore(cutoffCaptor.capture());
        Instant expectedCutoff = Instant.now().minus(7, ChronoUnit.DAYS);
        // Tolerance large : on verifie la fenetre de retention voulue, pas le temps
        // d'execution du test.
        assertThat(cutoffCaptor.getValue()).isCloseTo(expectedCutoff, within(5, ChronoUnit.SECONDS));
    }
}
