package com.byzi.api.domain;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Entity
@Data
@Table(
        name = "users",
        indexes = {
                @Index(name = "idx_users_apple_sub", columnList = "apple_sub", unique = true)
        })
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true, exclude = {"passwordHash", "appleSub", "email"})
public class User extends BaseEntity {

    @Column(name = "apple_sub", nullable = false, unique = true, updatable = false, length = 255)
    private String appleSub;

    @Column(name = "email", unique = true)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    @Builder.Default
    private Role role = Role.USER;

    @Enumerated(EnumType.STRING)
    @Column(name = "subscription_status", nullable = false, length = 32)
    @Builder.Default
    private SubscriptionStatus subscriptionStatus = SubscriptionStatus.TRIAL;

    @Column(name = "last_login_at")
    private java.time.Instant lastLoginAt;

    /**
     * Date de fin de l'abonnement ou de l'essai, telle que communiquee par RevenueCat (ou
     * prolongee manuellement par le support, story 09.5). Source de verite serveur : l'app
     * ne doit jamais deduire l'etat d'abonnement d'une date locale, qui serait triviale a
     * contourner en changeant l'horloge de l'appareil (AC de l'EPIC-07).
     */
    @Column(name = "subscription_expires_at")
    private java.time.Instant subscriptionExpiresAt;

    /**
     * Hash BCrypt, renseigne uniquement pour les comptes ADMIN qui se connectent au
     * back-office par navigateur. Les comptes utilisateurs passent par Sign in with Apple et
     * n'ont pas de mot de passe : ce champ reste null pour eux.
     * <p>
     * Exclu de toString() par la configuration @ToString de la classe : un hash de mot de
     * passe n'a rien a faire dans un log.
     */
    @Column(name = "password_hash", length = 72)
    private String passwordHash;

    /**
     * Code de parrainage (backlog 10.8), null tant que l'utilisateur n'a pas ouvert son ecran
     * de partage : il est cree a la demande par ReferralService, pas a l'inscription.
     * <p>
     * updatable reste vrai a dessein - le champ passe de null a une valeur une fois dans la
     * vie du compte. En revanche il n'est jamais REgenere : les codes deja partages par
     * l'utilisateur doivent continuer de fonctionner.
     */
    @Column(name = "referral_code", unique = true, length = 10)
    private String referralCode;

}