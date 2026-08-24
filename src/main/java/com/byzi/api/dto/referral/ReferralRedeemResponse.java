package com.byzi.api.dto.referral;

import java.time.Instant;

/**
 * @param daysGranted           jours ajoutes au compte du filleul.
 * @param subscriptionExpiresAt nouvelle date de fin d'acces, source de verite serveur : l'app
 *                              ne doit jamais la recalculer elle-meme (AC de l'EPIC-07).
 * @param referrerRewarded      false quand le parrain est deja abonne payant et n'a donc rien
 *                              recu - l'app peut alors ajuster son message de confirmation
 *                              plutot que d'annoncer une recompense qui n'a pas eu lieu.
 */
public record ReferralRedeemResponse(
        int daysGranted,
        Instant subscriptionExpiresAt,
        boolean referrerRewarded
) {
}
