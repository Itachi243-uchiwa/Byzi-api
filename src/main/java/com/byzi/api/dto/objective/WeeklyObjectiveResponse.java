package com.byzi.api.dto.objective;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * @param linkedTaskIds ids de taches, toujours tries (normalises par UuidSetConverter) pour
 *                      qu'une meme selection produise toujours la meme reponse.
 * @param deletedAt     tombstone : non nul uniquement dans le delta de synchronisation.
 */
public record WeeklyObjectiveResponse(
        UUID id,
        String title,
        String weekKey,
        Set<UUID> linkedTaskIds,
        boolean achieved,
        Instant achievedAt,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
}
