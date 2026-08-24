package com.byzi.api.dto.account;

import java.time.Instant;

/**
 * Volet parrainage de l'export de portabilite (art. 20 RGPD).
 * <p>
 * Une ligne de parrainage concerne DEUX personnes. L'export ne restitue donc que ce qui
 * appartient au demandeur : son propre code, le nombre de comptes qu'il a parraines, et le
 * fait qu'il ait lui-meme ete parraine. Ni l'identite de son parrain, ni celle de ses
 * filleuls n'y figurent - ce sont les donnees d'autrui, et le droit a la portabilite du
 * demandeur ne s'etend pas jusqu'a elles.
 *
 * @param code           code de l'utilisateur, null s'il n'a jamais ouvert son ecran de partage.
 * @param peopleReferred nombre de comptes ayant utilise ce code.
 * @param referredAt     date a laquelle ce compte a lui-meme ete parraine, null sinon.
 * @param daysReceived   jours recus a cette occasion, null si le compte n'a pas ete parraine.
 */
public record ReferralExport(
        String code,
        long peopleReferred,
        Instant referredAt,
        Integer daysReceived
) {
}
