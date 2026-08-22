package com.byzi.api.domain;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false) // Correction de la colonne de jointure
    private User user;

    @Column(name = "day", nullable = false)
    private LocalDate day;

    @Column(name = "goal_reached", nullable = false)
    private boolean goalReached;

    @Column(name = "focus_minutes", nullable = false)
    @Builder.Default
    private Integer focusMinutes = 0;
}