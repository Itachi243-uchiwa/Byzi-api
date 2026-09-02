package com.byzi.api.dto.objective;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Objectif de la semaine. {@code weekKey} est une CLE DE JOUR ("AAAA-MM-JJ"), jamais convertie
 * en date cote serveur - meme raisonnement que sur TodoTaskRequest.
 *
 * <p>{@code linkedTaskIds} est borne en nombre : un objectif reste une poignee de taches. La
 * borne protege la colonne texte qui les stocke (OWASP API4:2023).
 */
public record WeeklyObjectiveRequest(

        @NotBlank
        @Size(max = 200)
        String title,

        @NotBlank
        @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "weekKey doit etre au format AAAA-MM-JJ")
        String weekKey,

        @Size(max = 50, message = "un objectif ne peut pas lier plus de 50 taches")
        Set<UUID> linkedTaskIds,

        boolean achieved,

        /** Instant d'atteinte, fourni par le client. Ignore si {@code achieved} est faux. */
        Instant achievedAt,

        Instant clientUpdatedAt
) {
}
