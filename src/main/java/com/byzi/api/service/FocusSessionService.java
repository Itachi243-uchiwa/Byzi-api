package com.byzi.api.service;

import com.byzi.api.domain.FocusSession;
import com.byzi.api.domain.User;
import com.byzi.api.dto.session.FocusSessionRequest;
import com.byzi.api.dto.session.FocusSessionResponse;
import com.byzi.api.exception.ResourceNotFoundException;
import com.byzi.api.mapper.FocusSessionMapper;
import com.byzi.api.repository.FocusSessionRepository;
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

    /**
     * Contrat de l'upsert face a une ressource supprimee (MANQUE-02) : <b>une suppression est
     * une ecriture comme une autre</b>. Poser un tombstone met a jour updatedAt, si bien que
     * le last-write-wins tranche seul le cas :
     * <ul>
     *   <li>clientUpdatedAt anterieur au tombstone : l'appareil emetteur n'avait pas encore
     *       appris la suppression. Son ecriture est perimee, la ressource reste supprimee.</li>
     *   <li>clientUpdatedAt posterieur au tombstone : l'utilisateur a deliberement recree la
     *       ressource apres coup. Elle est reanimee.</li>
     * </ul>
     * Aucune regle speciale n'est donc necessaire : c'est la meme comparaison que pour une
     * modification ordinaire, ce qui evite au client Swift d'avoir deux comportements a
     * implementer.
     */
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
            current.setDeletedAt(null);
            mapper.applyUpdate(current, request);
            return mapper.toResponse(focusSessionRepository.save(current));
        }

        User owner = userRepository.getReferenceById(userId);
        FocusSession created = mapper.toNewEntity(resolveIdForNewSession(id, userId), owner, request);
        return mapper.toResponse(focusSessionRepository.save(created));
    }

    @Transactional(readOnly = true)
    public FocusSessionResponse get(UUID id, UUID userId) {
        return focusSessionRepository.findByIdAndUser_IdAndDeletedAtIsNull(id, userId)
                .map(mapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Session de focus introuvable"));
    }

    /**
     * @param updatedSince quand il est fourni, bascule en mode delta : renvoie tout ce qui a
     *                     change depuis cet instant, <b>tombstones compris</b>, tries par
     *                     updatedAt croissant. Sans lui, liste d'affichage ordinaire, dont
     *                     les ressources supprimees sont exclues.
     */
    @Transactional(readOnly = true)
    public Page<FocusSessionResponse> list(UUID userId, Instant updatedSince, Pageable pageable) {
        if (updatedSince == null) {
            return focusSessionRepository
                    .findAllByUser_IdAndDeletedAtIsNullOrderByStartedAtDesc(userId, pageable)
                    .map(mapper::toResponse);
        }
        return focusSessionRepository
                .findAllByUser_IdAndUpdatedAtGreaterThanEqual(userId, updatedSince, SyncPageables.forDelta(pageable))
                .map(mapper::toResponse);
    }

    /**
     * Suppression LOGIQUE. Effacer physiquement la ligne la rendrait invisible du delta, et le
     * PUT suivant d'un appareil qui ignore encore la suppression la recreerait (MANQUE-02).
     * <p>
     * Une ressource deja supprimee renvoie 404, au meme titre qu'une ressource inexistante ou
     * appartenant a autrui : du point de vue du client, ces trois situations sont une seule et
     * meme chose, et les distinguer divulguerait l'existence de donnees d'un autre compte.
     */
    @Transactional
    public void delete(UUID id, UUID userId) {
        FocusSession session = focusSessionRepository.findByIdAndUser_IdAndDeletedAtIsNull(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Session de focus introuvable"));

        session.setDeletedAt(Instant.now());
        focusSessionRepository.save(session);
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
