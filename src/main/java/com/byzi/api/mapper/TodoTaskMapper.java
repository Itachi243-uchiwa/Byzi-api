package com.byzi.api.mapper;

import com.byzi.api.domain.TodoTask;
import com.byzi.api.domain.User;
import com.byzi.api.dto.todo.TodoTaskRequest;
import com.byzi.api.dto.todo.TodoTaskResponse;
import org.springframework.stereotype.Component;

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
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getDeletedAt()
        );
    }

    /**
     * Une tache non cochee ne garde pas de date de completion : sans ce nettoyage, decocher
     * une tache laissait un doneAt orphelin, que l'app aurait pu compter dans une serie.
     */
    private java.time.Instant doneAtOrNull(TodoTaskRequest request) {
        return request.done() ? request.doneAt() : null;
    }
}
