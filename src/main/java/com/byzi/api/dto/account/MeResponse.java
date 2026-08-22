package com.byzi.api.dto.account;

import com.byzi.api.domain.SubscriptionStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Contrat de reference pour le client Swift (HAUT-01 de l'audit backend, EPIC-07).
 * <p>
 * hasActiveAccess est calcule UNIQUEMENT cote serveur (voir AccountProfileService) pour que
 * l'app iOS n'ait jamais a comparer subscriptionExpiresAt a une horloge locale - c'est
 * precisement cette comparaison, trivialement contournable en reculant l'heure de l'iPhone,
 * que l'EPIC-07 interdit. Le serveur rend un verdict pret a consommer, pas une date a
 * interpreter.
 */
public record MeResponse(
        UUID userId,
        String email,
        SubscriptionStatus subscriptionStatus,
        Instant subscriptionExpiresAt,
        boolean hasActiveAccess
) {
}
