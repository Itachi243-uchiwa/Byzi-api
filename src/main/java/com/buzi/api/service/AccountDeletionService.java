package com.buzi.api.service;

import com.buzi.api.exception.ResourceNotFoundException;
import com.buzi.api.repository.FocusSessionRepository;
import com.buzi.api.repository.UserRepository;
import com.buzi.api.security.jwt.RefreshTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Suppression definitive d'un compte Byzi, conforme App Store Review Guideline 5.1.1(v)
 * (Fiche technique, section 33) : la suppression doit etre possible directement dans l'app,
 * pas seulement par email/support.
 * <p>
 * Ordre de suppression et atomicite : tout se passe dans UNE seule transaction. Si une etape
 * echoue, rien n'est supprime (coherence garantie par le rollback Spring). La suppression de
 * la ligne User declenche la purge en cascade de toutes les donnees enfants (focus_sessions,
 * streak_records, app_block_rules, refresh_tokens) via les contraintes "on delete cascade"
 * du schema (V1__init_schema.sql) - une seule instruction SQL, donc pas de risque d'oubli
 * d'une table lors d'une future evolution du schema.
 * <p>
 * Aucune donnee personnelle (email, appleSub) n'est loggee : seul un identifiant technique
 * et des compteurs sont journalises, suffisants pour la tracabilite sans reintroduire
 * de PII dans les logs (OWASP A09 / RGPD - minimisation des donnees dans les journaux).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountDeletionService {

    private final UserRepository userRepository;
    // Conserve uniquement pour le compteur journalise : la purge elle-meme passe par le cascade.
    private final FocusSessionRepository focusSessionRepository;
    private final RefreshTokenService refreshTokenService;


    @Transactional
    public void deleteAccount(UUID userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("Compte introuvable");
        }

        long sessionsCount = focusSessionRepository.countByUser_Id(userId);

        refreshTokenService.revokeAllForUser(userId);
        // Une seule instruction : le "on delete cascade" du schema purge focus_sessions,
        // streak_records, app_block_rules et refresh_tokens. Supprimer les enfants un par un
        // ici serait a la fois redondant et fragile - c'est d'ailleurs ce que faisait la
        // version precedente, qui passait un userId aux deleteById() des repositories enfants
        // (dont la cle est l'id de la session / du streak / de la regle) et ne supprimait donc
        // rien du tout.
        userRepository.deleteById(userId);


        log.info("Compte supprime (userId={}, sessionsPurgees={}) - conformite RGPD / guideline 5.1.1(v)",
                userId, sessionsCount);
    }
}