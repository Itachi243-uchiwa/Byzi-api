package com.byzi.api.repository;

import com.byzi.api.domain.AppBlockRule;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Voir {@link FocusSessionRepository} pour la distinction entre les methodes de vue client
 * ({@code ...DeletedAtIsNull}) et le delta de synchronisation, qui inclut les tombstones.
 */
public interface AppBlockRuleRepository extends JpaRepository<AppBlockRule, UUID> {

    /** Inclut les lignes supprimees : reserve a l'upsert (regle de resurrection). */
    Optional<AppBlockRule> findByIdAndUser_Id(UUID id, UUID userId);

    Optional<AppBlockRule> findByIdAndUser_IdAndDeletedAtIsNull(UUID id, UUID userId);

    Page<AppBlockRule> findAllByUser_IdAndDeletedAtIsNull(UUID userId, Pageable pageable);

    /** Delta de synchronisation (MANQUE-01), tombstones compris. */
    Page<AppBlockRule> findAllByUser_IdAndUpdatedAtGreaterThanEqual(UUID userId, Instant updatedSince, Pageable pageable);

    /** Suppression physique, hors synchronisation. Voir {@link FocusSessionRepository#deleteByIdAndUser_Id}. */
    long deleteByIdAndUser_Id(UUID id, UUID userId);
}
