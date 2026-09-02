package com.byzi.api.controller;

import com.byzi.api.dto.common.PageResponse;
import com.byzi.api.dto.objective.WeeklyObjectiveRequest;
import com.byzi.api.dto.objective.WeeklyObjectiveResponse;
import com.byzi.api.security.SecurityUtils;
import com.byzi.api.service.WeeklyObjectiveService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@Tag(name = "Weekly Objectives", description = "Synchronisation des objectifs hebdomadaires")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/weekly-objectives")
public class WeeklyObjectiveController {

    private final WeeklyObjectiveService weeklyObjectiveService;

    public WeeklyObjectiveController(WeeklyObjectiveService weeklyObjectiveService) {
        this.weeklyObjectiveService = weeklyObjectiveService;
    }

    @Operation(summary = "Cree ou met a jour un objectif hebdomadaire (upsert idempotent)")
    @PutMapping("/{id}")
    public ResponseEntity<WeeklyObjectiveResponse> upsert(
            @PathVariable UUID id,
            @Valid @RequestBody WeeklyObjectiveRequest request
    ) {
        return ResponseEntity.ok(weeklyObjectiveService.upsert(id, SecurityUtils.currentUserId(), request));
    }

    @Operation(summary = "Recupere un objectif par ID")
    @GetMapping("/{id}")
    public ResponseEntity<WeeklyObjectiveResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(weeklyObjectiveService.get(id, SecurityUtils.currentUserId()));
    }

    @Operation(summary = "Liste paginee des objectifs de l'utilisateur courant",
            description = "Avec updatedSince, bascule en synchronisation incrementale, tombstones compris.")
    @GetMapping
    public ResponseEntity<PageResponse<WeeklyObjectiveResponse>> list(
            @Parameter(description = "Instant ISO-8601. Active le mode delta.", example = "2026-09-02T10:15:30Z")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant updatedSince,
            @PageableDefault(size = 50) Pageable pageable
    ) {
        return ResponseEntity.ok(PageResponse.from(
                weeklyObjectiveService.list(SecurityUtils.currentUserId(), updatedSince, pageable)));
    }

    @Operation(summary = "Supprime un objectif",
            description = "Suppression logique : visible dans le delta sous forme de tombstone.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        weeklyObjectiveService.delete(id, SecurityUtils.currentUserId());
        return ResponseEntity.noContent().build();
    }
}
