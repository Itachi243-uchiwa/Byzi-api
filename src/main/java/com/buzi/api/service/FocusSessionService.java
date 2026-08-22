package com.buzi.api.service;

import com.buzi.api.domain.FocusSession;
import com.buzi.api.domain.User;
import com.buzi.api.dto.session.FocusSessionRequest;
import com.buzi.api.dto.session.FocusSessionResponse;
import com.buzi.api.exception.ResourceNotFoundException;
import com.buzi.api.mapper.FocusSessionMapper;
import com.buzi.api.repository.FocusSessionRepository;
import com.buzi.api.repository.UserRepository;
import com.buzi.api.service.sync.ConflictResolutionStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Le userId provient TOUJOURS du JWT (via SecurityUtils dans le controller), jamais du corps
 * de la requete, et il est pousse jusque dans la clause where de chaque requete : une session
 * appartenant a quelqu'un d'autre est donc invisible, et non pas "visible mais refusee".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FocusSessionService {

    private final FocusSessionRepository focusSessionRepository;
    private final UserRepository userRepository;
    private final FocusSessionMapper mapper;
    private final ConflictResolutionStrategy conflictResolutionStrategy;

    @Transactional
    public FocusSessionResponse upsert(UUID id, UUID userId, FocusSessionRequest request) {
        Optional<FocusSession> existing = focusSessionRepository.findByIdAndUser_Id(id, userId);

        if (existing.isPresent()) {
            FocusSession current = existing.get();
            if (!conflictResolutionStrategy.shouldApplyIncoming(request.clientUpdatedAt(), current.getUpdatedAt())) {
                // Ecriture plus ancienne que ce que le serveur detient deja : on renvoie l'etat
                // canonique sans le degrader (last-write-wins).
                return mapper.toResponse(current);
            }
            mapper.applyUpdate(current, request);
            return mapper.toResponse(focusSessionRepository.save(current));
        }

        User owner = userRepository.getReferenceById(userId);
        FocusSession created = mapper.toNewEntity(resolveIdForNewSession(id, userId), owner, request);
        return mapper.toResponse(focusSessionRepository.save(created));
    }

    @Transactional(readOnly = true)
    public FocusSessionResponse get(UUID id, UUID userId) {
        return focusSessionRepository.findByIdAndUser_Id(id, userId)
                .map(mapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Session de focus introuvable"));
    }

    @Transactional(readOnly = true)
    public Page<FocusSessionResponse> list(UUID userId, Pageable pageable) {
        return focusSessionRepository.findAllByUser_IdOrderByStartedAtDesc(userId, pageable)
                .map(mapper::toResponse);
    }

    @Transactional
    public void delete(UUID id, UUID userId) {
        long deleted = focusSessionRepository.deleteByIdAndUser_Id(id, userId);
        if (deleted == 0) {
            // Inexistante ou appartenant a un autre utilisateur : meme reponse dans les deux cas.
            throw new ResourceNotFoundException("Session de focus introuvable");
        }
    }

    /**
     * L'id d'une session est choisi par le client (il vient de SwiftData) pour que la synchro
     * soit idempotente : rejouer le meme PUT ne doit pas creer de doublon.
     * <p>
     * Consequence : deux appareils de deux utilisateurs differents peuvent proposer le meme
     * UUID. Comme cet id est la cle primaire, on ne peut ni ecraser la ligne de l'autre
     * utilisateur (ce serait exactement l'IDOR que la story 01.4 interdit) ni la reutiliser.
     * On alloue alors un id cote serveur : l'appelant obtient bien SA session, et celle de
     * l'autre utilisateur reste intacte. Le client recupere l'id reel dans la reponse.
     */
    private UUID resolveIdForNewSession(UUID requestedId, UUID userId) {
        if (focusSessionRepository.existsById(requestedId)) {
            UUID reassigned = UUID.randomUUID();
            log.info("Id de session {} deja utilise par un autre compte : la session de l'utilisateur {} "
                    + "est creee sous l'id {}", requestedId, userId, reassigned);
            return reassigned;
        }
        return requestedId;
    }
}
