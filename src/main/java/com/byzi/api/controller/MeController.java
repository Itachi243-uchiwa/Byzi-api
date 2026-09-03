package com.byzi.api.controller;

import com.byzi.api.dto.account.MeResponse;
import com.byzi.api.dto.account.UpdateProfileRequest;
import com.byzi.api.dto.subscription.AppleSubscriptionReportRequest;
import com.byzi.api.security.SecurityUtils;
import com.byzi.api.service.AccountProfileService;
import com.byzi.api.service.subscription.SubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    private final SubscriptionService subscriptionService;

    @Operation(summary = "Profil et statut d'abonnement de l'utilisateur courant (source de verite serveur)")
    @GetMapping
    public ResponseEntity<MeResponse> me() {
        return ResponseEntity.ok(accountProfileService.currentProfile(SecurityUtils.currentUserId()));
    }

    @Operation(summary = "Met a jour le prenom de l'utilisateur courant",
            description = "Seul le prenom est modifiable ici. L'email vient d'Apple, le statut "
                    + "d'abonnement de RevenueCat, le role du back-office.")
    @PutMapping
    public ResponseEntity<MeResponse> updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(accountProfileService.updateProfile(SecurityUtils.currentUserId(), request));
    }

    @Operation(summary = "Rapporte un achat StoreKit lu localement par l'app iOS",
            description = "Pas de SDK RevenueCat cote client (EPIC-07, 2026-09-03) : l'app "
                    + "rapporte ici ce qu'elle a lu dans Transaction.currentEntitlements. "
                    + "userId vient du JWT, jamais du corps - un compte ne peut rapporter que "
                    + "pour lui-meme. Voir SubscriptionService.applyClientReportedApplePurchase "
                    + "pour la nuance avec un webhook verifie par Apple.")
    @PostMapping("/subscription/apple")
    public ResponseEntity<MeResponse> reportAppleSubscription(@Valid @RequestBody AppleSubscriptionReportRequest request) {
        subscriptionService.applyClientReportedApplePurchase(SecurityUtils.currentUserId(), request);
        return ResponseEntity.ok(accountProfileService.currentProfile(SecurityUtils.currentUserId()));
    }
}
