package com.byzi.api.controller;

import com.byzi.api.dto.account.MeResponse;
import com.byzi.api.security.SecurityUtils;
import com.byzi.api.service.AccountProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HAUT-01 de l'audit backend : jusqu'ici, seul le back-office lisait le statut d'abonnement
 * calcule par SubscriptionService - l'app iOS n'avait aucun moyen de le consulter, alors que
 * le paywall, le verrouillage du mode Deep Focus et l'ecran de compte en dependent tous. Ce
 * controller est le contrat de reference du client Swift pour ces trois usages.
 */
@Tag(name = "Account", description = "Profil de l'utilisateur courant")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class MeController {

    private final AccountProfileService accountProfileService;

    @Operation(summary = "Profil et statut d'abonnement de l'utilisateur courant (source de verite serveur)")
    @GetMapping
    public ResponseEntity<MeResponse> me() {
        return ResponseEntity.ok(accountProfileService.currentProfile(SecurityUtils.currentUserId()));
    }
}
