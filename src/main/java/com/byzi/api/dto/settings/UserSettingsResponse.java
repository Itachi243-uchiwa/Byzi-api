package com.byzi.api.dto.settings;

import java.time.Instant;

/**
 * Réglages de l'utilisateur courant. {@code updatedAt} est l'horloge serveur : l'app iOS la
 * compare à sa dernière modification locale pour le last-write-wins (cf. FocusGoalSync).
 */
public record UserSettingsResponse(
        Integer dailyGoalMinutes,
        Instant createdAt,
        Instant updatedAt
) {
}
