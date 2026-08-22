package com.byzi.api.controller;

import com.byzi.api.dto.blockrule.AppBlockRuleRequest;
import com.byzi.api.dto.blockrule.AppBlockRuleResponse;
import com.byzi.api.security.SecurityUtils;
import com.byzi.api.service.AppBlockRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
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

@Tag(name = "App Block Rules", description = "Synchronisation des regles de blocage (blob opaque, jamais interprete cote serveur)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/app-block-rules")
public class AppBlockRuleController {

    private final AppBlockRuleService appBlockRuleService;

    public AppBlockRuleController(AppBlockRuleService appBlockRuleService) {
        this.appBlockRuleService = appBlockRuleService;
    }

    @Operation(summary = "Cree ou met a jour une regle de blocage (upsert idempotent)",
            description = "Une regle supprimee n'est reanimee que si clientUpdatedAt est posterieur a "
                    + "la date de suppression. Ne pas confondre active (regle desactivee mais "
                    + "conservee) et deletedAt (regle supprimee).")
    @PutMapping("/{id}")
    public ResponseEntity<AppBlockRuleResponse> upsert(
            @PathVariable UUID id,
            @Valid @RequestBody AppBlockRuleRequest request
    ) {
        return ResponseEntity.ok(appBlockRuleService.upsert(id, SecurityUtils.currentUserId(), request));
    }

    @Operation(summary = "Recupere une regle de blocage par ID")
    @GetMapping("/{id}")
    public ResponseEntity<AppBlockRuleResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(appBlockRuleService.get(id, SecurityUtils.currentUserId()));
    }

    @Operation(summary = "Liste paginee des regles de blocage de l'utilisateur courant",
            description = "Avec updatedSince, bascule en synchronisation incrementale : seules les "
                    + "regles modifiees depuis cet instant sont renvoyees, tombstones compris "
                    + "(champ deletedAt non nul), triees par updatedAt croissant.")
    @GetMapping
    public ResponseEntity<Page<AppBlockRuleResponse>> list(
            @Parameter(description = "Instant ISO-8601. Active le mode delta.", example = "2026-08-22T10:15:30Z")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant updatedSince,
            @PageableDefault(size = 50) Pageable pageable
    ) {
        return ResponseEntity.ok(appBlockRuleService.list(SecurityUtils.currentUserId(), updatedSince, pageable));
    }

    @Operation(summary = "Supprime une regle de blocage",
            description = "Suppression logique : la regle sort des listes et des GET, mais reste "
                    + "visible dans le delta sous forme de tombstone.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        appBlockRuleService.delete(id, SecurityUtils.currentUserId());
        return ResponseEntity.noContent().build();
    }
}
