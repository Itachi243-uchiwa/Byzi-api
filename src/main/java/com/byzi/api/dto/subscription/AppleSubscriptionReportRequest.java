package com.byzi.api.dto.subscription;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Ce que l'app iOS rapporte apres avoir lu {@code Transaction.currentEntitlements} en local
 * (StoreKit 2, aucun SDK tiers).
 * <p>
 * Authentifie (route {@code /api/v1/me/...}, {@code userId} vient du JWT, jamais du corps) mais
 * <b>non verifie cryptographiquement</b> cote serveur : ce rapport vient directement du client,
 * et un appareil compromis pourrait mentir. Durcissement prevu : verification serveur de la
 * transaction signee via l'App Store Server Library, ou App Store Server Notifications V2.
 * Voir {@code SubscriptionService.applyClientReportedApplePurchase}.
 * <p>
 * {@code expiresAt} suffit a fermer la boucle sans evenement d'expiration explicite :
 * {@code AccountProfileService.hasActiveAccess} recompare deja cette date a l'horloge SERVEUR a
 * chaque lecture — un abonnement qui n'est plus renouvele redevient sans acces tout seul, sans
 * qu'aucun evenement "EXPIRED" n'ait besoin d'etre pousse.
 */
public record AppleSubscriptionReportRequest(
        /** {@code Transaction.id} StoreKit — unique par transition (achat, renouvellement...),
         *  et c'est lui qui porte l'idempotence cote serveur. */
        @NotBlank
        String transactionId,

        @NotBlank
        String productId,

        @NotNull
        Instant expiresAt,

        /** {@code transaction.offer?.type == .introductory} cote client. */
        boolean trialPeriod
) {
}
