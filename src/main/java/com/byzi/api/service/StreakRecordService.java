package com.byzi.api.service;

import com.byzi.api.domain.StreakRecord;
import com.byzi.api.domain.User;
import com.byzi.api.dto.streak.StreakRecordRequest;
import com.byzi.api.dto.streak.StreakRecordResponse;
import com.byzi.api.exception.ResourceNotFoundException;
import com.byzi.api.mapper.StreakRecordMapper;
import com.byzi.api.repository.StreakRecordRepository;
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
 * Comme pour les sessions de focus, le userId vient TOUJOURS du JWT et descend dans la clause
 * where de chaque requete : un streak appartenant a autrui est invisible, pas "refuse".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreakRecordService {

    private final StreakRecordRepository streakRecordRepository;
    private final UserRepository userRepository;
    private final StreakRecordMapper mapper;
    private final ConflictResolutionStrategy conflictResolutionStrategy;

    /**
     * Un streak est identifie de DEUX facons : par son id client, et par le couple
     * (utilisateur, jour) que la contrainte uk_streak_user_day impose unique. La recherche
     * teste donc les deux : deux appareils ayant genere des ids differents pour le meme jour
     * doivent converger vers une seule ligne, sinon l'insertion violerait la contrainte.
     * <p>
     * La recherche par jour inclut les lignes supprimees, volontairement : un streak supprime
     * occupe toujours son creneau (user_id, day). Le retrouver permet de le REANIMER plutot
     * que de tenter une seconde insertion vouee a echouer.
     * <p>
     * Regle de resurrection : identique a celle des sessions de focus - une suppression est une
     * ecriture comme une autre, et le last-write-wins tranche seul (cf. FocusSessionService).
     */
    @Transactional
    public StreakRecordResponse upsert(UUID id, UUID userId, StreakRecordRequest recordRequest) {
        Optional<StreakRecord> existingById = streakRecordRepository.findByIdAndUser_Id(id, userId);
        Optional<StreakRecord> existingByDay = streakRecordRepository.findByUser_IdAndDay(userId, recordRequest.date());

        Optional<StreakRecord> canonical = existingById.or(() -> existingByDay);

        if (canonical.isPresent()) {
            StreakRecord current = canonical.get();
            if (!conflictResolutionStrategy.shouldApplyIncoming(recordRequest.clientUpdatedAt(), current.getUpdatedAt())) {
                return mapper.toResponse(current);
            }
            current.setDeletedAt(null);
            mapper.applyUpdate(current, recordRequest);
            return mapper.toResponse(streakRecordRepository.save(current));
        }

        User owner = userRepository.getReferenceById(userId);
        StreakRecord created = mapper.toNewEntity(resolveIdForNewRecord(id, userId), owner, recordRequest);
        return mapper.toResponse(streakRecordRepository.save(created));
    }

    @Transactional(readOnly = true)
    public StreakRecordResponse get(UUID id, UUID userId) {
        return streakRecordRepository.findByIdAndUser_IdAndDeletedAtIsNull(id, userId)
                .map(mapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Streak introuvable"));
    }

    /**
     * @param updatedSince quand il est fourni, bascule en mode delta (tombstones compris,
     *                     tries par updatedAt croissant). Sans lui, liste d'affichage
     *                     ordinaire, dont les streaks supprimes sont exclus.
     */
    @Transactional(readOnly = true)
    public Page<StreakRecordResponse> list(UUID userId, Instant updatedSince, Pageable pageable) {
        if (updatedSince == null) {
            return streakRecordRepository
                    .findAllByUser_IdAndDeletedAtIsNullOrderByDayDesc(userId, pageable)
                    .map(mapper::toResponse);
        }
        return streakRecordRepository
                .findAllByUser_IdAndUpdatedAtGreaterThanEqual(userId, updatedSince, SyncPageables.forDelta(pageable))
                .map(mapper::toResponse);
    }

    /** Suppression LOGIQUE, pour les raisons detaillees dans {@link FocusSessionService#delete}. */
    @Transactional
    public void delete(UUID id, UUID userId) {
        StreakRecord record = streakRecordRepository.findByIdAndUser_IdAndDeletedAtIsNull(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Streak introuvable"));

        record.setDeletedAt(Instant.now());
        streakRecordRepository.save(record);
    }

    /**
     * Meme protection que pour les sessions de focus (BLOQ-01) : l'id vient du client, donc
     * deux comptes peuvent proposer le meme UUID. Sans cette verification, save() ne ferait pas
     * un insert en echec mais un merge, qui ECRASERAIT la ligne de l'autre utilisateur - et,
     * l'entite portant une relation vers son proprietaire, la lui retirerait au passage.
     */
    private UUID resolveIdForNewRecord(UUID requestedId, UUID userId) {
        if (streakRecordRepository.existsById(requestedId)) {
            UUID reassigned = UUID.randomUUID();
            log.info("Id de streak {} deja utilise par un autre compte : le streak de l'utilisateur {} "
                    + "est cree sous l'id {}", requestedId, userId, reassigned);
            return reassigned;
        }
        return requestedId;
    }
}
