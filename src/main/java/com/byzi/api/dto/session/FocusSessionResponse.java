package com.byzi.api.dto.session;

import com.byzi.api.domain.SessionMode;

import java.time.Instant;
import java.util.UUID;

public record FocusSessionResponse(
        UUID id,
        Instant startedAt,
        Instant endedAt,
        Integer plannedDurationSeconds,
        SessionMode mode,
        boolean completed,
        Instant createdAt,
        Instant updatedAt
) {
}
