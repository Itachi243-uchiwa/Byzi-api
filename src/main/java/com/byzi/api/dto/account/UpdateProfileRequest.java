package com.byzi.api.dto.account;

import jakarta.validation.constraints.Size;

/**
 * Mise a jour du profil par l'utilisateur lui-meme (backlog app 0ter T8).
 *
 * <p>Un seul champ pour l'instant, et volontairement : email, statut d'abonnement et role ne
 * sont PAS modifiables par cette route. L'email vient d'Apple, le statut est calcule par
 * RevenueCat, le role appartient au back-office - les exposer ici en ferait des vecteurs
 * d'escalade.
 *
 * <p>{@code null} et chaine vide sont equivalents : les deux effacent le prenom.
 */
public record UpdateProfileRequest(
        @Size(max = 100)
        String givenName
) {
}
