package com.byzi.api.controller;

import com.byzi.api.dto.blockrule.AppBlockRuleRequest;
import com.byzi.api.dto.blockrule.AppBlockRuleResponse;
import com.byzi.api.security.SecurityUtils;
import com.byzi.api.service.AppBlockRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

@Tag(name = "App Block Rules", description = "Synchronisation des regles de blocage (blob opaque, jamais interprete cote serveur)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/app-block-rules")
public class AppBlockRuleController {

    private final AppBlockRuleService appBlockRuleService;

    public AppBlockRuleController(AppBlockRuleService appBlockRuleService) {
        this.appBlockRuleService = appBlockRuleService;
    }

    @Operation(summary = "Cree ou met a jour une regle de blocage (upsert idempotent)")
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

    @Operation(summary = "Liste paginee des regles de blocage de l'utilisateur courant")
    @GetMapping
    public ResponseEntity<Page<AppBlockRuleResponse>> list(@PageableDefault(size = 50) Pageable pageable) {
        return ResponseEntity.ok(appBlockRuleService.list(SecurityUtils.currentUserId(), pageable));
    }

    @Operation(summary = "Supprime une regle de blocage")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        appBlockRuleService.delete(id, SecurityUtils.currentUserId());
        return ResponseEntity.noContent().build();
    }
}