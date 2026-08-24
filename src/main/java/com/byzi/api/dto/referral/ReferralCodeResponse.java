package com.byzi.api.dto.referral;

/**
 * Ecran de partage du parrainage (backlog 10.8).
 *
 * @param code             le code a partager, cree au premier appel.
 * @param redemptionCount  nombre de filleuls ayant utilise ce code.
 * @param daysPerRedemption jours offerts de part et d'autre a chaque utilisation. Renvoye par
 *                          le serveur plutot que code en dur dans l'app : la valeur est un
 *                          levier commercial, et la changer ne doit pas exiger une mise a
 *                          jour de l'App Store.
 */
public record ReferralCodeResponse(
        String code,
        long redemptionCount,
        int daysPerRedemption
) {
}
