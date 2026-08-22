package com.byzi.api.domain;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

@Entity
@Table(
        name = "focus_sessions",
        indexes = {
                @Index(name = "idx_focus_sessions_user_started", columnList = "user_id,started_at")
        }
)
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true, exclude = "user")
public class FocusSession extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "planned_duration_seconds", nullable = false)
    private Integer plannedDurationSeconds;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 32)
    private SessionMode mode;

    @Column(name = "completed", nullable = false)
    @Builder.Default
    private boolean completed = false;

    /**
     * Tombstone de synchronisation (MANQUE-02). Effacer physiquement la ligne la rendrait
     * invisible du delta, et le PUT suivant de l'appareil qui ignore encore la suppression
     * la recreerait. Renseigne = supprimee du point de vue des clients.
     * <p>
     * Sans rapport avec la suppression de compte RGPD, qui reste physique et en cascade
     * (cf. AccountDeletionService).
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;
}
