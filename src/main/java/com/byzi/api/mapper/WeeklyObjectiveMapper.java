package com.byzi.api.mapper;

import com.byzi.api.domain.User;
import com.byzi.api.domain.WeeklyObjective;
import com.byzi.api.dto.objective.WeeklyObjectiveRequest;
import com.byzi.api.dto.objective.WeeklyObjectiveResponse;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class WeeklyObjectiveMapper {

    public WeeklyObjective toNewEntity(UUID id, User owner, WeeklyObjectiveRequest request) {
        return WeeklyObjective.builder()
                .id(id)
                .user(owner)
                .title(request.title())
                .weekKey(request.weekKey())
                .linkedTaskIds(request.linkedTaskIds())
                .isAchieved(request.achieved())
                .achievedAt(achievedAtOrNull(request))
                .clientCreatedAt(sanitizedClientCreatedAt(request.clientCreatedAt()))
                .build();
    }

    public void applyUpdate(WeeklyObjective entity, WeeklyObjectiveRequest request) {
        entity.setTitle(request.title());
        entity.setWeekKey(request.weekKey());
        entity.setLinkedTaskIds(request.linkedTaskIds());
        entity.setAchieved(request.achieved());
        entity.setAchievedAt(achievedAtOrNull(request));
    }

    public WeeklyObjectiveResponse toResponse(WeeklyObjective entity) {
        return new WeeklyObjectiveResponse(
                entity.getId(),
                entity.getTitle(),
                entity.getWeekKey(),
                entity.getLinkedTaskIds(),
                entity.isAchieved(),
                entity.getAchievedAt(),
                writtenAt(entity),
                entity.getUpdatedAt(),
                entity.getDeletedAt()
        );
    }

    /**
     * Un objectif redevenu non atteint ne garde PAS sa date d'atteinte. C'est le point le plus
     * sensible de cette ressource : cote app, achievedAt designe le jour qui compte pour la
     * serie. Un achievedAt orphelin ferait gagner une serie pour un objectif abandonne.
     */
    private Instant achievedAtOrNull(WeeklyObjectiveRequest request) {
        return request.achieved() ? request.achievedAt() : null;
    }

    /**
     * Date a afficher cote client : « ecrit le … ».
     * <p>
     * On expose la date d'ECRITURE, pas la date d'audit. Le client ne connait qu'un seul
     * createdAt et c'est celui-la qui l'interesse ; createdAt (arrivee sur le serveur) reste
     * interne. Repli sur l'audit quand le client ne l'a pas envoye - une date approchee vaut
     * mieux que pas de date, et c'est exactement la meme valeur qu'avant ce changement.
     */
    private Instant writtenAt(WeeklyObjective entity) {
        Instant client = entity.getClientCreatedAt();
        return client != null ? client : entity.getCreatedAt();
    }

    /** Meme garde-fou que sur TodoTaskMapper : pas de date d'ecriture dans le futur. */
    private Instant sanitizedClientCreatedAt(Instant candidate) {
        if (candidate == null || candidate.isAfter(Instant.now())) {
            return null;
        }
        return candidate;
    }
}
