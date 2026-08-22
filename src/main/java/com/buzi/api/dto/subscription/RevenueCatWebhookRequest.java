package com.buzi.api.dto.subscription;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Payload d'un webhook RevenueCat.
 * <p>
 * Seuls les champs dont Byzi a besoin sont declares. Le corps reel en contient bien
 * davantage (store, prix, devise, entitlements, plateforme...) : ignoreUnknown garantit que
 * l'ajout d'un champ cote RevenueCat ne fera pas echouer la reception - un webhook rejete
 * pour une raison cosmetique serait rejoue en boucle et finirait par etre abandonne, laissant
 * l'abonnement desynchronise.
 * <p>
 * Les noms JSON sont declares explicitement plutot que via une strategie de nommage globale :
 * le contrat vient d'un tiers, il doit rester lisible ici et insensible a un changement de
 * configuration Jackson ailleurs dans l'application.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RevenueCatWebhookRequest(
        @NotNull
        @Valid
        Event event
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Event(

            /** Identifiant unique de l'evenement chez RevenueCat : porte l'idempotence. */
            @NotBlank
            @JsonProperty("id")
            String id,

            @NotBlank
            @JsonProperty("type")
            String type,

            /** Identifiant transmis par l'app iOS a RevenueCat : le userId Byzi. */
            @NotBlank
            @JsonProperty("app_user_id")
            String appUserId,

            /** Millisecondes epoch. Absent des evenements sans abonnement actif (ex. EXPIRATION). */
            @JsonProperty("expiration_at_ms")
            Long expirationAtMs,

            @JsonProperty("event_timestamp_ms")
            Long eventTimestampMs,

            /** "TRIAL", "NORMAL", "INTRO"... Distingue un essai d'un abonnement payant. */
            @JsonProperty("period_type")
            String periodType
    ) {
    }
}
