package com.byzi.api.domain;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

@Entity
@Table(name = "app_block_rules")
@SuperBuilder
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true, exclude = {"user", "selectionData"})
public class AppBlockRule extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    // Pas de @Lob : sur Postgres, @Lob sur un String bascule sur les "large objects" (oid),
    // qui ne se comportent pas comme une colonne texte ordinaire (pas de lecture hors
    // transaction, pas de suppression automatique). Un varchar large suffit, la taille etant
    // deja bornee cote entree par @Size(max = 200_000) sur le DTO.
    @Column(name = "selection_data", nullable = false, length = 200_000)
    private String selectionData;

    // Nullable : une regle peut n'avoir qu'une plage horaire, sans quota de minutes.
    @Column(name = "daily_limit_minutes")
    private Integer dailyLimitMinutes;

    @Column(name = "schedule_start", length = 5)
    private String scheduleStart;

    @Column(name = "schedule_end", length = 5)
    private String scheduleEnd;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    /**
     * Tombstone de synchronisation (MANQUE-02), meme role que sur FocusSession.
     * <p>
     * A ne pas confondre avec isActive, qui est un choix de l'utilisateur (regle desactivee
     * mais conservee) : deletedAt signifie que la regle n'existe plus du tout.
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;
}
