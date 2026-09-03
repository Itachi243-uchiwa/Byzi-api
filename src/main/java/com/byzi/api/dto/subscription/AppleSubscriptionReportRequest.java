package com.byzi.api.dto.subscription;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Ce que l'app iOS rapporte apres avoir lu {@code Transaction.currentEntitlements} en local
 * (StoreKit 2) — pas de SDK RevenueCat cote client (EPIC-07, decision 2026-09-03 : pas de
 * compte/cle RevenueCat disponible).
 * <p>
 * Authentifie (route {@code /api/v1/me/...}, {@code userId} vient du JWT, jamais du corps) mais
 * <b>non verifie cryptographiquement</b> cote serveur : contrairement au webhook RevenueCat
 * (verifie par Apple avant meme d'arriver), ce rapport vient directement du client. Un appareil
 * compromis pourrait mentir. Suffisant pour le lancement (pas pire qu'un SDK tiers en confiance
 * cote client), mais **pas** la meme garantie que le webhook — durcissement prevu :
 * verification serveur de la transaction signee via l'App Store Server Library, ou de vrais
 * App Store Server Notifications V2. Voir {@code SubscriptionService.applyClientReportedApplePurchase}.
 * <p>
 * {@code expiresAt} suffit a fermer la boucle sans evenement d'expiration explicite :
 * {@code AccountProfileService.hasActiveAccess} recompare deja cette date a l'horloge SERVEUR a
 * chaque lecture — un abonnement qui n'est plus renouvele redevient sans acces tout seul, sans
 * qu'aucun evenement "EXPIRED" n'ait besoin d'etre pousse.
 */
public record AppleSubscriptionReportRequest(
        /** {@code Transaction.id} StoreKit — unique par transition (achat, renouvellement...),
         *  porte l'idempotence comme {@code event.id} chez RevenueCat. */
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
