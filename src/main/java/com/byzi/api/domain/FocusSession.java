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
}
