package com.byzi.api.service;

import com.byzi.api.domain.AppBlockRule;
import com.byzi.api.domain.User;
import com.byzi.api.dto.blockrule.AppBlockRuleRequest;
import com.byzi.api.dto.blockrule.AppBlockRuleResponse;
import com.byzi.api.exception.ResourceNotFoundException;
import com.byzi.api.mapper.AppBlockRuleMapper;
import com.byzi.api.repository.AppBlockRuleRepository;
import com.byzi.api.repository.UserRepository;
import com.byzi.api.service.sync.ConflictResolutionStrategy;
import com.byzi.api.service.sync.SyncPageables;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Le champ selectionData est un blob opaque que le serveur ne decode JAMAIS : il transite
 * tel quel, y compris dans les reponses du delta.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppBlockRuleService {

    private final AppBlockRuleRepository appBlockRuleRepository;
    private final UserRepository userRepository;
    private final AppBlockRuleMapper mapper;
    private final ConflictResolutionStrategy conflictResolutionStrategy;

    /**
     * Regle de resurrection identique a celle des sessions de focus : une suppression est une
     * ecriture comme une autre, tranchee par le last-write-wins (cf. FocusSessionService).
     */
    @Transactional
    public AppBlockRuleResponse upsert(UUID id, UUID userId, AppBlockRuleRequest request) {
        Optional<AppBlockRule> existing = appBlockRuleRepository.findByIdAndUser_Id(id, userId);

        if (existing.isPresent()) {
            AppBlockRule current = existing.get();
            if (!conflictResolutionStrategy.shouldApplyIncoming(request.clientUpdatedAt(), current.getUpdatedAt())) {
                return mapper.toResponse(current);
            }
            current.setDeletedAt(null);
            mapper.applyUpdate(current, request);
            return mapper.toResponse(appBlockRuleRepository.save(current));
        }

        User owner = userRepository.getReferenceById(userId);
        AppBlockRule created = mapper.toNewEntity(resolveIdForNewRule(id, userId), owner, request);
        return mapper.toResponse(appBlockRuleRepository.save(created));
    }

    @Transactional(readOnly = true)
    public AppBlockRuleResponse get(UUID id, UUID userId) {
        return appBlockRuleRepository.findByIdAndUser_IdAndDeletedAtIsNull(id, userId)
                .map(mapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Regle de blocage introuvable"));
    }

    /**
     * @param updatedSince quand il est fourni, bascule en mode delta (tombstones compris,
     *                     tries par updatedAt croissant). Sans lui, liste d'affichage
     *                     ordinaire, dont les regles supprimees sont exclues.
     */
    @Transactional(readOnly = true)
    public Page<AppBlockRuleResponse> list(UUID userId, Instant updatedSince, Pageable pageable) {
        if (updatedSince == null) {
            return appBlockRuleRepository
                    .findAllByUser_IdAndDeletedAtIsNull(userId, pageable)
                    .map(mapper::toResponse);
        }
        return appBlockRuleRepository
                .findAllByUser_IdAndUpdatedAtGreaterThanEqual(userId, updatedSince, SyncPageables.forDelta(pageable))
                .map(mapper::toResponse);
    }

    /** Suppression LOGIQUE, pour les raisons detaillees dans {@link FocusSessionService#delete}. */
    @Transactional
    public void delete(UUID id, UUID userId) {
        AppBlockRule rule = appBlockRuleRepository.findByIdAndUser_IdAndDeletedAtIsNull(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Regle de blocage introuvable"));

        rule.setDeletedAt(Instant.now());
        appBlockRuleRepository.save(rule);
    }

    /**
     * Meme protection que pour les sessions de focus (BLOQ-01). Sans elle, un PUT portant l'id
     * d'une regle appartenant a un autre compte ne produisait pas une erreur mais un merge
     * Hibernate, qui remplacait le contenu de la regle de la victime par celui de l'appelant.
     */
    private UUID resolveIdForNewRule(UUID requestedId, UUID userId) {
        if (appBlockRuleRepository.existsById(requestedId)) {
            UUID reassigned = UUID.randomUUID();
            log.info("Id de regle de blocage {} deja utilise par un autre compte : la regle de "
                    + "l'utilisateur {} est creee sous l'id {}", requestedId, userId, reassigned);
            return reassigned;
        }
        return requestedId;
    }
}
