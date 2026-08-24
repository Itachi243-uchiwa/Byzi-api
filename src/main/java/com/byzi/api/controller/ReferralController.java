package com.byzi.api.controller;

import com.byzi.api.dto.referral.ReferralCodeResponse;
import com.byzi.api.dto.referral.ReferralRedeemRequest;
import com.byzi.api.dto.referral.ReferralRedeemResponse;
import com.byzi.api.security.SecurityUtils;
import com.byzi.api.service.referral.ReferralService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Parrainage (backlog 10.8). Deux gestes seulement du point de vue de l'app : recuperer son
 * code pour le partager, et saisir celui d'un tiers.
 */
@Tag(name = "Referral", description = "Codes de parrainage et jours d'essai offerts")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/referrals")
@RequiredArgsConstructor
public class ReferralController {

    private final ReferralService referralService;

    @Operation(summary = "Code de parrainage de l'utilisateur courant",
            description = "Le code est cree au premier appel puis ne change plus : il a pu etre "
                    + "partage entre-temps. Renvoie aussi le nombre de filleuls et le nombre de "
                    + "jours offerts par utilisation, pour que l'app n'ait pas a coder cette "
                    + "derniere valeur en dur.")
    @GetMapping("/me")
    public ResponseEntity<ReferralCodeResponse> myCode() {
        return ResponseEntity.ok(referralService.myCode(SecurityUtils.currentUserId()));
    }

    @Operation(summary = "Utilise le code de parrainage d'un autre utilisateur")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Jours accordes au filleul"),
            @ApiResponse(responseCode = "404", description = "Code inconnu"),
            @ApiResponse(responseCode = "409",
                    description = "Code appartenant au demandeur, compte deja parraine, ou compte "
                            + "deja abonne payant - l'offre s'adresse aux comptes en essai ou expires")
    })
    @PostMapping("/redeem")
    public ResponseEntity<ReferralRedeemResponse> redeem(
            @Valid @RequestBody ReferralRedeemRequest request
    ) {
        return ResponseEntity.ok(referralService.redeem(SecurityUtils.currentUserId(), request.code()));
    }
}
