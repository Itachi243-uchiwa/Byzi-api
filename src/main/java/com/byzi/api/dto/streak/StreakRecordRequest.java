package com.byzi.api.dto.streak;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;
import java.time.LocalDate;

public record StreakRecordRequest(
        @NotNull
        LocalDate date,

        boolean goalReached,

        @PositiveOrZero
        Integer focusMinutes,

        Instant clientUpdatedAt
) {
}
