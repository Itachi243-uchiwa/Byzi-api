package com.byzi.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Trace d'une action d'administration (story 09.7).
 * <p>
 * Append-only : aucune methode applicative ne modifie ni ne supprime une entree. Un journal
 * d'audit que l'application peut reecrire ne prouve rien.
 * <p>
 * L'admin est identifie par son id ET par un libelle lisible : conserver seulement l'id
 * rendrait le journal illisible le jour ou le compte admin est supprime, alors qu'un audit
 * doit survivre a ses acteurs. Pour la meme raison, target_user_id n'a pas de cle etrangere
 * vers users - une cascade effacerait la trace d'une suppression de compte au moment precis
 * ou elle devient la plus utile.
 */
@Entity
@Table(name = "admin_audit_log")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminAuditLog {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "admin_id", nullable = false, updatable = false)
    private UUID adminId;

    @Column(name = "admin_label", nullable = false, updatable = false, length = 255)
    private String adminLabel;

    @Column(name = "action", nullable = false, updatable = false, length = 64)
    private String action;

    @Column(name = "target_user_id", updatable = false)
    private UUID targetUserId;

    @Column(name = "details", updatable = false, length = 1000)
    private String details;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;
}
