package com.byzi.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import java.util.Set;
import java.util.UUID;

/**
 * Objectif de la semaine (backlog app 0ter T10), rattache aux taches de la to-do list.
 * Meme contrat de synchronisation que {@link TodoTask} et {@link AppBlockRule}.
 */
@Entity
@Table(name = "weekly_objectives")
@SuperBuilder
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true, exclude = {"user"})
public class WeeklyObjective extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /** Premier jour de la semaine, "AAAA-MM-JJ". Meme cle que {@link TodoTask#getWeekKey()}. */
    @Column(name = "week_key", nullable = false, length = 10)
    private String weekKey;

    /**
     * Taches de la to-do list qui composent cet objectif. Volontairement SANS cle etrangere :
     * les deux ressources se synchronisent independamment et peuvent arriver dans le desordre,
     * une contrainte ferait echouer l'ecriture d'un objectif dont une tache n'est pas encore
     * arrivee. L'app ignore les ids sans correspondance.
     */
    @Convert(converter = UuidSetConverter.class)
    @Column(name = "linked_task_ids", length = 4000)
    private Set<UUID> linkedTaskIds;

    @Column(name = "is_achieved", nullable = false)
    @Builder.Default
    private boolean isAchieved = false;

    /**
     * Jour ou l'objectif a ete atteint. C'est CE jour qui compte pour la serie cote app, d'ou
     * son importance : il ne doit jamais survivre a un objectif redevenu non atteint.
     */
    @Column(name = "achieved_at")
    private Instant achievedAt;

    /** Tombstone de synchronisation, meme role que sur {@link AppBlockRule}. */
    @Column(name = "deleted_at")
    private Instant deletedAt;
}
