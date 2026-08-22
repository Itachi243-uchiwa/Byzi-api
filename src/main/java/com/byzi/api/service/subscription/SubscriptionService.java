package com.byzi.api.service.subscription;

import com.byzi.api.domain.SubscriptionEvent;
import com.byzi.api.domain.SubscriptionStatus;
import com.byzi.api.domain.User;
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
 * Applique les transitions d'abonnement recues de RevenueCat (EPIC-07.5).
 * <p>
 * Le serveur est la SEULE source de verite de l'etat d'abonnement : l'app iOS ne fait que le
 * lire. C'est l'exigence explicite de l'EPIC-07 ("l'etat d'abonnement n'est jamais deduit
 * d'une date locale sur l'appareil"), qui interdit de faire confiance a une expiration
 * calculee cote client - trivialement contournable en reculant l'horloge systeme.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

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
        if (subscriptionEventRepository.existsByEventId(event.id())) {
            log.debug("Evenement RevenueCat {} deja traite, ignore", event.id());
            return false;
        }

        Optional<SubscriptionStatus> newStatus = eventMapper.toStatus(event.type(), event.periodType());
        if (newStatus.isEmpty()) {
            log.info("Evenement RevenueCat de type '{}' sans effet sur l'abonnement, ignore", event.type());
            return false;
        }

        UUID userId = parseUserId(event.appUserId());
        if (userId == null) {
            return false;
        }

        Optional<User> maybeUser = userRepository.findById(userId);
        if (maybeUser.isEmpty()) {
            // Peut arriver legitimement : compte supprime (RGPD) alors qu'un webhook etait
            // encore en vol. On acquitte sans rien faire plutot que de recreer un compte.
            log.warn("Webhook RevenueCat recu pour un utilisateur inconnu ({}), ignore", userId);
            return false;
        }

        User user = maybeUser.get();
        // occurred_at est not null en base : a defaut d'horodatage RevenueCat, la date de
        // reception est la meilleure approximation disponible.
        Instant occurredAt = Optional.ofNullable(toInstant(event.eventTimestampMs())).orElseGet(Instant::now);
        Instant expiresAt = toInstant(event.expirationAtMs());

        // Les webhooks peuvent arriver dans le desordre (rejeux, latence reseau). Appliquer un
        // evenement plus ancien que le dernier deja traite ferait "revivre" un abonnement
        // expire : un EXPIRATION suivi d'un RENEWAL retardataire mais anterieur redonnerait
        // l'acces a un compte qui ne paie plus.
        if (isStale(userId, occurredAt)) {
            log.warn("Evenement RevenueCat {} anterieur au dernier evenement traite pour l'utilisateur {}, ignore",
                    event.id(), userId);
            return false;
        }

        user.setSubscriptionStatus(newStatus.get());
        user.setSubscriptionExpiresAt(expiresAt);
        userRepository.save(user);

        SubscriptionEvent trace = SubscriptionEvent.builder()
                .id(UUID.randomUUID())
                .user(user)
                .eventId(event.id())
                .eventType(event.type())
                .resultingStatus(newStatus.get())
                .expiresAt(expiresAt)
                .occurredAt(occurredAt)
                .receivedAt(Instant.now())
                .build();

        try {
            subscriptionEventRepository.save(trace);
        } catch (DataIntegrityViolationException e) {
            // Deux livraisons du meme webhook traitees en parallele : la contrainte d'unicite
            // sur event_id tranche. Le perdant abandonne, l'etat reste coherent.
            //
            // L'exception est RELANCEE volontairement : apres une violation de contrainte, le
            // contexte de persistance est inutilisable et la transaction doit etre annulee.
            // C'est le controller qui la rattrape pour repondre 200 a RevenueCat, conformement
            // au contrat "401 ou 200, jamais autre chose" - la rattraper ici ne ferait que
            // deplacer l'echec au commit.
            log.info("Evenement RevenueCat {} insere concurremment, transaction abandonnee", event.id());
            throw e;
        }

        log.info("Abonnement mis a jour (userId={}, evenement={}, statut={})",
                userId, event.type(), newStatus.get());
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
