package com.byzi.api.service.admin;

import com.byzi.api.domain.Role;
import com.byzi.api.domain.StreakRecord;
import com.byzi.api.domain.SubscriptionStatus;
import com.byzi.api.domain.User;
import com.byzi.api.dto.admin.AdminUserDetail;
import com.byzi.api.exception.ForbiddenOperationException;
import com.byzi.api.exception.ResourceNotFoundException;
import com.byzi.api.repository.FocusSessionRepository;
import com.byzi.api.repository.StreakRecordRepository;
import com.byzi.api.repository.SubscriptionEventRepository;
import com.byzi.api.repository.UserRepository;
import com.byzi.api.service.AccountDeletionService;
import com.byzi.api.service.subscription.SubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Operations du back-office sur les comptes utilisateurs (stories 09.2, 09.3, 09.5, 09.6).
 * <p>
 * Chaque action mutante ecrit une entree d'audit AVANT de s'executer : si l'action echoue, la
 * tentative reste tracee (l'audit est ecrit dans sa propre transaction, cf.
 * {@link AdminAuditService#record}). Un journal qui ne consignerait que les succes laisserait
 * invisibles les tentatives repetees sur un compte.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private static final int MAX_TRIAL_EXTENSION_DAYS = 90;

    private final UserRepository userRepository;
    private final FocusSessionRepository focusSessionRepository;
    private final StreakRecordRepository streakRecordRepository;
    private final SubscriptionEventRepository subscriptionEventRepository;
    private final SubscriptionService subscriptionService;
    private final AccountDeletionService accountDeletionService;
    private final AdminAuditService auditService;

    /**
     * Deux requetes distinctes plutot qu'une requete unique avec un filtre optionnel : outre
     * le probleme de typage du parametre null cote PostgreSQL (voir
     * {@link UserRepository#findByEmailContainingIgnoreCase}), la liste complete n'a aucune
     * raison de payer un LIKE sur toutes les lignes.
     */
    @Transactional(readOnly = true)
    public Page<User> search(String term, Pageable pageable) {
        if (term == null || term.isBlank()) {
            return userRepository.findAll(pageable);
        }
        return userRepository.findByEmailContainingIgnoreCase(term.trim(), pageable);
    }

    @Transactional(readOnly = true)
    public AdminUserDetail detail(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Compte introuvable"));

        List<LocalDate> goalReachedDays = streakRecordRepository
                .findTop60ByUser_IdAndGoalReachedTrueAndDeletedAtIsNullOrderByDayDesc(userId)
                .stream()
                .map(StreakRecord::getDay)
                .toList();

        return new AdminUserDetail(
                user,
                focusSessionRepository.countByUser_Id(userId),
                currentStreak(goalReachedDays),
                goalReachedDays.stream().limit(14).toList(),
                subscriptionEventRepository.findAllByUser_IdOrderByOccurredAtDesc(userId),
                auditService.forUser(userId));
    }

    /**
     * Story 09.5 - geste commercial : prolonger l'essai d'un compte.
     * <p>
     * Reserve au role finance (story 17.4). L'annotation est portee par le SERVICE et non par
     * le controller : l'action a un effet sur l'acces paye d'un compte, et cet effet doit
     * etre protege quel que soit le point d'entree - y compris un futur endpoint REST ou une
     * tache automatisee. La hierarchie fait qu'un ADMIN complet la satisfait aussi.
     *
     * @throws IllegalArgumentException si la duree demandee est nulle, negative ou
     *         deraisonnable. Une borne haute evite qu'une faute de frappe ("365" au lieu de
     *         "3") n'offre un an d'acces gratuit sans que personne ne s'en apercoive.
     */
    @PreAuthorize("hasRole('ADMIN_FINANCE')")
    @Transactional
    public User extendTrial(UUID userId, int days, UUID adminId, String adminLabel) {
        if (days <= 0 || days > MAX_TRIAL_EXTENSION_DAYS) {
            throw new IllegalArgumentException(
                    "La prolongation doit etre comprise entre 1 et " + MAX_TRIAL_EXTENSION_DAYS + " jours");
        }
        auditService.record(adminId, adminLabel, AdminAuditService.ACTION_EXTEND_TRIAL, userId,
                "Prolongation de " + days + " jour(s)");
        return subscriptionService.extendAccess(userId, days);
    }

    /**
     * Story 09.5 - remboursement traite hors application (App Store). On aligne l'etat local
     * sur la realite : l'acces cesse immediatement.
     * <p>
     * Role finance : acter un remboursement, c'est couper l'acces d'un client qui a paye.
     */
    @PreAuthorize("hasRole('ADMIN_FINANCE')")
    @Transactional
    public User markRefunded(UUID userId, UUID adminId, String adminLabel, String reason) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Compte introuvable"));

        auditService.record(adminId, adminLabel, AdminAuditService.ACTION_MARK_REFUNDED, userId, reason);

        user.setSubscriptionStatus(SubscriptionStatus.EXPIRED);
        user.setSubscriptionExpiresAt(null);
        return userRepository.save(user);
    }

    /**
     * Story 09.6 - suppression RGPD declenchee depuis le back-office.
     * <p>
     * Reserve a l'ADMIN complet, et a lui seul : c'est la seule action du back-office qui soit
     * irreversible et qui detruise des donnees. Ni le support ni la finance n'en ont besoin au
     * quotidien, et un compte supprime par erreur ne se restaure pas.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public void deleteAccount(UUID userId, UUID adminId, String adminLabel) {
        // Audit AVANT suppression : apres, l'email du compte n'existe plus et la trace
        // perdrait toute valeur pour une eventuelle reclamation.
        auditService.record(adminId, adminLabel, AdminAuditService.ACTION_DELETE_ACCOUNT, userId,
                "Suppression definitive du compte (RGPD / guideline 5.1.1(v))");
        accountDeletionService.deleteAccount(userId);
    }

    /**
     * Story 17.4 - attribution d'un role d'administration.
     * <p>
     * Reserve a l'ADMIN complet : distribuer les droits est en soi le droit le plus sensible.
     * Trois garde-fous, chacun contre une facon differente de se tirer une balle dans le pied :
     * <ul>
     *   <li>on ne modifie pas son PROPRE role - c'est la voie ouverte a l'escalade
     *       silencieuse comme a l'auto-exclusion, et rien ne justifie de le faire depuis cette
     *       interface plutot qu'en demandant a un autre administrateur ;</li>
     *   <li>on ne retire pas le dernier ADMIN complet - plus personne ne pourrait alors en
     *       nommer un, et la seule sortie serait une intervention manuelle en base ;</li>
     *   <li>un compte promu sans mot de passe ne pourra pas se connecter au back-office. Ce
     *       n'est pas bloque - la promotion reste valide et le mot de passe peut etre pose
     *       ensuite - mais l'appelant est prevenu par la valeur de retour.</li>
     * </ul>
     *
     * @return true si le compte promu peut effectivement se connecter au back-office.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public boolean changeRole(UUID userId, Role newRole, UUID adminId, String adminLabel) {
        if (userId.equals(adminId)) {
            throw new ForbiddenOperationException("Un administrateur ne peut pas modifier son propre role");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Compte introuvable"));

        if (user.getRole() == Role.ADMIN && newRole != Role.ADMIN
                && userRepository.countByRole(Role.ADMIN) <= 1) {
            throw new ForbiddenOperationException(
                    "Impossible de retirer le dernier administrateur complet");
        }

        auditService.record(adminId, adminLabel, AdminAuditService.ACTION_CHANGE_ROLE, userId,
                "Role " + user.getRole() + " -> " + newRole);

        user.setRole(newRole);
        userRepository.save(user);

        return !newRole.isAdmin() || (user.getPasswordHash() != null && !user.getPasswordHash().isBlank());
    }

    /**
     * Longueur du streak courant : nombre de jours consecutifs avec objectif atteint en
     * remontant depuis aujourd'hui.
     * <p>
     * Un streak dont le dernier jour valide est anterieur a hier est rompu et vaut 0. Hier est
     * tolere comme point de depart : a 9h du matin, une journee en cours sans session encore
     * enregistree ne doit pas afficher un streak a zero au support.
     */
    int currentStreak(List<LocalDate> goalReachedDaysDesc) {
        if (goalReachedDaysDesc.isEmpty()) {
            return 0;
        }
        LocalDate today = LocalDate.now();
        LocalDate mostRecent = goalReachedDaysDesc.get(0);
        if (mostRecent.isBefore(today.minusDays(1))) {
            return 0;
        }

        int streak = 1;
        LocalDate previous = mostRecent;
        for (LocalDate day : goalReachedDaysDesc.subList(1, goalReachedDaysDesc.size())) {
            if (day.equals(previous.minusDays(1))) {
                streak++;
                previous = day;
            } else if (day.equals(previous)) {
                // Doublon defensif : la contrainte uk_streak_user_day l'interdit en base, mais
                // le compter deux fois gonflerait silencieusement le streak si elle sautait.
                continue;
            } else {
                break;
            }
        }
        return streak;
    }
}
