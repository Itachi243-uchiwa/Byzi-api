package com.byzi.api.controller;

import com.byzi.api.security.SecurityUtils;
import com.byzi.api.service.AccountDeletionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * La double confirmation ("saisie du mot SUPPRIMER", cf. Fiche technique section 33) est
 * geree cote UI iOS, en amont de cet appel : le backend expose une action definitive et
 * ne demande pas de confirmation supplementaire - au moment ou cet endpoint est appele,
 * le consentement est deja acquis cote client.
 */
@Tag(name = "Account", description = "Suppression de compte (RGPD / App Store Guideline 5.1.1(v))")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/account")
public class AccountController {

    private final AccountDeletionService accountDeletionService;

    public AccountController(AccountDeletionService accountDeletionService) {
        this.accountDeletionService = accountDeletionService;
    }

    @Operation(summary = "Supprime definitivement le compte de l'utilisateur courant et toutes ses donnees")
    @DeleteMapping
    public ResponseEntity<Void> deleteAccount() {
        accountDeletionService.deleteAccount(SecurityUtils.currentUserId());
        return ResponseEntity.noContent().build();
    }
}