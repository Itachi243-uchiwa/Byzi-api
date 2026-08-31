package com.byzi.api.domain;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * Réglages d'un utilisateur — ressource singulière (une ligne par compte, cf.
 * uk_user_settings_user). Miroir serveur de {@code FocusGoal} côté iOS : le serveur ne fait
 * que stocker et transporter la valeur, c'est l'app qui l'applique.
 * <p>
 * user_id est {@code updatable = false} pour la même raison que sur {@link StreakRecord} :
 * un enregistrement ne doit jamais changer de propriétaire, quelle que soit la requête.
 */
@Entity
@Table(
        name = "user_settings",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_user_settings_user", columnNames = {"user_id"})
        }
)
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true, exclude = "user")
public class UserSettings extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    /** Objectif de focus réel quotidien, en minutes (borné 5..240, cf. ck_user_settings_goal). */
    @Column(name = "daily_goal_minutes", nullable = false)
    @Builder.Default
    private Integer dailyGoalMinutes = 25;
}
