package com.byzi.api.dto.account;

import com.byzi.api.domain.SubscriptionStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Volet "profil" de l'export RGPD (art. 20). appleSub est inclus deliberement : c'est un
 * identifiant opaque fourni par Apple, mais il concerne bien la personne (c'est sa cle
 * d'authentification aupres de Byzi), donc une donnee personnelle a part entiere.
 * <p>
 * passwordHash et role restent HORS de ce DTO : ce sont des donnees internes au fonctionnement
 * du systeme (authentification back-office, autorisation), pas des donnees concernant
 * l'utilisateur au sens de l'article 20.
 */
public record AccountExportProfile(
        UUID userId,
        String appleSub,
        String email,
        SubscriptionStatus subscriptionStatus,
        Instant subscriptionExpiresAt,
        Instant createdAt,
        Instant lastLoginAt
) {
}
