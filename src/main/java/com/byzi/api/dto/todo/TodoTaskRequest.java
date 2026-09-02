package com.byzi.api.dto.todo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Tache de la to-do list hebdomadaire.
 *
 * <p>weekKey / dueDate sont des CLES DE JOUR ("AAAA-MM-JJ"), pas des instants : le serveur ne
 * les convertit jamais en date, ce qui l'obligerait a choisir un fuseau. Il valide juste la
 * forme, pour qu'une valeur incoherente ne se propage pas silencieusement aux autres appareils.
 */
public record TodoTaskRequest(

        @NotBlank
        @Size(max = 200)
        String title,

        @Size(max = 2000)
        String notes,

        @NotBlank
        @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "weekKey doit etre au format AAAA-MM-JJ")
        String weekKey,

        @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "dueDate doit etre au format AAAA-MM-JJ")
        String dueDate,

        boolean done,

        /** Instant de completion, fourni par le client. Ignore si {@code done} est faux. */
        Instant doneAt,

        Instant clientUpdatedAt
) {
}
