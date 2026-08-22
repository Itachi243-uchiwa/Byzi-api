package com.buzi.api.service.subscription;

import com.buzi.api.domain.SubscriptionStatus;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * Traduit un type d'evenement RevenueCat en statut d'abonnement Byzi.
 * <p>
 * Isole du service pour rester testable sans base ni contexte Spring : c'est ici que vivent
 * les regles metier subtiles de la facturation, et elles meritent des tests exhaustifs.
 * <p>
 * Deux decisions qui ne vont pas de soi :
 * <ul>
 *   <li><b>CANCELLATION ne coupe pas l'acces.</b> Chez RevenueCat, cet evenement signifie
 *       "l'utilisateur a desactive le renouvellement automatique", pas "l'abonnement est
 *       termine". L'acces reste du jusqu'a la date d'expiration deja payee ; c'est
 *       l'evenement EXPIRATION qui clot reellement l'abonnement. Couper a CANCELLATION
 *       reviendrait a priver un client d'un temps qu'il a paye.</li>
 *   <li><b>BILLING_ISSUE bascule en GRACE_PERIOD, pas en EXPIRED.</b> Un prelevement qui
 *       echoue est le plus souvent temporaire (carte expiree, plafond) ; Apple retente
 *       plusieurs jours. Expirer immediatement ferait perdre des clients recuperables.</li>
 * </ul>
 */
@Component
public class RevenueCatEventMapper {

    private static final String PERIOD_TYPE_TRIAL = "TRIAL";

    /**
     * @return le statut resultant, ou {@link Optional#empty()} si l'evenement ne concerne pas
     *         le cycle de vie de l'abonnement (transfert de compte, alias, evenement de test,
     *         ou type inconnu ajoute par RevenueCat apres coup). Un type non reconnu ne doit
     *         jamais faire echouer le webhook : il est acquitte et ignore, sinon RevenueCat
     *         le rejouerait indefiniment.
     */
    public Optional<SubscriptionStatus> toStatus(String eventType, String periodType) {
        if (eventType == null) {
            return Optional.empty();
        }
        boolean isTrial = PERIOD_TYPE_TRIAL.equalsIgnoreCase(periodType);

        return switch (eventType.toUpperCase(Locale.ROOT)) {
            // Un achat initial en periode d'essai reste un essai : le facturer comme ACTIVE
            // fausserait le taux de conversion essai -> payant du dashboard (story 09.4).
            case "INITIAL_PURCHASE" -> Optional.of(isTrial ? SubscriptionStatus.TRIAL : SubscriptionStatus.ACTIVE);
            case "RENEWAL", "PRODUCT_CHANGE", "UNCANCELLATION", "SUBSCRIPTION_EXTENDED" ->
                    Optional.of(SubscriptionStatus.ACTIVE);
            case "CANCELLATION" -> Optional.of(isTrial ? SubscriptionStatus.TRIAL : SubscriptionStatus.ACTIVE);
            case "BILLING_ISSUE" -> Optional.of(SubscriptionStatus.GRACE_PERIOD);
            case "EXPIRATION" -> Optional.of(SubscriptionStatus.EXPIRED);
            default -> Optional.empty();
        };
    }
}
