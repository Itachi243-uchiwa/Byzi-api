package com.byzi.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

/**
 * Tache de la to-do list hebdomadaire (backlog app 0ter T9).
 * <p>
 * Meme contrat de synchronisation que {@link AppBlockRule} : id genere par le client, upsert
 * idempotent arbitre par le last-write-wins, delta par updatedAt, suppression logique.
 */
@Entity
@Table(name = "todo_tasks")
@SuperBuilder
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true, exclude = {"user"})
public class TodoTask extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "notes", length = 2000)
    private String notes;

    /**
     * Premier jour de la semaine de rattachement, au format ISO "AAAA-MM-JJ".
     * <p>
     * Le serveur ne l'interprete JAMAIS : c'est l'app qui le calcule selon la locale de
     * l'appareil (lundi en FR/BE, dimanche aux US). Le stocker en chaine plutot qu'en date
     * evite d'avoir a choisir un fuseau cote serveur - c'est une cle de regroupement, pas un
     * instant. Meme raisonnement que scheduleStart/scheduleEnd sur AppBlockRule.
     */
    @Column(name = "week_key", nullable = false, length = 10)
    private String weekKey;

    /** Jour d'echeance affiche, format ISO "AAAA-MM-JJ". Nullable : une tache peut ne pas en avoir. */
    @Column(name = "due_date", length = 10)
    private String dueDate;

    @Column(name = "is_done", nullable = false)
    @Builder.Default
    private boolean isDone = false;

    /** Instant de completion, pour l'historique et le calcul de serie cote app. */
    @Column(name = "done_at")
    private Instant doneAt;

    /** Tombstone de synchronisation, meme role que sur {@link AppBlockRule}. */
    @Column(name = "deleted_at")
    private Instant deletedAt;
}
