package com.buzi.api.controller;

import com.buzi.api.dto.session.FocusSessionRequest;
import com.buzi.api.dto.session.FocusSessionResponse;
import com.buzi.api.security.SecurityUtils;
import com.buzi.api.service.FocusSessionService;
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

@Tag(name = "Focus Sessions", description = "Synchronisation des sessions de focus (miroir de SwiftData)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/focus-sessions")
public class FocusSessionController {

    private final FocusSessionService focusSessionService;

    public FocusSessionController(FocusSessionService focusSessionService) {
        this.focusSessionService = focusSessionService;
    }

    @Operation(summary = "Cree ou met a jour une session de focus (upsert idempotent sur l'id client)")
    @PutMapping("/{id}")
    public ResponseEntity<FocusSessionResponse> upsert(
            @PathVariable UUID id,
            @Valid @RequestBody FocusSessionRequest request
    ) {
        return ResponseEntity.ok(focusSessionService.upsert(id, SecurityUtils.currentUserId(), request));
    }

    @Operation(summary = "Recupere une session de focus par ID")
    @GetMapping("/{id}")
    public ResponseEntity<FocusSessionResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(focusSessionService.get(id, SecurityUtils.currentUserId()));
    }

    @Operation(summary = "Liste paginee des sessions de l'utilisateur courant, plus recentes d'abord")
    @GetMapping
    public ResponseEntity<Page<FocusSessionResponse>> list(
            @PageableDefault(size = 50, sort = "startedAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ResponseEntity.ok(focusSessionService.list(SecurityUtils.currentUserId(), pageable));
    }

    @Operation(summary = "Supprime une session de focus")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        focusSessionService.delete(id, SecurityUtils.currentUserId());
        return ResponseEntity.noContent().build();
    }
}
