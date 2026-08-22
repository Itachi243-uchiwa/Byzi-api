package com.byzi.api.dto.streak;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record StreakRecordResponse(
        UUID id,
        LocalDate date,
        boolean goalReached,
        Integer focusMinutes,
        Instant createdAt,
        Instant updatedAt
) {
}
