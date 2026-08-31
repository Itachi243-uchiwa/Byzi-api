package com.byzi.api.controller;

import com.byzi.api.dto.settings.UserSettingsRequest;
import com.byzi.api.dto.settings.UserSettingsResponse;
import com.byzi.api.security.SecurityUtils;
import com.byzi.api.service.UserSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Réglages de l'utilisateur courant (miroir de {@code FocusGoal} côté iOS). Ressource
 * singulière : pas d'id dans l'URL, pas de liste, pas de suppression — un GET et un PUT.
 */
@Tag(name = "User Settings", description = "Réglages de l'utilisateur (objectif de focus quotidien) — miroir de FocusGoal iOS")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/user-settings")
@RequiredArgsConstructor
public class UserSettingsController {

    private final UserSettingsService userSettingsService;

    @Operation(summary = "Réglages de l'utilisateur courant",
            description = "La ligne est créée avec les valeurs par défaut au premier appel : "
                    + "le client ne reçoit jamais de 404 « pas encore de réglages ».")
    @GetMapping
    public ResponseEntity<UserSettingsResponse> get() {
        return ResponseEntity.ok(userSettingsService.getOrCreate(SecurityUtils.currentUserId()));
    }

    @Operation(summary = "Met à jour les réglages",
            description = "Last-write-wins : la modification n'est appliquée que si clientUpdatedAt "
                    + "est postérieur à l'updatedAt serveur (ou si l'un des deux est absent).")
    @PutMapping
    public ResponseEntity<UserSettingsResponse> update(@Valid @RequestBody UserSettingsRequest request) {
        return ResponseEntity.ok(userSettingsService.upsert(SecurityUtils.currentUserId(), request));
    }
}
