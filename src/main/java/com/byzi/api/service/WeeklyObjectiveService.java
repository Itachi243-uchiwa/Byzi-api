package com.byzi.api.service;

import com.byzi.api.domain.User;
import com.byzi.api.domain.WeeklyObjective;
import com.byzi.api.dto.objective.WeeklyObjectiveRequest;
import com.byzi.api.dto.objective.WeeklyObjectiveResponse;
import com.byzi.api.exception.ResourceNotFoundException;
import com.byzi.api.mapper.WeeklyObjectiveMapper;
import com.byzi.api.repository.UserRepository;
import com.byzi.api.repository.WeeklyObjectiveRepository;
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
 * Objectifs hebdomadaires (backlog app 0ter T10). Meme contrat de synchronisation
 * qu'{@link AppBlockRuleService} et {@link TodoTaskService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeeklyObjectiveService {

    private final WeeklyObjectiveRepository weeklyObjectiveRepository;
    private final UserRepository userRepository;
    private final WeeklyObjectiveMapper mapper;
    private final ConflictResolutionStrategy conflictResolutionStrategy;

    @Transactional
    public WeeklyObjectiveResponse upsert(UUID id, UUID userId, WeeklyObjectiveRequest request) {
        Optional<WeeklyObjective> existing = weeklyObjectiveRepository.findByIdAndUser_Id(id, userId);

        if (existing.isPresent()) {
            WeeklyObjective current = existing.get();
            if (!conflictResolutionStrategy.shouldApplyIncoming(request.clientUpdatedAt(), current.getUpdatedAt())) {
                return mapper.toResponse(current);
            }
            current.setDeletedAt(null);
            mapper.applyUpdate(current, request);
            return mapper.toResponse(weeklyObjectiveRepository.save(current));
        }

        User owner = userRepository.getReferenceById(userId);
        WeeklyObjective created = mapper.toNewEntity(resolveIdForNewObjective(id, userId), owner, request);
        return mapper.toResponse(weeklyObjectiveRepository.save(created));
    }

    @Transactional(readOnly = true)
    public WeeklyObjectiveResponse get(UUID id, UUID userId) {
        return weeklyObjectiveRepository.findByIdAndUser_IdAndDeletedAtIsNull(id, userId)
                .map(mapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Objectif introuvable"));
    }

    @Transactional(readOnly = true)
    public Page<WeeklyObjectiveResponse> list(UUID userId, Instant updatedSince, Pageable pageable) {
        if (updatedSince == null) {
            return weeklyObjectiveRepository
                    .findAllByUser_IdAndDeletedAtIsNull(userId, pageable)
                    .map(mapper::toResponse);
        }
        return weeklyObjectiveRepository
                .findAllByUser_IdAndUpdatedAtGreaterThanEqual(userId, updatedSince, SyncPageables.forDelta(pageable))
                .map(mapper::toResponse);
    }

    /** Suppression LOGIQUE : l'objectif sort des listes mais reste visible dans le delta. */
    @Transactional
    public void delete(UUID id, UUID userId) {
        WeeklyObjective objective = weeklyObjectiveRepository.findByIdAndUser_IdAndDeletedAtIsNull(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Objectif introuvable"));

        objective.setDeletedAt(Instant.now());
        weeklyObjectiveRepository.save(objective);
    }

    /** Meme protection IDOR que sur les autres ressources synchronisees (BLOQ-01). */
    private UUID resolveIdForNewObjective(UUID requestedId, UUID userId) {
        if (weeklyObjectiveRepository.existsById(requestedId)) {
            UUID reassigned = UUID.randomUUID();
            log.info("Id d'objectif {} deja utilise par un autre compte : l'objectif de "
                    + "l'utilisateur {} est cree sous l'id {}", requestedId, userId, reassigned);
            return reassigned;
        }
        return requestedId;
    }
}
