package com.byzi.api.service.admin;

import com.byzi.api.domain.SubscriptionStatus;
import com.byzi.api.dto.admin.DashboardKpi;
import com.byzi.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Calcul des KPI du tableau de bord (story 09.4).
 * <p>
 * Tout passe par des "count" cote base : charger les comptes en memoire pour les compter en
 * Java fonctionnerait sur un jeu de test et s'effondrerait des les premiers milliers
 * d'utilisateurs.
 */
@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    private static final int NEW_USERS_WINDOW_DAYS = 30;

    /**
     * Comptes ayant termine leur essai, dans un sens ou dans l'autre. GRACE_PERIOD compte
     * comme converti : l'utilisateur a bien souscrit, c'est le prelevement qui a echoue.
     */
    private static final List<SubscriptionStatus> POST_TRIAL_STATUSES = List.of(
            SubscriptionStatus.ACTIVE, SubscriptionStatus.GRACE_PERIOD, SubscriptionStatus.EXPIRED);

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public DashboardKpi computeKpi() {
        long total = userRepository.count();
        long newUsers = userRepository.countByCreatedAtAfter(
                Instant.now().minus(NEW_USERS_WINDOW_DAYS, ChronoUnit.DAYS));

        long active = userRepository.countBySubscriptionStatus(SubscriptionStatus.ACTIVE);
        long trial = userRepository.countBySubscriptionStatus(SubscriptionStatus.TRIAL);
        long grace = userRepository.countBySubscriptionStatus(SubscriptionStatus.GRACE_PERIOD);
        long expired = userRepository.countBySubscriptionStatus(SubscriptionStatus.EXPIRED);

        // Denominateur : uniquement les comptes qui ont eu l'occasion de convertir. Rapporter
        // les conversions au total des comptes ferait baisser le taux a chaque nouvelle
        // inscription, ce qui en ferait un indicateur trompeur en periode de croissance.
        long postTrial = userRepository.countByStatuses(POST_TRIAL_STATUSES);

        return new DashboardKpi(
                total,
                newUsers,
                active,
                trial,
                grace,
                expired,
                percentage(active + grace, postTrial),
                percentage(expired, postTrial));
    }

    /** Renvoie 0 plutot que NaN quand aucun compte n'est encore sorti de l'essai. */
    private double percentage(long part, long whole) {
        if (whole <= 0) {
            return 0d;
        }
        return Math.round((double) part / whole * 1000d) / 10d;
    }
}
