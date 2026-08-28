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

    /**
     * Libelle donne par l'utilisateur ("Reseaux sociaux", "Jeux"...). Purement descriptif,
     * synchronise tel quel. NOT NULL en base (defaut "" pour les regles anterieures a V8).
     */
    @Column(name = "name", nullable = false, length = 100)
    @Builder.Default
    private String name = "";

    /**
     * FOCUS (bloque pendant une session de focus) ou LIMIT (creneau planifie / quota
     * quotidien). Defaut FOCUS pour les regles anterieures a V8.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    @Builder.Default
    private RuleKind kind = RuleKind.FOCUS;

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

    /**
     * Jours ou la plage horaire s'applique (backlog 10.7). {@code null} signifie "tous les
     * jours" : c'est l'etat des regles creees avant l'introduction du champ, dont le
     * comportement ne doit pas changer.
     * <p>
     * Le serveur ne fait que stocker et synchroniser cette valeur - c'est l'app iOS qui
     * arme le blocage au bon moment, comme pour scheduleStart/scheduleEnd. Le fuseau horaire
     * est donc celui de l'appareil, et volontairement : une regle "9h-18h du lundi au
     * vendredi" doit suivre l'utilisateur qui voyage, pas rester accrochee au fuseau de son
     * inscription.
     */
    @Convert(converter = ScheduleDaysConverter.class)
    @Column(name = "schedule_days", length = 13)
    private java.util.Set<java.time.DayOfWeek> scheduleDays;

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
