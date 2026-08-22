package com.byzi.api.security.webhook;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Verifie le secret partage porte par l'en-tete Authorization des webhooks RevenueCat.
 * <p>
 * L'endpoint webhook est necessairement ouvert (pas de JWT utilisateur : c'est un serveur
 * tiers qui appelle), donc ce secret est la SEULE chose qui empeche n'importe qui de
 * declarer un abonnement actif sur le compte de son choix. La comparaison passe par
 * {@link MessageDigest#isEqual} et non par {@code equals} : une comparaison de chaines
 * s'arrete au premier caractere different, ce qui rend le temps de reponse dependant du
 * nombre de caracteres corrects et ouvre la porte a une reconstitution du secret octet par
 * octet (attaque temporelle).
 */
@Component
public class WebhookAuthenticator {

    private final byte[] expectedSecret;

    public WebhookAuthenticator(WebhookProperties properties) {
        this.expectedSecret = properties.secret().getBytes(StandardCharsets.UTF_8);
    }

    public boolean isAuthorized(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(authorizationHeader.getBytes(StandardCharsets.UTF_8), expectedSecret);
    }
}
