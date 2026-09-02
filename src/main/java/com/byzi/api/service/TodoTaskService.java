package com.byzi.api.service;

import com.byzi.api.domain.TodoTask;
import com.byzi.api.domain.User;
import com.byzi.api.dto.todo.TodoTaskRequest;
import com.byzi.api.dto.todo.TodoTaskResponse;
import com.byzi.api.exception.ResourceNotFoundException;
import com.byzi.api.mapper.TodoTaskMapper;
import com.byzi.api.repository.TodoTaskRepository;
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
 * To-do list hebdomadaire (backlog app 0ter T9). Calque exact du contrat de synchronisation
 * d'{@link AppBlockRuleService} : upsert idempotent, last-write-wins, delta par updatedAt,
 * suppression logique.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TodoTaskService {

    private final TodoTaskRepository todoTaskRepository;
    private final UserRepository userRepository;
    private final TodoTaskMapper mapper;
    private final ConflictResolutionStrategy conflictResolutionStrategy;

    /** Regle de resurrection identique aux autres ressources : une suppression est une ecriture. */
    @Transactional
    public TodoTaskResponse upsert(UUID id, UUID userId, TodoTaskRequest request) {
        Optional<TodoTask> existing = todoTaskRepository.findByIdAndUser_Id(id, userId);

        if (existing.isPresent()) {
            TodoTask current = existing.get();
            if (!conflictResolutionStrategy.shouldApplyIncoming(request.clientUpdatedAt(), current.getUpdatedAt())) {
                return mapper.toResponse(current);
            }
            current.setDeletedAt(null);
            mapper.applyUpdate(current, request);
            return mapper.toResponse(todoTaskRepository.save(current));
        }

        User owner = userRepository.getReferenceById(userId);
        TodoTask created = mapper.toNewEntity(resolveIdForNewTask(id, userId), owner, request);
        return mapper.toResponse(todoTaskRepository.save(created));
    }

    @Transactional(readOnly = true)
    public TodoTaskResponse get(UUID id, UUID userId) {
        return todoTaskRepository.findByIdAndUser_IdAndDeletedAtIsNull(id, userId)
                .map(mapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Tache introuvable"));
    }

    /**
     * @param updatedSince quand il est fourni, bascule en mode delta (tombstones compris,
     *                     tries par updatedAt croissant).
     */
    @Transactional(readOnly = true)
    public Page<TodoTaskResponse> list(UUID userId, Instant updatedSince, Pageable pageable) {
        if (updatedSince == null) {
            return todoTaskRepository
                    .findAllByUser_IdAndDeletedAtIsNull(userId, pageable)
                    .map(mapper::toResponse);
        }
        return todoTaskRepository
                .findAllByUser_IdAndUpdatedAtGreaterThanEqual(userId, updatedSince, SyncPageables.forDelta(pageable))
                .map(mapper::toResponse);
    }

    /** Suppression LOGIQUE : la tache sort des listes mais reste visible dans le delta. */
    @Transactional
    public void delete(UUID id, UUID userId) {
        TodoTask task = todoTaskRepository.findByIdAndUser_IdAndDeletedAtIsNull(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Tache introuvable"));

        task.setDeletedAt(Instant.now());
        todoTaskRepository.save(task);
    }

    /**
     * Meme protection IDOR que sur les regles de blocage (BLOQ-01) : sans elle, un PUT portant
     * l'id d'une tache appartenant a un autre compte declencherait un merge Hibernate qui
     * ecraserait le contenu de la tache de la victime.
     */
    private UUID resolveIdForNewTask(UUID requestedId, UUID userId) {
        if (todoTaskRepository.existsById(requestedId)) {
            UUID reassigned = UUID.randomUUID();
            log.info("Id de tache {} deja utilise par un autre compte : la tache de l'utilisateur "
                    + "{} est creee sous l'id {}", requestedId, userId, reassigned);
            return reassigned;
        }
        return requestedId;
    }
}
