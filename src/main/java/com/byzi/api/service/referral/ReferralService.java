package com.byzi.api.service.referral;

import com.byzi.api.domain.ReferralRedemption;
import com.byzi.api.domain.SubscriptionStatus;
import com.byzi.api.domain.User;
import com.byzi.api.dto.referral.ReferralCodeResponse;
import com.byzi.api.dto.referral.ReferralRedeemResponse;
import com.byzi.api.exception.ForbiddenOperationException;
import com.byzi.api.exception.ResourceNotFoundException;
import com.byzi.api.repository.ReferralRedemptionRepository;
import com.byzi.api.repository.UserRepository;
import com.byzi.api.service.subscription.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Parrainage (backlog 10.8) : un code par utilisateur, et des jours d'essai offerts de part
 * et d'autre a chaque utilisation.
 * <p>
 * <b>Ce que ce service refuse d'accorder, et pourquoi.</b> Les jours sont ajoutes en ecrivant
 * {@code subscriptionExpiresAt}, le meme champ que celui pilote par les webhooks RevenueCat.
 * Sur un compte dont l'abonnement est actif chez RevenueCat, cette ecriture serait ecrasee
 * par le webhook suivant : la recompense serait annoncee a l'utilisateur puis disparaitrait
 * silencieusement. Un abonne payant ne recoit donc PAS de jours - ni comme filleul (sa
 * demande est refusee, l'offre s'adressant aux comptes en essai ou expires), ni comme parrain
 * (l'utilisation est enregistree, la recompense non). Accorder ces jours pour de bon suppose
 * de passer par les entitlements promotionnels de l'API RevenueCat, ce que le backend ne fait
 * pas encore.
 * <p>
 * La protection contre les abus se limite ici a "un compte ne peut etre parraine qu'une
 * fois", garantie par une contrainte d'unicite en base. La detection du multi-comptes est une
 * story a part entiere, prevue en V2 (EPIC-22.1).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReferralService {

    /**
     * Alphabet sans 0/O, 1/I/L ni U : les codes sont lus sur une capture d'ecran et recopies
     * a la main. Chaque paire de caracteres confondables se paie en tentatives echouees et en
     * messages au support. U est ecarte en plus des ambigus pour eloigner la generation de
     * mots malencontreux.
     */
    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789".toCharArray();

    private static final int CODE_LENGTH = 6;

    /**
     * Collisions : avec 30 caracteres sur 6 positions, l'espace est de 729 millions de codes.
     * Une poignee de tentatives suffit donc tres largement, et la borne est la pour qu'un
     * epuisement improbable echoue franchement plutot que de boucler sans fin.
     */
    private static final int MAX_GENERATION_ATTEMPTS = 5;

    /** Etats dans lesquels l'acces est paye et pilote par RevenueCat. */
    private static final Set<SubscriptionStatus> PAID_STATUSES =
            Set.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.GRACE_PERIOD);

    private final UserRepository userRepository;
    private final ReferralRedemptionRepository redemptionRepository;
    private final SubscriptionService subscriptionService;
    private final ReferralProperties properties;
    private final SecureRandom random = new SecureRandom();

    /**
     * Code de l'utilisateur, cree au premier appel.
     * <p>
     * Une lecture qui ecrit, donc - assume : generer un code pour chaque compte des
     * l'inscription remplirait la table de codes que l'immense majorite des utilisateurs ne
     * partagera jamais. L'operation reste idempotente du point de vue de l'appelant : le
     * deuxieme appel renvoie le meme code, et c'est ce qui compte, puisque le code a pu etre
     * partage entre-temps.
     */
    @Transactional
    public ReferralCodeResponse myCode(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Compte introuvable"));

        if (user.getReferralCode() == null) {
            user.setReferralCode(generateUniqueCode());
            user = userRepository.save(user);
            log.info("Code de parrainage cree (userId={})", userId);
        }

        return new ReferralCodeResponse(
                user.getReferralCode(),
                redemptionRepository.countByReferrer_Id(userId),
                properties.trialExtensionDays());
    }

    @Transactional
    public ReferralRedeemResponse redeem(UUID referredId, String rawCode) {
        String code = normalize(rawCode);

        User referrer = userRepository.findByReferralCodeIgnoreCase(code)
                .orElseThrow(() -> new ResourceNotFoundException("Code de parrainage inconnu"));

        User referred = userRepository.findById(referredId)
                .orElseThrow(() -> new ResourceNotFoundException("Compte introuvable"));

        if (referrer.getId().equals(referredId)) {
            throw new ForbiddenOperationException("Un code de parrainage ne peut pas etre utilise par son proprietaire");
        }
        if (redemptionRepository.existsByReferred_Id(referredId)) {
            throw new ForbiddenOperationException("Ce compte a deja utilise un code de parrainage");
        }
        if (isPaid(referred)) {
            // Refuse plutot qu'accepte sans effet : le filleul verrait sa demande reussir et
            // n'obtiendrait rien, le webhook RevenueCat suivant reecrivant sa date d'acces.
            throw new ForbiddenOperationException(
                    "L'offre de parrainage s'adresse aux comptes en essai ou expires");
        }

        int days = properties.trialExtensionDays();
        User updatedReferred = subscriptionService.extendAccess(referredId, days);

        boolean referrerRewarded = !isPaid(referrer);
        if (referrerRewarded) {
            subscriptionService.extendAccess(referrer.getId(), days);
        }

        recordRedemption(referrer, referred, code, days, referrerRewarded ? days : 0);

        log.info("Parrainage utilise (parrain={}, filleul={}, jours={}, parrainRecompense={})",
                referrer.getId(), referredId, days, referrerRewarded);

        return new ReferralRedeemResponse(
                days, updatedReferred.getSubscriptionExpiresAt(), referrerRewarded);
    }

    private void recordRedemption(User referrer, User referred, String code,
                                  int referredDays, int referrerDays) {
        try {
            redemptionRepository.save(ReferralRedemption.builder()
                    .id(UUID.randomUUID())
                    .referrer(referrer)
                    .referred(referred)
                    .code(code)
                    .referredDays(referredDays)
                    .referrerDays(referrerDays)
                    .redeemedAt(Instant.now())
                    .build());
        } catch (DataIntegrityViolationException e) {
            // Deux requetes concurrentes du meme filleul : la contrainte d'unicite sur
            // referred_id a tranche. La transaction perdante doit etre annulee entierement -
            // y compris les jours qu'elle venait d'accorder, sans quoi le compte recevrait
            // deux fois la recompense pour une seule ligne d'historique.
            throw new ForbiddenOperationException("Ce compte a deja utilise un code de parrainage", e);
        }
    }

    private boolean isPaid(User user) {
        return PAID_STATUSES.contains(user.getSubscriptionStatus());
    }

    /** Le code est recopie a la main : espaces et casse ne doivent jamais faire echouer. */
    private String normalize(String rawCode) {
        return rawCode == null ? "" : rawCode.trim().replace(" ", "").toUpperCase();
    }

    private String generateUniqueCode() {
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            String candidate = randomCode();
            if (!userRepository.existsByReferralCode(candidate)) {
                return candidate;
            }
        }
        // La contrainte d'unicite en base reste le dernier rempart ; arriver ici signale un
        // probleme bien plus grave qu'une collision (generateur casse, table corrompue).
        throw new IllegalStateException("Impossible de generer un code de parrainage unique");
    }

    private String randomCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return code.toString();
    }
}
