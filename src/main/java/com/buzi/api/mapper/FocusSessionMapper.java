package com.buzi.api.mapper;

import com.buzi.api.domain.FocusSession;
import com.buzi.api.domain.User;
import com.buzi.api.dto.session.FocusSessionRequest;
import com.buzi.api.dto.session.FocusSessionResponse;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class FocusSessionMapper {

    public FocusSession toNewEntity(UUID id, User owner, FocusSessionRequest request) {
        return FocusSession.builder()
                .id(id)
                .user(owner)
                .startedAt(request.startedAt())
                .endedAt(request.endedAt())
                .plannedDurationSeconds(request.plannedDurationSeconds())
                .mode(request.mode())
                .completed(request.completed())
                .build();
    }

    /**
     * Le proprietaire (user) n'est deliberement PAS reaffecte ici : une mise a jour ne doit
     * jamais pouvoir faire changer une session de proprietaire, meme si un futur DTO venait a
     * porter un userId.
     */
    public void applyUpdate(FocusSession entity, FocusSessionRequest request) {
        entity.setStartedAt(request.startedAt());
        entity.setEndedAt(request.endedAt());
        entity.setPlannedDurationSeconds(request.plannedDurationSeconds());
        entity.setMode(request.mode());
        entity.setCompleted(request.completed());
    }

    public FocusSessionResponse toResponse(FocusSession entity) {
        return new FocusSessionResponse(
                entity.getId(),
                entity.getStartedAt(),
                entity.getEndedAt(),
                entity.getPlannedDurationSeconds(),
                entity.getMode(),
                entity.isCompleted(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
