package com.byzi.api.dto.todo;

import java.time.Instant;
import java.util.UUID;

/**
 * @param weekKey   premier jour de la semaine de rattachement ("AAAA-MM-JJ"), calcule par
 *                  l'app selon la locale de l'appareil.
 * @param deletedAt tombstone : non nul uniquement dans les reponses du delta de
 *                  synchronisation (parametre updatedSince).
 */
public record TodoTaskResponse(
        UUID id,
        String title,
        String notes,
        String weekKey,
        String dueDate,
        boolean done,
        Instant doneAt,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
}
