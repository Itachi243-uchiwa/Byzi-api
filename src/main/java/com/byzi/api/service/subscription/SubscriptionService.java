package com.byzi.api.service.subscription;

import com.byzi.api.domain.SubscriptionEvent;
import com.byzi.api.domain.SubscriptionStatus;
import com.byzi.api.domain.User;
import com.byzi.api.dto.subscription.AppleSubscriptionReportRequest;
import com.byzi.api.dto.subscription.RevenueCatWebhookRequest;
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
 * Applique les transitions d'abonnement, recues de RevenueCat ou rapportees par le client iOS
 * (EPIC-07.5).
 * <p>
 * Le serveur est la SEULE source de verite de l'etat d'abonnement : l'app iOS ne fait que le
 * lire (voir {@code AccountProfileService.hasActiveAccess}). C'est l'exigence explicite de
 * l'EPIC-07 ("l'etat d'abonnement n'est jamais deduit d'une date locale sur l'appareil"), qui
 * interdit de faire confiance a une expiration calculee cote client - trivialement contournable
 * en reculant l'horloge systeme. Un rapport client (voir {@link #applyClientReportedApplePurchase})
 * alimente cette meme source de verite, mais n'est pas cryptographiquement verifie comme l'est
 * un webhook RevenueCat : voir sa Javadoc pour la nuance.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private static final String CLIENT_REPORT_EVENT_TYPE = "APPLE_CLIENT_REPORT";

    private final UserRepository userRepository;
    private final SubscriptionEventRepository subscriptionEventRepository;
    private final RevenueCatEventMapper eventMapper;

    /**
     * @return true si l'evenement a modifie l'etat du compte, false s'il a ete ignore
     *         (doublon, type non pertinent, utilisateur inconnu, evenement perime).
     *         Dans les deux cas l'appelant doit repondre 2xx : signaler une erreur a
     *         RevenueCat pour un evenement qu'on a deliberement ignore le ferait rejouer
     *         en boucle.
     */
    @Transactional
    public boolean applyWebhookEvent(RevenueCatWebhookRequest.Event event) {
        Optional<SubscriptionStatus> newStatus = eventMapper.toStatus(event.type(), event.periodType());
        if (newStatus.isEmpty()) {
            log.info("Evenement RevenueCat de type '{}' sans effet sur l'abonnement, ignore", event.type());
            return false;
        }

        UUID userId = parseUserId(event.appUserId());
        if (userId == null) {
            return false;
        }

        // occurred_at est not null en base : a defaut d'horodatage RevenueCat, la date de
        // reception est la meilleure approximation disponible.
        Instant occurredAt = Optional.ofNullable(toInstant(event.eventTimestampMs())).orElseGet(Instant::now);
        Instant expiresAt = toInstant(event.expirationAtMs());

        return applyTransition(userId, event.id(), event.type(), newStatus.get(), expiresAt, occurredAt);
    }

    /**
     * Rapport envoye par l'app iOS apres lecture de {@code Transaction.currentEntitlements}
     * (StoreKit 2 pur, pas de SDK RevenueCat cote client — voir la Javadoc de
     * {@link AppleSubscriptionReportRequest}). Authentifie via le JWT ({@code userId} ne vient
     * jamais du corps de la requete, cf. {@code MeController}), donc un utilisateur ne peut
     * rapporter que POUR LUI-MEME — mais **pas** verifie cryptographiquement contre Apple : un
     * client compromis pourrait mentir sur son propre acces. Accepte pour le lancement en
     * l'absence de compte RevenueCat ; durcissement naturel plus tard (verification serveur de
     * la transaction signee, ou App Store Server Notifications V2).
     * <p>
     * Idempotent comme {@link #applyWebhookEvent} : {@code transactionId} porte l'unicite
     * exactement comme {@code event.id} chez RevenueCat (un renouvellement StoreKit cree un
     * nouveau {@code Transaction.id}, un rappel du meme achat re-soumet le meme id et est
     * silencieusement ignore).
     */
    @Transactional
    public boolean applyClientReportedApplePurchase(UUID userId, AppleSubscriptionReportRequest report) {
        SubscriptionStatus newStatus = report.trialPeriod() ? SubscriptionStatus.TRIAL : SubscriptionStatus.ACTIVE;
        String eventId = "apple-client:" + report.transactionId();
        return applyTransition(userId, eventId, CLIENT_REPORT_EVENT_TYPE, newStatus, report.expiresAt(), Instant.now());
    }

    /**
     * Coeur transactionnel partage par les deux sources d'evenements : dedoublonnage par
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

    private UUID parseUserId(String appUserId) {
        try {
            return UUID.fromString(appUserId);
        } catch (IllegalArgumentException e) {
            // app_user_id anonyme RevenueCat ($RCAnonymousID:...) : l'utilisateur n'a pas
            // encore de compte Byzi, il n'y a rien a mettre a jour.
            log.info("Webhook RevenueCat avec un app_user_id non exploitable, ignore");
            return null;
        }
    }

    private Instant toInstant(Long epochMillis) {
        return epochMillis == null ? null : Instant.ofEpochMilli(epochMillis);
    }
}
