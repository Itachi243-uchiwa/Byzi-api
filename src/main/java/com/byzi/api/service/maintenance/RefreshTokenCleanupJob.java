package com.byzi.api.service.maintenance;

import com.byzi.api.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Purge nocturne des refresh tokens expires ou revoques (HAUT-02).
 * <p>
 * Chaque rotation (RefreshTokenService.rotate) marque l'ancien token revoque et en insere un
 * nouveau, mais ne supprime jamais rien : sans ce job, un utilisateur actif (access token de
 * 15 minutes, donc ~96 rotations par jour) laisse une ligne morte en base a chaque
 * rafraichissement. Sur 500 utilisateurs, cela represente environ 1,44 million de lignes
 * cumulatives par mois : le seul poste capable de saturer le disque du serveur, et conserver
 * indefiniment des tokens revoques est aussi contraire a la minimisation des donnees (RGPD).
 * <p>
 * Retention de 7 jours apres expiration ou revocation : assez pour enqueter sur une
 * reutilisation suspecte de token (cf. RefreshTokenService.rotate), pas assez pour que la
 * table derive.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenCleanupJob {

    private static final int RETENTION_DAYS = 7;

    private final RefreshTokenRepository refreshTokenRepository;

    /**
     * Cron configurable via byzi.maintenance.refresh-token-purge-cron, avec une valeur par
     * defaut (3h30 du matin, heure creuse) : le placeholder evite de toucher aux fichiers YAML
     * partages avec d'autres correctifs en cours, tout en laissant l'exploitation ajuster
     * l'horaire sans recompiler.
     */
    @Scheduled(cron = "${byzi.maintenance.refresh-token-purge-cron:0 30 3 * * *}")
    @Transactional
    public void purge() {
        Instant cutoff = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS);
        int removed = refreshTokenRepository.deleteExpiredOrRevokedBefore(cutoff);
        log.info("Purge des refresh tokens : {} lignes supprimees", removed);
    }
}
