package com.byzi.api.repository;

import com.byzi.api.domain.TodoTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Voir {@link AppBlockRuleRepository} pour la distinction entre les methodes de vue client
 * ({@code ...DeletedAtIsNull}) et le delta de synchronisation, qui inclut les tombstones.
 */
public interface TodoTaskRepository extends JpaRepository<TodoTask, UUID> {

    /** Inclut les lignes supprimees : reserve a l'upsert (regle de resurrection). */
    Optional<TodoTask> findByIdAndUser_Id(UUID id, UUID userId);

    Optional<TodoTask> findByIdAndUser_IdAndDeletedAtIsNull(UUID id, UUID userId);

    Page<TodoTask> findAllByUser_IdAndDeletedAtIsNull(UUID userId, Pageable pageable);

    /** Delta de synchronisation, tombstones compris. */
    Page<TodoTask> findAllByUser_IdAndUpdatedAtGreaterThanEqual(UUID userId, Instant updatedSince, Pageable pageable);
}
