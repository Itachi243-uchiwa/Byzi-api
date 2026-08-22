package com.buzi.api.repository;

import com.buzi.api.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Revoque les tokens DU PROPRIETAIRE (r.user.id), pas le token dont l'id vaut userId :
     * la version precedente comparait r.id a un userId et ne revoquait donc jamais rien,
     * laissant des refresh tokens actifs apres une deconnexion globale.
     */
    @Modifying
    @Query("update RefreshToken r set r.revoked = true where r.user.id = :userId and r.revoked = false")
    int revokeAllActiveForUser(@Param("userId") UUID userId);
}
