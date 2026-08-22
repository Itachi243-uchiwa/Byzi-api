package com.byzi.api.controller;

import com.byzi.api.dto.subscription.RevenueCatWebhookRequest;
import com.byzi.api.security.webhook.WebhookAuthenticator;
import com.byzi.api.service.subscription.SubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
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

        try {
            subscriptionService.applyWebhookEvent(request.event());
        } catch (DataIntegrityViolationException e) {
            // Deux livraisons du meme webhook traitees en parallele : la contrainte d'unicite
            // sur event_id a tranche et la transaction perdante a ete annulee. L'etat en base
            // est celui qu'a ecrit la transaction gagnante - c'est-a-dire exactement le meme,
            // puisque c'est le meme evenement. Il n'y a donc rien a rejouer.
            //
            // Le rattrapage est ICI et non dans le service : apres une violation de contrainte,
            // le contexte de persistance JPA est inutilisable et la transaction DOIT etre
            // annulee. Avaler l'exception a l'interieur du service ne ferait que deplacer
            // l'echec au moment du commit.
            log.info("Evenement RevenueCat {} deja insere par une livraison concurrente, acquitte sans rejeu",
                    request.event().id());
        }
        return ResponseEntity.ok().build();
    }
}
