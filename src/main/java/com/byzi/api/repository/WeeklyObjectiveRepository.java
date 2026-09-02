package com.byzi.api.repository;

import com.byzi.api.domain.WeeklyObjective;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Voir {@link AppBlockRuleRepository} pour la distinction vue client / delta de sync. */
public interface WeeklyObjectiveRepository extends JpaRepository<WeeklyObjective, UUID> {

    /** Inclut les lignes supprimees : reserve a l'upsert (regle de resurrection). */
    Optional<WeeklyObjective> findByIdAndUser_Id(UUID id, UUID userId);

    Optional<WeeklyObjective> findByIdAndUser_IdAndDeletedAtIsNull(UUID id, UUID userId);

    Page<WeeklyObjective> findAllByUser_IdAndDeletedAtIsNull(UUID userId, Pageable pageable);

    /** Delta de synchronisation, tombstones compris. */
    Page<WeeklyObjective> findAllByUser_IdAndUpdatedAtGreaterThanEqual(UUID userId, Instant updatedSince, Pageable pageable);
}
