package com.byzi.api.controller;

import com.apple.itunes.storekit.verification.VerificationException;
import com.byzi.api.service.subscription.AppleServerNotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reception des App Store Server Notifications V2 (story 07.10).
 * <p>
 * <b>Endpoint public par necessite</b> : c'est Apple qui appelle, sans jeton. Sa protection
 * n'est pas un secret partage mais la <b>signature du corps</b>, verifiee jusqu'au certificat
 * racine d'Apple. Un appel non signe, ou signe par autre chose, repart en 401 sans avoir touche
 * a quoi que ce soit.
 * <p>
 * Contrat de reponse volontairement binaire, comme l'ancien webhook : 401 si la signature ne
 * tient pas, 200 dans tous les autres cas. Un evenement ignore (type sans effet, compte
 * supprime, doublon) renvoie quand meme 200 - Apple rejoue jusqu'a cinq fois sur 24 h tout ce
 * qui n'est pas acquitte, donc repondre en erreur pour un evenement qu'on a choisi d'ignorer
 * creerait une boucle de rejeu inutile.
 * <p>
 * URL a renseigner dans App Store Connect : {@code https://<host>/api/v1/webhooks/apple},
 * champ "App Store Server Notifications", version 2.
 */
@Slf4j
@Tag(name = "Webhooks", description = "Notifications serveur d'Apple")
@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
public class AppleServerNotificationController {

    private final AppleServerNotificationService notificationService;

    /** Le corps d'une notification V2 : un seul champ, le JWS. */
    public record SignedPayload(String signedPayload) {
    }

    @Operation(summary = "Recoit une App Store Server Notification V2 signee par Apple")
    @PostMapping("/apple")
    public ResponseEntity<Void> receive(@RequestBody SignedPayload body) {
        if (body == null || body.signedPayload() == null || body.signedPayload().isBlank()) {
            log.warn("Notification App Store sans signedPayload, rejetee");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            notificationService.handle(body.signedPayload());
        } catch (VerificationException e) {
            // Signature, chaine de certificats, bundle id ou environnement invalides. Ce n'est
            // pas Apple : on refuse, et on ne rejoue rien.
            log.warn("Notification App Store rejetee : {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        } catch (DataIntegrityViolationException e) {
            // Deux livraisons du meme evenement traitees en parallele : la contrainte d'unicite
            // sur event_id a tranche. L'etat en base est celui qu'a ecrit la transaction
            // gagnante - le meme, puisque c'est le meme evenement. Rien a rejouer.
            log.info("Notification App Store deja inseree par une livraison concurrente, acquittee");
        }
        return ResponseEntity.ok().build();
    }
}
