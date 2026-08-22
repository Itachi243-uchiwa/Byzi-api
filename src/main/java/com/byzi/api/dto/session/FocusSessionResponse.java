package com.byzi.api.dto.session;

import com.byzi.api.domain.SessionMode;

import java.time.Instant;
import java.util.UUID;

/**
 * @param deletedAt tombstone : non nul uniquement dans les reponses du delta de
 *                  synchronisation (parametre updatedSince). Le client qui le recoit doit
 *                  supprimer sa copie locale. Toujours nul dans les listes ordinaires et
 *                  les GET unitaires, qui excluent les ressources supprimees.
 */
public record FocusSessionResponse(
        UUID id,
        Instant startedAt,
        Instant endedAt,
        Integer plannedDurationSeconds,
        SessionMode mode,
        boolean completed,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt
) {
}
