package com.byzi.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

/**
 * Utilisation reussie d'un code de parrainage (backlog 10.8) : qui a parraine qui, avec quel
 * code, et combien de jours chacun a reellement recus.
 * <p>
 * La contrainte d'unicite sur referred_id porte l'invariant du dispositif - un compte ne peut
 * etre parraine qu'une fois - et elle est dans la base, pas dans le service : deux requetes
 * concurrentes passeraient a travers un simple "select if exists".
 */
@Entity
@Data
@Table(name = "referral_redemptions")
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true, exclude = {"referrer", "referred"})
public class ReferralRedemption extends BaseEntity {

    /** Le parrain, proprietaire du code utilise. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "referrer_id", nullable = false, updatable = false)
    private User referrer;

    /** Le filleul, celui qui a saisi le code. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "referred_id", nullable = false, updatable = false)
    private User referred;

    @Column(name = "code", nullable = false, updatable = false, length = 10)
    private String code;

    @Column(name = "referred_days", nullable = false)
    private int referredDays;

    @Column(name = "referrer_days", nullable = false)
    private int referrerDays;

    @Column(name = "redeemed_at", nullable = false, updatable = false)
    private Instant redeemedAt;
}
