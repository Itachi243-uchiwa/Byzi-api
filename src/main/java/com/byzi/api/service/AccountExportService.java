package com.byzi.api.service;

import com.byzi.api.domain.User;
import com.byzi.api.dto.account.AccountExportProfile;
import com.byzi.api.dto.account.AccountExportResponse;
import com.byzi.api.dto.account.SubscriptionEventExport;
import com.byzi.api.exception.ResourceNotFoundException;
import com.byzi.api.mapper.AppBlockRuleMapper;
import com.byzi.api.mapper.FocusSessionMapper;
import com.byzi.api.mapper.StreakRecordMapper;
import com.byzi.api.repository.AppBlockRuleRepository;
import com.byzi.api.repository.FocusSessionRepository;
import com.byzi.api.repository.StreakRecordRepository;
import com.byzi.api.repository.SubscriptionEventRepository;
import com.byzi.api.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Droit a la portabilite (art. 20 RGPD - MANQUE-03 de l'audit backend) : DELETE
 * /api/v1/account couvre deja le droit a l'effacement (AccountDeletionService), mais rien ne
 * couvrait jusqu'ici le droit d'obtenir une copie structuree des donnees. Ce service assemble
 * cette copie pour l'utilisateur courant UNIQUEMENT - le userId provient toujours du JWT
 * (SecurityUtils cote controller), jamais d'un parametre de requete (defense IDOR de fond du
 * projet).
 */
@Service
@RequiredArgsConstructor
public class AccountExportService {

    /**
     * Taille de page utilisee pour parcourir sessions/streaks/regles de blocage pendant
     * l'assemblage de l'export. Un utilisateur actif peut accumuler plusieurs centaines de
     * lignes, et selectionData a lui seul peut peser jusqu'a 200 000 caracteres
     * (AppBlockRuleRequest) : charger tout d'un coup exposerait le meme risque que MOY-05 de
     * l'audit (une reponse de plusieurs centaines de Mo capable de faire tomber la JVM). Cette
     * valeur n'est PAS pilotee par le client (contrairement au ?size= de la pagination
     * publique) : elle borne uniquement chaque aller-retour base pendant la construction
     * interne de l'export, qui reste malgre tout renvoye en une seule reponse JSON complete -
     * c'est le perimetre du droit a la portabilite qui l'exige (une copie complete, pas
     * paginee, des donnees de la personne).
     */
    private static final int EXPORT_PAGE_SIZE = 200;

    private final UserRepository userRepository;
    private final FocusSessionRepository focusSessionRepository;
    private final StreakRecordRepository streakRecordRepository;
    private final AppBlockRuleRepository appBlockRuleRepository;
    private final SubscriptionEventRepository subscriptionEventRepository;
    private final FocusSessionMapper focusSessionMapper;
    private final StreakRecordMapper streakRecordMapper;
    private final AppBlockRuleMapper appBlockRuleMapper;

    @Transactional(readOnly = true)
    public AccountExportResponse export(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Compte introuvable"));

        // Les tombstones (deletedAt renseigne) sont volontairement EXCLUS de l'export. Ce sont
        // des ressources que l'utilisateur a supprimees : elles ne subsistent en base que le
        // temps de propager la suppression a ses autres appareils, et les lui restituer dans un
        // export de portabilite reviendrait a lui rendre des donnees qu'il pense effacees.
        return new AccountExportResponse(
                toProfile(user),
                loadAll(focusSessionRepository::findAllByUser_IdAndDeletedAtIsNullOrderByStartedAtDesc, userId,
                        focusSessionMapper::toResponse),
                loadAll(streakRecordRepository::findAllByUser_IdAndDeletedAtIsNullOrderByDayDesc, userId,
                        streakRecordMapper::toResponse),
                loadAll(appBlockRuleRepository::findAllByUser_IdAndDeletedAtIsNull, userId,
                        appBlockRuleMapper::toResponse),
                subscriptionHistory(userId),
                Instant.now());
    }

    private AccountExportProfile toProfile(User user) {
        return new AccountExportProfile(
                user.getId(),
                user.getAppleSub(),
                user.getEmail(),
                user.getSubscriptionStatus(),
                user.getSubscriptionExpiresAt(),
                user.getCreatedAt(),
                user.getLastLoginAt());
    }

    /**
     * Evenements d'abonnement de l'utilisateur. Contrairement aux sessions/streaks/regles,
     * SubscriptionEventRepository n'expose pas de variante paginee (hors perimetre de ce lot :
     * ce depot appartient au lot abonnements). C'est acceptable ici sans y toucher : un compte
     * accumule au plus quelques dizaines d'evenements RevenueCat sur toute sa duree de vie (une
     * ligne par transition d'abonnement), plusieurs ordres de grandeur en dessous du volume de
     * sessions de focus qui justifie le paginage ci-dessus.
     */
    private List<SubscriptionEventExport> subscriptionHistory(UUID userId) {
        return subscriptionEventRepository.findAllByUser_IdOrderByOccurredAtDesc(userId).stream()
                .map(event -> new SubscriptionEventExport(
                        event.getEventType(),
                        event.getResultingStatus(),
                        event.getExpiresAt(),
                        event.getOccurredAt()))
                .toList();
    }

    /**
     * Parcourt un repository paginable page par page (taille bornee par EXPORT_PAGE_SIZE) et
     * accumule les DTO resultants. Aucune requete ne charge plus de EXPORT_PAGE_SIZE lignes a
     * la fois, quelle que soit la taille reelle des donnees de l'utilisateur.
     */
    private <E, R> List<R> loadAll(
            BiFunction<UUID, Pageable, Page<E>> pageFetcher, UUID userId, Function<E, R> toDto) {
        List<R> results = new ArrayList<>();
        Page<E> current;
        int pageIndex = 0;
        do {
            current = pageFetcher.apply(userId, PageRequest.of(pageIndex, EXPORT_PAGE_SIZE));
            current.getContent().stream().map(toDto).forEach(results::add);
            pageIndex++;
        } while (!current.isLast());
        return results;
    }
}
