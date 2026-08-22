package com.buzi.api.controller;

import com.buzi.api.dto.streak.StreakRecordRequest;
import com.buzi.api.dto.streak.StreakRecordResponse;
import com.buzi.api.security.SecurityUtils;
import com.buzi.api.service.StreakRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Streak Records", description = "Synchronisation des streaks quotidiens (miroir de SwiftData)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/streak-records")
public class StreakRecordController {

    private final StreakRecordService streakRecordService;

    public StreakRecordController(StreakRecordService streakRecordService) {
        this.streakRecordService = streakRecordService;
    }

    @Operation(summary = "Cree ou met a jour un streak quotidien (upsert, un seul enregistrement par jour et par utilisateur)")
    @PutMapping("/{id}")
    public ResponseEntity<StreakRecordResponse> upsert(
            @PathVariable UUID id,
            @Valid @RequestBody StreakRecordRequest request
    ) {
        return ResponseEntity.ok(streakRecordService.upsert(id, SecurityUtils.currentUserId(), request));
    }

    @Operation(summary = "Recupere un streak par ID")
    @GetMapping("/{id}")
    public ResponseEntity<StreakRecordResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(streakRecordService.get(id, SecurityUtils.currentUserId()));
    }

    @Operation(summary = "Liste paginee des streaks de l'utilisateur courant, plus recents d'abord")
    @GetMapping
    public ResponseEntity<Page<StreakRecordResponse>> list(
            @PageableDefault(size = 90, sort = "day", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ResponseEntity.ok(streakRecordService.list(SecurityUtils.currentUserId(), pageable));
    }

    @Operation(summary = "Supprime un streak")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        streakRecordService.delete(id, SecurityUtils.currentUserId());
        return ResponseEntity.noContent().build();
    }
}