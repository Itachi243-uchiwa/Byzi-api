package com.byzi.api.repository;

import com.byzi.api.domain.StreakRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StreakRecordRepository extends JpaRepository<StreakRecord, UUID> {
    Optional<StreakRecord> findByIdAndUser_Id(UUID id, UUID userId);
    Optional<StreakRecord> findByUser_IdAndDay(UUID userId, LocalDate day);
    Page<StreakRecord> findAllByUser_IdOrderByDayDesc(UUID userId, Pageable pageable);
    long deleteByIdAndUser_Id(UUID id, UUID userId);

    /** Back-office (story 09.3) : jours avec objectif atteint, du plus recent au plus ancien. */
    List<StreakRecord> findTop60ByUser_IdAndGoalReachedTrueOrderByDayDesc(UUID userId);
}
