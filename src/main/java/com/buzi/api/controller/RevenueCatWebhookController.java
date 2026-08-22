package com.buzi.api.controller;

import com.buzi.api.dto.subscription.RevenueCatWebhookRequest;
import com.buzi.api.security.webhook.WebhookAuthenticator;
import com.buzi.api.service.subscription.SubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reception des webhooks RevenueCat (EPIC-07.5).
 * <p>
 * Contrat de reponse volontairement binaire : 401 si le secret ne correspond pas, 200 dans
 * tous les autres cas. Un evenement ignore (doublon, type non pertinent, compte supprime)
 * renvoie quand meme 200 - RevenueCat rejoue tout ce qui n'est pas acquitte, donc repondre
 * en erreur pour un evenement qu'on a choisi d'ignorer creerait une boucle de rejeu inutile.
 */
@Slf4j
@Tag(name = "Webhooks", description = "Callbacks des services tiers (RevenueCat)")
@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
public class RevenueCatWebhookController {

    private final WebhookAuthenticator webhookAuthenticator;
    private final SubscriptionService subscriptionService;

    @Operation(summary = "Recoit les evenements d'abonnement RevenueCat et met a jour le statut du compte")
    @PostMapping("/revenuecat")
    public ResponseEntity<Void> receive(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody RevenueCatWebhookRequest request
    ) {
        if (!webhookAuthenticator.isAuthorized(authorization)) {
            log.warn("Webhook RevenueCat rejete : secret partage invalide");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        subscriptionService.applyWebhookEvent(request.event());
        return ResponseEntity.ok().build();
    }
}
