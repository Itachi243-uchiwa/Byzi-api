package com.byzi.api.service;

import com.byzi.api.domain.SubscriptionStatus;
import com.byzi.api.domain.User;
import com.byzi.api.dto.account.MeResponse;
import com.byzi.api.dto.account.UpdateProfileRequest;
import com.byzi.api.exception.ResourceNotFoundException;
import com.byzi.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Expose au client iOS ce que seul le back-office lisait jusqu'ici (HAUT-01 de l'audit
 * backend) : le statut d'abonnement calcule par SubscriptionService. Cf. sa javadoc : "le
 * serveur est la SEULE source de verite de l'etat d'abonnement : l'app iOS ne fait que le
 * lire."
 */
@Service
@RequiredArgsConstructor
public class AccountProfileService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public MeResponse currentProfile(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Compte introuvable"));

        return new MeResponse(
                user.getId(),
                user.getEmail(),
                user.getGivenName(),
                user.getSubscriptionStatus(),
                user.getSubscriptionExpiresAt(),
                hasActiveAccess(user.getSubscriptionStatus(), user.getSubscriptionExpiresAt()));
    }

    /**
     * Met a jour le profil modifiable par l'utilisateur (backlog app 0ter T8). Seul le prenom
     * l'est : cf. {@link UpdateProfileRequest} pour la raison.
     * <p>
     * Le prenom est rogne, et une chaine vide vaut {@code null} - deux representations du
     * meme etat ("pas de prenom") rendraient la comparaison de deltas dependante de laquelle
     * a ete ecrite, exactement comme pour ScheduleDaysConverter.
     */
    @Transactional
    public MeResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Compte introuvable"));

        String trimmed = request.givenName() == null ? null : request.givenName().trim();
        user.setGivenName(trimmed == null || trimmed.isEmpty() ? null : trimmed);
        userRepository.save(user);

        return currentProfile(userId);
    }

    /**
     * Verdict d'acces calcule ICI, cote serveur, et nulle part ailleurs (EPIC-07) : le client
     * Swift consomme hasActiveAccess tel quel, il ne doit jamais recalculer un acces en
     * comparant subscriptionExpiresAt a Date() - exactement la comparaison qu'un utilisateur
     * contourne en reculant l'horloge de l'appareil.
     * <p>
     * Regle retenue :
     * - ACTIVE / TRIAL : acces uniquement si une date d'expiration existe ET n'est pas encore
     *   passee. Un compte fraichement cree est TRIAL par defaut (User.subscriptionStatus) mais
     *   sans subscriptionExpiresAt tant qu'aucun webhook RevenueCat n'a confirme l'essai
     *   (AuthService.createUser ne renseigne jamais ce champ) : il n'a donc PAS acces avant
     *   cette confirmation, coherent avec "seul RevenueCat cree un acces reel".
     * - GRACE_PERIOD : acces accorde INCONDITIONNELLEMENT, sans regarder la date. L'utilisateur
     *   a bien souscrit, seul le prelevement a echoue - c'est la meme convention que
     *   AdminDashboardService.POST_TRIAL_STATUSES, qui traite GRACE_PERIOD comme "converti" au
     *   meme titre qu'ACTIVE.
     * - EXPIRED : jamais d'acces, quelle que soit la date stockee (une date future en base pour
     *   un statut EXPIRED serait une incoherence de donnees, pas un signal a suivre).
     */
    private boolean hasActiveAccess(SubscriptionStatus status, Instant expiresAt) {
        return switch (status) {
            case ACTIVE, TRIAL -> expiresAt != null && expiresAt.isAfter(Instant.now());
            case GRACE_PERIOD -> true;
            case EXPIRED -> false;
        };
    }
}
