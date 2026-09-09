package com.byzi.api.service.subscription;

import com.apple.itunes.storekit.model.Environment;
import com.apple.itunes.storekit.model.JWSTransactionDecodedPayload;
import com.apple.itunes.storekit.model.NotificationTypeV2;
import com.apple.itunes.storekit.model.ResponseBodyV2DecodedPayload;
import com.apple.itunes.storekit.model.Subtype;
import com.apple.itunes.storekit.verification.SignedDataVerifier;
import com.apple.itunes.storekit.verification.VerificationException;
import com.byzi.api.domain.SubscriptionStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * App Store Server Notifications V2 (story 07.10) : ce qu'Apple pousse au serveur, meme quand
 * l'app ne tourne pas.
 *
 * <h2>Pourquoi ceci existe</h2>
 * Jusqu'ici l'etat d'abonnement ne bougeait que lorsque l'app se relancait et le rapportait
 * elle-meme. Trois choses echappaient donc au serveur :
 * <ul>
 *   <li>un <b>remboursement</b> - le compte gardait l'acces jusqu'a la date d'expiration
 *       initiale si l'app ne se relancait jamais ;</li>
 *   <li>un <b>renouvellement</b> - le serveur croyait le compte expire jusqu'a la prochaine
 *       ouverture, meme si la personne payait bien ;</li>
 *   <li>une <b>periode de grace</b> - invisible, donc impossible a distinguer d'un abandon.</li>
 * </ul>
 * Apple pousse ces evenements en direct. C'est la seule source qui ne depende pas de l'appareil,
 * et donc la seule qui ferme vraiment la boucle.
 *
 * <h2>Verification</h2>
 * Le corps est un JWS dont l'en-tete porte une chaine de certificats {@code x5c}. La verifier
 * signifie : controler que le certificat feuille signe bien la charge utile, puis remonter la
 * chaine jusqu'a l'<b>AppleRootCA-G3</b>, et enfin controler que le bundle id et
 * l'environnement sont bien les notres.
 * <p>
 * Ce travail est confie a {@link SignedDataVerifier}, de la bibliotheque publiee par Apple.
 * L'ecrire a la main serait du code de securite maison sur le chemin critique de la
 * facturation : une erreur de validation de chaine, et n'importe qui peut s'octroyer un
 * abonnement a vie en forgeant un JWS.
 * <p>
 * <b>C'est ce qui remplace le secret partage de RevenueCat</b>, retire le 2026-09-09 : une
 * signature cryptographique verifiee contre une racine connue, plutot qu'un mot de passe dans
 * un en-tete HTTP.
 *
 * <h2>Idempotence et ordre</h2>
 * Rien de nouveau : {@code notificationUUID} joue le role d'{@code eventId}, et
 * {@link SubscriptionService#applyTransition} apporte deja le dedoublonnage et la garde
 * anti-desordre. Apple rejoue une notification jusqu'a cinq fois sur 24 h tant qu'elle n'a pas
 * recu de 2xx ; recevoir deux fois le meme evenement doit rester sans effet.
 */
@Slf4j
@Service
public class AppleServerNotificationService {

    /** Le certificat racine d'Apple, embarque dans le jar (src/main/resources/apple/). */
    private static final String ROOT_CA = "apple/AppleRootCA-G3.cer";

    private final SubscriptionService subscriptionService;
    private final SignedDataVerifier verifier;

    public AppleServerNotificationService(
            SubscriptionService subscriptionService,
            @Value("${byzi.apple.notifications.bundle-id}") String bundleId,
            @Value("${byzi.apple.notifications.app-apple-id:#{null}}") Long appAppleId,
            @Value("${byzi.apple.notifications.environment:SANDBOX}") String environment
    ) throws IOException {
        this.subscriptionService = subscriptionService;
        Environment env = Environment.valueOf(environment);

        try (InputStream rootCa = new ClassPathResource(ROOT_CA).getInputStream()) {
            this.verifier = new SignedDataVerifier(
                    Set.of(rootCa.readAllBytes()).stream()
                            .map(java.io.ByteArrayInputStream::new)
                            .collect(java.util.stream.Collectors.toSet()),
                    bundleId,
                    appAppleId,
                    env,
                    // Verification en ligne de la revocation des certificats : desactivee.
                    // Elle ajoute un appel reseau sortant SYNCHRONE sur le chemin d'un webhook
                    // qu'Apple veut voir acquitte vite. La chaine reste verifiee hors ligne
                    // jusqu'a la racine, ce qui est la garantie qui compte.
                    false);
        }
        log.info("Notifications App Store V2 : verification active (environnement={})", env);
    }

    /**
     * Verifie, decode et applique une notification.
     *
     * @return true si l'etat du compte a change
     * @throws VerificationException signature, chaine, bundle id ou environnement invalides.
     *         L'appelant repond alors 401 : ce n'est pas une notification d'Apple.
     */
    public boolean handle(String signedPayload) throws VerificationException {
        ResponseBodyV2DecodedPayload payload = verifier.verifyAndDecodeNotification(signedPayload);
        NotificationTypeV2 type = payload.getNotificationType();
        Subtype subtype = payload.getSubtype();

        if (type == NotificationTypeV2.TEST) {
            // Notification de test declenchee depuis App Store Connect : elle prouve que
            // l'URL repond et que la signature se verifie. Rien a appliquer.
            log.info("Notification App Store de TEST recue et verifiee");
            return false;
        }

        Optional<SubscriptionStatus> newStatus = mapStatus(type, subtype);
        if (newStatus.isEmpty()) {
            // La majorite des types ne changent PAS l'acces : un changement de preference de
            // renouvellement, une hausse de prix, une demande de consommation. Les appliquer
            // "au cas ou" ferait bouger l'etat sans raison.
            log.info("Notification App Store {}/{} sans effet sur l'acces, ignoree", type, subtype);
            return false;
        }

        if (payload.getData() == null || payload.getData().getSignedTransactionInfo() == null) {
            log.warn("Notification App Store {} sans transaction signee, ignoree", type);
            return false;
        }

        JWSTransactionDecodedPayload transaction =
                verifier.verifyAndDecodeTransaction(payload.getData().getSignedTransactionInfo());

        UUID userId = transaction.getAppAccountToken();
        if (userId == null) {
            // `appAccountToken` est pose par l'app au moment de l'achat (StoreService.purchase).
            // Absent, la notification ne peut se rattacher a aucun compte : typiquement un
            // achat effectue avant que l'app ne pose ce jeton. Rien a faire, et surtout pas
            // deviner.
            log.warn("Notification App Store {} sans appAccountToken : compte inconnu, ignoree", type);
            return false;
        }

        Instant expiresAt = expiryFor(newStatus.get(), transaction);
        Instant occurredAt = payload.getSignedDate() == null
                ? Instant.now()
                : Instant.ofEpochMilli(payload.getSignedDate());

        String eventId = "apple-assn:" + payload.getNotificationUUID();
        String eventType = "APPLE_" + type + (subtype == null ? "" : "_" + subtype);

        return subscriptionService.applyServerNotification(
                userId, eventId, eventType, newStatus.get(), expiresAt, occurredAt);
    }

    /**
     * Quel etat d'acces resulte de cette notification, ou {@code empty} si elle n'en change pas.
     * <p>
     * Le choix qui merite d'etre explicite : <b>{@code DID_CHANGE_RENEWAL_STATUS} ne change
     * rien</b>. Couper le renouvellement automatique n'annule pas l'abonnement en cours - la
     * personne a paye jusqu'au terme et y a droit. Traiter cet evenement comme une resiliation
     * couperait l'acces d'un client a jour de ses paiements, le jour ou il decide de ne pas
     * reconduire.
     */
    private Optional<SubscriptionStatus> mapStatus(NotificationTypeV2 type, Subtype subtype) {
        if (type == null) {
            return Optional.empty();
        }
        return switch (type) {
            // Entrees et reconductions.
            case SUBSCRIBED, DID_RENEW, OFFER_REDEEMED, RENEWAL_EXTENDED ->
                    Optional.of(SubscriptionStatus.ACTIVE);

            // Sorties. REFUND et REVOKE sont les deux qui manquaient vraiment : Apple a rendu
            // l'argent, ou l'organisateur du Partage familial a retire l'achat.
            case EXPIRED, GRACE_PERIOD_EXPIRED, REFUND, REVOKE ->
                    Optional.of(SubscriptionStatus.EXPIRED);

            // Echec de renouvellement : l'acces survit PENDANT la periode de grace seulement.
            // Sans ce sous-type, on est en nouvelle tentative de facturation et l'acces est
            // deja termine.
            case DID_FAIL_TO_RENEW -> Optional.of(subtype == Subtype.GRACE_PERIOD
                    ? SubscriptionStatus.GRACE_PERIOD
                    : SubscriptionStatus.EXPIRED);

            // Un remboursement annule : la personne recupere son acces.
            case REFUND_REVERSED -> Optional.of(SubscriptionStatus.ACTIVE);

            default -> Optional.empty();
        };
    }

    /**
     * Une sortie d'acces efface l'expiration au lieu de la reculer - meme regle que le rapport
     * client de revocation et que le geste manuel du back-office : couper, c'est maintenant.
     */
    private Instant expiryFor(SubscriptionStatus status, JWSTransactionDecodedPayload transaction) {
        if (status == SubscriptionStatus.EXPIRED) {
            return null;
        }
        Long expires = transaction.getExpiresDate();
        return expires == null ? null : Instant.ofEpochMilli(expires);
    }
}
