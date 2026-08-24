package com.byzi.api.controller;

import com.byzi.api.dto.session.FocusSessionRequest;
import com.byzi.api.dto.session.FocusSessionResponse;
import com.byzi.api.security.SecurityUtils;
import com.byzi.api.service.FocusSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import com.byzi.api.dto.common.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

@Tag(name = "Focus Sessions", description = "Synchronisation des sessions de focus (miroir de SwiftData)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/focus-sessions")
public class FocusSessionController {

    private final FocusSessionService focusSessionService;

    public FocusSessionController(FocusSessionService focusSessionService) {
        this.focusSessionService = focusSessionService;
    }

    @Operation(summary = "Cree ou met a jour une session de focus (upsert idempotent sur l'id client)",
            description = "Une session supprimee n'est reanimee que si clientUpdatedAt est posterieur a "
                    + "la date de suppression : le tombstone est une ecriture comme une autre dans le "
                    + "last-write-wins.")
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

    @Operation(summary = "Liste paginee des sessions de l'utilisateur courant, plus recentes d'abord",
            description = "Avec updatedSince, bascule en synchronisation incrementale : seules les "
                    + "sessions modifiees depuis cet instant sont renvoyees, tombstones compris "
                    + "(champ deletedAt non nul), triees par updatedAt croissant. Le client retient le "
                    + "updatedAt du dernier element recu comme point de reprise.")
    @GetMapping
    public ResponseEntity<PageResponse<FocusSessionResponse>> list(
            @Parameter(description = "Instant ISO-8601. Active le mode delta.", example = "2026-08-22T10:15:30Z")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant updatedSince,
            @PageableDefault(size = 50, sort = "startedAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return ResponseEntity.ok(PageResponse.from(focusSessionService.list(SecurityUtils.currentUserId(), updatedSince, pageable)));
    }

    @Operation(summary = "Supprime une session de focus",
            description = "Suppression logique : la session sort des listes et des GET, mais reste "
                    + "visible dans le delta sous forme de tombstone pour que les autres appareils "
                    + "sachent supprimer leur copie locale.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        focusSessionService.delete(id, SecurityUtils.currentUserId());
        return ResponseEntity.noContent().build();
    }
}
