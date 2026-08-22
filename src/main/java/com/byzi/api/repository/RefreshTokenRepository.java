package com.byzi.api.repository;

import com.byzi.api.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Revoque les tokens DU PROPRIETAIRE (r.user.id), pas le token dont l'id vaut userId :
     * la version precedente comparait r.id a un userId et ne revoquait donc jamais rien,
     * laissant des refresh tokens actifs apres une deconnexion globale.
     * <p>
     * Renseigne aussi revoked_at : sans cette date, la purge (deleteExpiredOrRevokedBefore)
     * ne saurait pas quand ces tokens ont ete revoques et retomberait sur leur date
     * d'emission, potentiellement bien plus ancienne, pour decider de leur suppression.
     */
    @Modifying
    @Transactional
    @Query("update RefreshToken r set r.revoked = true, r.revokedAt = :now "
            + "where r.user.id = :userId and r.revoked = false")
    int revokeAllActiveForUser(@Param("userId") UUID userId, @Param("now") Instant now);

    /**
     * Purge des lignes qui ne servent plus a rien : tokens dont le TTL naturel est depasse, ou
     * deja revoques depuis plus de la fenetre de retention. Sans cette purge, chaque rotation
     * (RefreshTokenService.rotate) laisse une ligne morte en base indefiniment (HAUT-02) : avec
     * un access token de 15 minutes, un utilisateur actif declenche ~96 rotations par jour, soit
     * environ 1,44 million de lignes cumulatives par mois pour 500 utilisateurs.
     * <p>
     * coalesce(r.revokedAt, r.createdAt) couvre les lignes revoquees avant l'introduction de
     * revoked_at (colonne nullable, V4) : sans repli sur created_at, ces lignes anciennes ne
     * seraient plus jamais eligibles a la purge et resteraient immortelles.
     *
     * @return le nombre de lignes supprimees, pour journalisation par RefreshTokenCleanupJob.
     */
    @Modifying
    @Transactional
    @Query("delete from RefreshToken r where r.expiresAt < :cutoff "
            + "or (r.revoked = true and coalesce(r.revokedAt, r.createdAt) < :cutoff)")
    int deleteExpiredOrRevokedBefore(@Param("cutoff") Instant cutoff);
}
