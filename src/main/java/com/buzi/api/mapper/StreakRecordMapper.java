package com.buzi.api.mapper;

import com.buzi.api.domain.StreakRecord;
import com.buzi.api.domain.User;
import com.buzi.api.dto.streak.StreakRecordRequest;
import com.buzi.api.dto.streak.StreakRecordResponse;
import org.springframework.stereotype.Component;

import java.util.UUID;
@Component
public class StreakRecordMapper {

    public StreakRecord toNewEntity(UUID id, User owner, StreakRecordRequest recordRequest) {
        return StreakRecord.builder()
                .id(id)
                .user(owner)
                .day(recordRequest.date())
                .goalReached(recordRequest.goalReached())
                .focusMinutes(recordRequest.focusMinutes())
                .build();
    }

    public void applyUpdate(StreakRecord entity, StreakRecordRequest recordRequest) {
        entity.setGoalReached(recordRequest.goalReached());
        entity.setFocusMinutes(recordRequest.focusMinutes());
    }

    public StreakRecordResponse toResponse(StreakRecord entity) {
        return new StreakRecordResponse(
                entity.getId(),
                entity.getDay(),
                entity.isGoalReached(),
                entity.getFocusMinutes(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
