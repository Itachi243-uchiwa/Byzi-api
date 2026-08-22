package com.byzi.api.dto.account;

import com.byzi.api.domain.SubscriptionStatus;

import java.time.Instant;

/**
 * Vue export d'une transition d'abonnement. Ni l'id technique de la ligne subscription_events
 * ni l'eventId RevenueCat (identifiant de livraison de webhook, pas une donnee concernant la
 * personne) ne sont exposes ici - seul ce qui decrit "ce qui est arrive a l'abonnement de
 * l'utilisateur" en fait partie.
 */
public record SubscriptionEventExport(
        String eventType,
        SubscriptionStatus resultingStatus,
        Instant expiresAt,
        Instant occurredAt
) {
}
