package com.byzi.api.service.admin;

import com.byzi.api.domain.SubscriptionStatus;
import com.byzi.api.dto.admin.DashboardKpi;
import com.byzi.api.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Story 09.4 - KPI du tableau de bord.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminDashboardServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AdminDashboardService service;

    private void givenCounts(long total, long active, long trial, long grace, long expired) {
        when(userRepository.count()).thenReturn(total);
        when(userRepository.countByCreatedAtAfter(any(Instant.class))).thenReturn(0L);
        when(userRepository.countBySubscriptionStatus(SubscriptionStatus.ACTIVE)).thenReturn(active);
        when(userRepository.countBySubscriptionStatus(SubscriptionStatus.TRIAL)).thenReturn(trial);
        when(userRepository.countBySubscriptionStatus(SubscriptionStatus.GRACE_PERIOD)).thenReturn(grace);
        when(userRepository.countBySubscriptionStatus(SubscriptionStatus.EXPIRED)).thenReturn(expired);
        when(userRepository.countByStatuses(any(Collection.class))).thenReturn(active + grace + expired);
    }

    @Test
    void computesConversionAndChurnOverPostTrialAccountsOnly() {
        // 60 payants (dont 10 en impaye) et 40 expires parmi 100 comptes sortis de l'essai.
        // Les 500 essais en cours ne doivent PAS diluer le taux : ils n'ont pas encore eu
        // l'occasion de convertir.
        givenCounts(600, 50, 500, 10, 40);

        DashboardKpi kpi = service.computeKpi();

        assertThat(kpi.conversionRate()).isEqualTo(60.0);
        assertThat(kpi.churnRate()).isEqualTo(40.0);
    }

    @Test
    void returnsZeroRatesWhenNobodyLeftTrialYet() {
        // Cas du lancement : sans ce garde-fou, la division donnerait NaN et l'affichage
        // casserait sur le tout premier ecran vu par l'equipe.
        givenCounts(12, 0, 12, 0, 0);
        when(userRepository.countByStatuses(any(Collection.class))).thenReturn(0L);

        DashboardKpi kpi = service.computeKpi();

        assertThat(kpi.conversionRate()).isZero();
        assertThat(kpi.churnRate()).isZero();
    }

    @Test
    void countsGracePeriodAsConvertedNotChurned() {
        // Un impaye a bien souscrit : c'est le prelevement qui a echoue, pas la conversion.
        givenCounts(10, 0, 0, 10, 0);

        DashboardKpi kpi = service.computeKpi();

        assertThat(kpi.conversionRate()).isEqualTo(100.0);
        assertThat(kpi.churnRate()).isZero();
    }

    @Test
    void exposesRawCounts() {
        givenCounts(100, 30, 40, 5, 25);
        when(userRepository.countByCreatedAtAfter(any(Instant.class))).thenReturn(17L);

        DashboardKpi kpi = service.computeKpi();

        assertThat(kpi.totalUsers()).isEqualTo(100);
        assertThat(kpi.newUsersLast30Days()).isEqualTo(17);
        assertThat(kpi.activeSubscribers()).isEqualTo(30);
        assertThat(kpi.trialUsers()).isEqualTo(40);
        assertThat(kpi.gracePeriodUsers()).isEqualTo(5);
        assertThat(kpi.expiredUsers()).isEqualTo(25);
    }

    @Test
    void ratesAreRoundedToOneDecimal() {
        // 1 converti sur 3 : un affichage brut donnerait 33.33333333333333 %.
        givenCounts(3, 1, 0, 0, 2);

        assertThat(service.computeKpi().conversionRate()).isEqualTo(33.3);
    }
}
