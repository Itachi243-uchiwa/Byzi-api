package com.byzi.api.dto.subscription;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/**
 * Ce que l'app iOS rapporte quand elle constate qu'une transaction a ete
 * <b>revoquee</b> — remboursement accorde par Apple, ou retrait d'un achat par
 * le Partage familial (story 07.9).
 * <p>
 * StoreKit expose cet etat via {@code Transaction.revocationDate}, que le client
 * lit deja pour exclure la transaction de {@code currentEntitlements}. Il ne le
 * remontait simplement jamais : le serveur gardait donc l'acces ouvert jusqu'a
 * {@code subscriptionExpiresAt}, soit jusqu'a un an sur la formule annuelle.
 * <p>
 * Aucun {@code expiresAt} dans ce corps, et c'est deliberé : un remboursement
 * coupe l'acces <b>maintenant</b>. Laisser le client proposer une date de fin
 * rouvrirait exactement la porte qu'on ferme.
 * <p>
 * Meme regime de confiance que {@link AppleSubscriptionReportRequest} : la route
 * est authentifiee ({@code userId} vient du JWT), mais le contenu n'est pas
 * verifie contre Apple. Le sens du mensonge possible est ici <b>defavorable a
 * l'utilisateur</b> — on ne peut que se retirer un acces, jamais s'en octroyer
 * un — ce qui en fait le rapport le moins risque des deux.
 */
public record AppleSubscriptionRevocationRequest(
        /** {@code Transaction.id} de la transaction revoquee. Porte l'idempotence. */
        @NotBlank
        String transactionId,

        /** {@code Transaction.revocationDate}. {@code null} accepte : le serveur prend l'heure de reception. */
        Instant revokedAt
) {
}
