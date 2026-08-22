package com.byzi.api.repository;

import com.byzi.api.domain.StreakRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Voir {@link FocusSessionRepository} pour la distinction entre les methodes de vue client
 * ({@code ...DeletedAtIsNull}) et le delta de synchronisation, qui inclut les tombstones.
 */
public interface StreakRecordRepository extends JpaRepository<StreakRecord, UUID> {

    /** Inclut les lignes supprimees : reserve a l'upsert (regle de resurrection). */
    Optional<StreakRecord> findByIdAndUser_Id(UUID id, UUID userId);

    Optional<StreakRecord> findByIdAndUser_IdAndDeletedAtIsNull(UUID id, UUID userId);

    /**
     * Inclut DELIBEREMENT les lignes supprimees. La contrainte uk_streak_user_day interdit
     * deux enregistrements pour le meme (user_id, day) : un streak supprime occupe toujours
     * son creneau, et le retrouver ici est ce qui permet de le reanimer plutot que de tenter
     * une insertion qui violerait la contrainte.
     */
    Optional<StreakRecord> findByUser_IdAndDay(UUID userId, LocalDate day);

    Page<StreakRecord> findAllByUser_IdAndDeletedAtIsNullOrderByDayDesc(UUID userId, Pageable pageable);

    /** Delta de synchronisation (MANQUE-01), tombstones compris. */
    Page<StreakRecord> findAllByUser_IdAndUpdatedAtGreaterThanEqual(UUID userId, Instant updatedSince, Pageable pageable);

    /** Suppression physique, hors synchronisation. Voir {@link FocusSessionRepository#deleteByIdAndUser_Id}. */
    long deleteByIdAndUser_Id(UUID id, UUID userId);

    /**
     * Back-office (story 09.3) : jours avec objectif atteint, du plus recent au plus ancien.
     * Exclut les tombstones - un streak supprime par l'utilisateur ne doit pas continuer a
     * alimenter le calcul de serie affiche au support.
     */
    List<StreakRecord> findTop60ByUser_IdAndGoalReachedTrueAndDeletedAtIsNullOrderByDayDesc(UUID userId);
}
