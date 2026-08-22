package com.byzi.api.dto.blockrule;

import java.time.Instant;
import java.util.UUID;

public record AppBlockRuleResponse(
        UUID id,
        String selectionData,
        Integer dailyLimitMinutes,
        String scheduleStart,
        String scheduleEnd,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
}