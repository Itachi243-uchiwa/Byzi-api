package com.byzi.api.mapper;

import com.byzi.api.domain.TodoTask;
import com.byzi.api.domain.User;
import com.byzi.api.dto.todo.TodoTaskRequest;
import com.byzi.api.dto.todo.TodoTaskResponse;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class TodoTaskMapper {

    public TodoTask toNewEntity(UUID id, User owner, TodoTaskRequest request) {
        return TodoTask.builder()
                .id(id)
                .user(owner)
                .title(request.title())
                .notes(request.notes())
                .weekKey(request.weekKey())
                .dueDate(request.dueDate())
                .isDone(request.done())
                .doneAt(doneAtOrNull(request))
                .clientCreatedAt(sanitizedClientCreatedAt(request.clientCreatedAt()))
                .build();
    }

    public void applyUpdate(TodoTask entity, TodoTaskRequest request) {
        entity.setTitle(request.title());
        entity.setNotes(request.notes());
        entity.setWeekKey(request.weekKey());
        entity.setDueDate(request.dueDate());
        entity.setDone(request.done());
        entity.setDoneAt(doneAtOrNull(request));
    }

    public TodoTaskResponse toResponse(TodoTask entity) {
        return new TodoTaskResponse(
                entity.getId(),
                entity.getTitle(),
                entity.getNotes(),
                entity.getWeekKey(),
                entity.getDueDate(),
                entity.isDone(),
                entity.getDoneAt(),
                writtenAt(entity),
                entity.getUpdatedAt(),
                entity.getDeletedAt()
        );
    }

    /**
     * Une tache non cochee ne garde pas de date de completion : sans ce nettoyage, decocher
     * une tache laissait un doneAt orphelin, que l'app aurait pu compter dans une serie.
     */
    private Instant doneAtOrNull(TodoTaskRequest request) {
        return request.done() ? request.doneAt() : null;
    }

    /**
     * Date a afficher cote client : « ecrit le … ».
     * <p>
     * On expose la date d'ECRITURE, pas la date d'audit. Le client ne connait qu'un seul
     * createdAt et c'est celui-la qui l'interesse ; createdAt (arrivee sur le serveur) reste
     * interne. Repli sur l'audit quand le client ne l'a pas envoye - une date approchee vaut
     * mieux que pas de date, et c'est exactement la meme valeur qu'avant ce changement.
     */
    private Instant writtenAt(TodoTask entity) {
        Instant client = entity.getClientCreatedAt();
        return client != null ? client : entity.getCreatedAt();
    }

    /**
     * Une date d'ecriture dans le futur n'a aucun sens : horloge deregle ou client hostile. On
     * la refuse plutot que de la stocker - le serveur retombera sur sa propre date.
     */
    private Instant sanitizedClientCreatedAt(Instant candidate) {
        if (candidate == null || candidate.isAfter(Instant.now())) {
            return null;
        }
        return candidate;
    }
}
