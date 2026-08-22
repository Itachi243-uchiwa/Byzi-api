package com.byzi.api.domain;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(
        name = "streak_records",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_streak_user_day", columnNames = {"user_id", "day"})
        }
)
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true, exclude = "user")
public class StreakRecord extends BaseEntity {

    /**
     * updatable = false est ESSENTIEL ici (audit backend - BLOQ-01). Sans lui, un upsert
     * portant l'id d'une ligne appartenant a un autre compte produisait un merge Hibernate
     * qui reaffectait user_id a l'appelant : la victime perdait purement et simplement sa
     * ligne, qui changeait de proprietaire. Un enregistrement ne doit jamais pouvoir changer
     * de proprietaire, quelle que soit la requete.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "day", nullable = false)
    private LocalDate day;

    @Column(name = "goal_reached", nullable = false)
    private boolean goalReached;

    @Column(name = "focus_minutes", nullable = false)
    @Builder.Default
    private Integer focusMinutes = 0;

    /**
     * Tombstone de synchronisation (MANQUE-02), meme role que sur FocusSession.
     * <p>
     * Cas particulier de cette entite : la contrainte uk_streak_user_day interdit deux lignes
     * pour le meme (user_id, day). Un streak supprime occupe donc toujours son creneau - la
     * recreation du meme jour REANIME la ligne existante au lieu d'en inserer une seconde,
     * cf. StreakRecordService.upsert.
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;
}