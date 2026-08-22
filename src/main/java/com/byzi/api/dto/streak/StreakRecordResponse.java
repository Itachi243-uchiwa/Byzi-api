package com.byzi.api.dto.streak;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * @param deletedAt tombstone : non nul uniquement dans les reponses du delta de
 *                  synchronisation (parametre updatedSince). Le client qui le recoit doit
 *                  supprimer sa copie locale.
 */
public record StreakRecordResponse(
        UUID id,
        LocalDate date,
        boolean goalReached,
        Integer focusMinutes,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
}
