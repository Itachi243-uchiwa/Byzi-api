package com.byzi.api.service.referral;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Parametres du parrainage (backlog 10.8).
 */
@Validated
@ConfigurationProperties(prefix = "byzi.referral")
public record ReferralProperties(

        /**
         * Jours offerts au filleul ET au parrain a chaque utilisation reussie d'un code.
         * <p>
         * Configurable parce que c'est un levier commercial : passer de 7 a 14 jours pendant
         * une campagne ne doit demander ni deploiement de code, ni mise a jour de l'app.
         * Plafonne, parce qu'une valeur saisie de travers dans une variable d'environnement
         * offrirait des annees d'abonnement gratuit sans que rien ne s'y oppose.
         */
        @Positive
        @Max(90)
        int trialExtensionDays
) {
}
