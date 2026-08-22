package com.buzi.api.dto.session;

import com.buzi.api.domain.SessionMode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

public record FocusSessionRequest(
        @NotNull
        Instant startedAt,

        Instant endedAt,
        @NotNull
        @Positive
        Integer plannedDurationSeconds,

        @NotNull
        SessionMode mode,

        boolean completed,

        Instant clientUpdatedAt
) {
}
