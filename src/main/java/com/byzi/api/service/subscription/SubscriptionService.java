package com.byzi.api.service.subscription;

import com.byzi.api.domain.SubscriptionEvent;
import com.byzi.api.domain.SubscriptionStatus;
import com.byzi.api.domain.User;
import com.byzi.api.dto.subscription.AppleSubscriptionReportRequest;
import com.byzi.api.exception.ResourceNotFoundException;
import com.byzi.api.repository.SubscriptionEventRepository;
import com.byzi.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Applique les transitions d'abonnement rapportees par le client iOS (EPIC-07.5).
 * <p>
 * Le serveur est la SEULE source de verite de l'etat d'abonnement : l'app iOS ne fait que le
 * lire (voir {@code AccountProfileService.hasActiveAccess}). C'est l'exigence explicite de
 * l'EPIC-07 ("l'etat d'abonnement n'est jamais deduit d'une date locale sur l'appareil"), qui
 * interdit de faire confiance a une expiration calculee cote client - trivialement contournable
 * en reculant l'horloge systeme.
 * <p>
 * <b>RevenueCat a ete retire le 2026-09-09.</b> L'app est en StoreKit 2 pur, iOS uniquement,
 * avec deux produits dans un seul groupe : RevenueCat n'apportait ni la validation d'achat
 * (StoreKit 2 verifie deja la transaction au niveau de l'OS) ni rien dont le MVP ait besoin -
 * sa vraie valeur est ailleurs, dans les entitlements multiplateformes, l'analytics et l'A/B
 * testing de paywall. Restait un endpoint public a proteger, un secret partage a faire tourner
 * et 1% du revenu suivi, pour rien.
 * <p>
 * Ce qu'il faisait et qui manque vraiment - apprendre les evenements de cycle de vie quand
 * l'app ne tourne pas - se traite par <b>App Store Server Notifications V2</b>, first-party et
 * gratuit. En attendant, {@link #applyClientReportedApplePurchase} et
 * {@link #applyClientReportedRevocation} couvrent ce que le client peut constater lui-meme.
 * {@code applyTransition} reste volontairement agnostique de la source : brancher ASSN V2
 * consistera a y mapper un evenement de plus, rien d'autre.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private static final String CLIENT_REPORT_EVENT_TYPE = "APPLE_CLIENT_REPORT";
    private static final String CLIENT_REVOCATION_EVENT_TYPE = "APPLE_CLIENT_REVOCATION";

    private final UserRepository userRepository;
    private final SubscriptionEventRepository subscriptionEventRepository;

    /**
     * Rapport envoye par l'app iOS apres lecture de {@code Transaction.currentEntitlements}
     * (StoreKit 2 pur, aucun SDK tiers). Authentifie via le JWT ({@code userId} ne vient jamais
     * du corps de la requete, cf. {@code MeController}), donc un utilisateur ne peut rapporter
     * que POUR LUI-MEME — mais **pas** verifie cryptographiquement contre Apple : un client
     * compromis pourrait mentir sur son propre acces. Durcissement prevu : App Store Server
     * Notifications V2.
     * <p>
     * Idempotent : {@code transactionId} porte l'unicite. Un renouvellement StoreKit cree un
     * nouveau {@code Transaction.id} ; un rappel du meme achat re-soumet le meme id et est
     * silencieusement ignore.
     */
    @Transactional
    public boolean applyClientReportedApplePurchase(UUID userId, AppleSubscriptionReportRequest report) {
        SubscriptionStatus newStatus = report.trialPeriod() ? SubscriptionStatus.TRIAL : SubscriptionStatus.ACTIVE;
        String eventId = "apple-client:" + report.transactionId();
        return applyTransition(userId, eventId, CLIENT_REPORT_EVENT_TYPE, newStatus, report.expiresAt(), Instant.now());
    }

    /**
     * Remboursement ou revocation, constate par le client (story 07.9).
     * <p>
     * <b>Le trou que ceci ferme.</b> {@code Transaction.currentEntitlements} exclut deja les
     * transactions revoquees, donc l'app arrete d'elle-meme d'accorder l'acces. Mais rien ne le
     * disait au serveur : celui-ci gardait {@code hasActiveAccess} jusqu'a
     * {@code subscriptionExpiresAt}, et la porte de l'app s'ouvre des qu'UNE des deux sources
     * dit oui ({@code PremiumGate}). Quelqu'un qui se faisait rembourser l'annuel conservait
     * donc Premium <b>jusqu'a un an</b>.
     * <p>
     * L'expiration est mise a {@code null}, pas a la date de revocation : un remboursement
     * coupe l'acces MAINTENANT, il ne le laisse pas courir jusqu'a un terme. C'est exactement
     * ce que fait deja le geste manuel du back-office ({@code AdminUserService.markRefunded}),
     * et les deux doivent laisser le compte dans le meme etat.
     * <p>
     * Idempotent, et distinct de l'achat : l'{@code eventId} porte un prefixe propre, sans quoi
     * la revocation d'une transaction deja rapportee serait prise pour un doublon de son achat
     * et ignoree — c'est-a-dire precisement le cas qu'on veut traiter.
     * <p>
     * Limite assumee : un client qui ne se relance jamais ne rapporte jamais. Seul ASSN V2
     * ferme ce dernier ecart, et {@code applyTransition} est pret a l'accueillir.
     */
    @Transactional
    public boolean applyClientReportedRevocation(UUID userId, String transactionId, Instant revokedAt) {
        String eventId = "apple-client-revoked:" + transactionId;
        Instant occurredAt = revokedAt != null ? revokedAt : Instant.now();
        return applyTransition(userId, eventId, CLIENT_REVOCATION_EVENT_TYPE,
                SubscriptionStatus.EXPIRED, null, occurredAt);
    }

    /**
     * Applique une App Store Server Notification V2 deja <b>verifiee</b> (story 07.10).
     * <p>
     * Le contrat est different des deux autres entrees : ici la source est cryptographiquement
     * prouvee - chaine de certificats remontee jusqu'a la racine d'Apple - alors qu'un rapport
     * client est simplement authentifie. C'est la seule source qui apprend au serveur ce qui se
     * passe <b>quand l'app ne tourne pas</b> : renouvellements, remboursements, periodes de
     * grace.
     * <p>
     * La verification appartient a {@code AppleServerNotificationService} et pas a cette
     * methode : ce service ne connait ni JWS ni x5c, et il ne doit pas commencer. Il applique
     * une transition, quelle qu'en soit l'origine.
     */
    @Transactional
    public boolean applyServerNotification(UUID userId, String eventId, String eventType,
                                           SubscriptionStatus newStatus,
                                           Instant expiresAt, Instant occurredAt) {
        return applyTransition(userId, eventId, eventType, newStatus, expiresAt, occurredAt);
    }

    /**
     * Coeur transactionnel partage par toutes les sources d'evenements : dedoublonnage par
     * {@code eventId}, garde anti-desordre, ecriture de {@code User} + trace {@link SubscriptionEvent}.
     */
    private boolean applyTransition(
            UUID userId, String eventId, String eventType,
            SubscriptionStatus newStatus, Instant expiresAt, Instant occurredAt
    ) {
        if (subscriptionEventRepository.existsByEventId(eventId)) {
            log.debug("Evenement d'abonnement {} deja traite, ignore", eventId);
            return false;
        }

        Optional<User> maybeUser = userRepository.findById(userId);
        if (maybeUser.isEmpty()) {
            // Peut arriver legitimement : compte supprime (RGPD) alors qu'un evenement etait
            // encore en vol. On acquitte sans rien faire plutot que de recreer un compte.
            log.warn("Evenement d'abonnement recu pour un utilisateur inconnu ({}), ignore", userId);
            return false;
        }

        User user = maybeUser.get();

        // Les evenements peuvent arriver dans le desordre (rejeux, latence reseau, deux
        // appareils). Appliquer un evenement plus ancien que le dernier deja traite ferait
        // "revivre" un abonnement expire : un EXPIRATION suivi d'un RENEWAL retardataire mais
        // anterieur redonnerait l'acces a un compte qui ne paie plus.
        if (isStale(userId, occurredAt)) {
            log.warn("Evenement d'abonnement {} anterieur au dernier evenement traite pour l'utilisateur {}, ignore",
                    eventId, userId);
            return false;
        }

        user.setSubscriptionStatus(newStatus);
        user.setSubscriptionExpiresAt(expiresAt);
        userRepository.save(user);

        SubscriptionEvent trace = SubscriptionEvent.builder()
                .id(UUID.randomUUID())
                .user(user)
                .eventId(eventId)
                .eventType(eventType)
                .resultingStatus(newStatus)
                .expiresAt(expiresAt)
                .occurredAt(occurredAt)
                .receivedAt(Instant.now())
                .build();

        try {
            subscriptionEventRepository.save(trace);
        } catch (DataIntegrityViolationException e) {
            // Deux livraisons du meme evenement traitees en parallele : la contrainte d'unicite
            // sur event_id tranche. Le perdant abandonne, l'etat reste coherent.
            //
            // L'exception est RELANCEE volontairement : apres une violation de contrainte, le
            // contexte de persistance est inutilisable et la transaction doit etre annulee.
            // C'est l'appelant qui la rattrape pour repondre 2xx (contrat webhook : "401 ou
            // 200, jamais autre chose"), ou la laisse remonter (rapport client : un doublon
            // concurrent n'est pas une erreur cote app iOS non plus) - la rattraper ici ne
            // ferait que deplacer l'echec au commit.
            log.info("Evenement d'abonnement {} insere concurremment, transaction abandonnee", eventId);
            throw e;
        }

        log.info("Abonnement mis a jour (userId={}, evenement={}, statut={})", userId, eventType, newStatus);
        return true;
    }

    /**
     * Prolonge manuellement l'acces d'un compte (story 09.5 - geste commercial du support).
     * Passe deliberement par le meme champ que les webhooks : il ne doit exister qu'une
     * seule notion de "jusqu'a quand ce compte a acces".
     */
    @Transactional
    public User extendAccess(UUID userId, int additionalDays) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Compte introuvable"));

        // Un compte deja expire repart de maintenant, pas d'une date passee : sinon prolonger
        // de 7 jours un essai termine depuis 2 semaines n'aurait aucun effet visible.
        Instant base = user.getSubscriptionExpiresAt() == null || user.getSubscriptionExpiresAt().isBefore(Instant.now())
                ? Instant.now()
                : user.getSubscriptionExpiresAt();

        user.setSubscriptionExpiresAt(base.plusSeconds((long) additionalDays * 86_400));
        user.setSubscriptionStatus(SubscriptionStatus.TRIAL);
        return userRepository.save(user);
    }

    private boolean isStale(UUID userId, Instant occurredAt) {
        if (occurredAt == null) {
            // Sans horodatage exploitable, on ne peut pas ordonner : on applique l'evenement
            // plutot que de le perdre.
            return false;
        }
        return subscriptionEventRepository.findFirstByUser_IdOrderByOccurredAtDesc(userId)
                .map(SubscriptionEvent::getOccurredAt)
                .filter(last -> occurredAt.isBefore(last))
                .isPresent();
    }

}
