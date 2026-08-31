package com.byzi.api.dto.settings;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * @param dailyGoalMinutes objectif de focus réel quotidien, en minutes (5..240, aligné sur
 *                         le clamp de {@code FocusGoal} côté iOS).
 * @param clientUpdatedAt  horloge de l'appareil au moment du changement. Peut être nul
 *                         (anciens clients / tests) : la valeur entrante est alors appliquée
 *                         d'office, cf. {@code LastWriteStrategy}.
 */
public record UserSettingsRequest(
        @NotNull
        @Min(5)
        @Max(240)
        Integer dailyGoalMinutes,

        Instant clientUpdatedAt
) {
}
