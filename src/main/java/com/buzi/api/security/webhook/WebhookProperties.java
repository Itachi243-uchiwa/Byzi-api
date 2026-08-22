package com.buzi.api.security.webhook;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "byzi.webhook.revenuecat")
public record WebhookProperties(

        /**
         * Secret partage configure dans le dashboard RevenueCat (champ "Authorization header").
         * RevenueCat le renvoie tel quel dans l'en-tete Authorization de chaque appel.
         * Injecte par variable d'environnement, jamais commite (OWASP A02).
         */
        @NotBlank
        String secret
) {
}
