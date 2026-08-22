package com.buzi.api.service;

import com.buzi.api.domain.StreakRecord;
import com.buzi.api.domain.User;
import com.buzi.api.dto.streak.StreakRecordRequest;
import com.buzi.api.dto.streak.StreakRecordResponse;
import com.buzi.api.exception.ResourceNotFoundException;
import com.buzi.api.mapper.StreakRecordMapper;
import com.buzi.api.repository.StreakRecordRepository;
import com.buzi.api.repository.UserRepository;
import com.buzi.api.service.sync.ConflictResolutionStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StreakRecordService {

    private final StreakRecordRepository streakRecordRepository;
    private final UserRepository userRepository;
    private final StreakRecordMapper mapper;
    private final ConflictResolutionStrategy conflictResolutionStrategy;

    @Transactional
    public StreakRecordResponse upsert(UUID id, UUID userId, StreakRecordRequest recordRequest) {
        Optional<StreakRecord> existingById = streakRecordRepository.findByIdAndUser_Id(id, userId);
        Optional<StreakRecord> existngByDay = streakRecordRepository.findByUser_IdAndDay(userId, recordRequest.date());

        StreakRecord canonical = existingById.or(()-> existngByDay).orElse(null);
        var storedUpdatedAt = canonical != null ? canonical.getUpdatedAt() : null;
        boolean shouldApply = conflictResolutionStrategy.shouldApplyIncoming(recordRequest.clientUpdatedAt(), storedUpdatedAt);

        if (!shouldApply) {
            assert canonical != null;
            return mapper.toResponse(canonical);
        }

        StreakRecord entity;
        if (canonical != null) {
            mapper.applyUpdate(canonical, recordRequest);
            entity = canonical;
        } else {
            User owner = userRepository.getReferenceById(userId);
            entity = mapper.toNewEntity(id, owner, recordRequest);

        }
        return mapper.toResponse(streakRecordRepository.save(entity));
    }

    @Transactional(readOnly = true)
    public StreakRecordResponse get(UUID id, UUID userId) {
        return streakRecordRepository.findByIdAndUser_Id(id, userId)
                .map(mapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Streak record not found"));
    }

    @Transactional(readOnly = true)
    public Page<StreakRecordResponse> list(UUID userId, Pageable pageable) {
        return streakRecordRepository.findAllByUser_IdOrderByDayDesc(userId, pageable)
                .map(mapper::toResponse);

    }

    @Transactional
    public void delete(UUID id, UUID userId) {
        long deleted = streakRecordRepository.deleteByIdAndUser_Id(id, userId);
        if (deleted == 0) {
            throw new ResourceNotFoundException("Streak record not found");
        }
    }


}
