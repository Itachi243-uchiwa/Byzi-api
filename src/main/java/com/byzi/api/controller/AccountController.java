package com.byzi.api.controller;

import com.byzi.api.dto.account.AccountExportResponse;
import com.byzi.api.security.SecurityUtils;
import com.byzi.api.service.AccountDeletionService;
import com.byzi.api.service.AccountExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * La double confirmation ("saisie du mot SUPPRIMER", cf. Fiche technique section 33) est
 * geree cote UI iOS, en amont de cet appel : le backend expose une action definitive et
 * ne demande pas de confirmation supplementaire - au moment ou cet endpoint est appele,
 * le consentement est deja acquis cote client.
 */
@Tag(name = "Account", description = "Suppression de compte (RGPD / App Store Guideline 5.1.1(v)) "
        + "et export portable des donnees (art. 20 RGPD)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/account")
public class AccountController {

    private final AccountDeletionService accountDeletionService;
    private final AccountExportService accountExportService;

    public AccountController(AccountDeletionService accountDeletionService, AccountExportService accountExportService) {
        this.accountDeletionService = accountDeletionService;
        this.accountExportService = accountExportService;
    }

    @Operation(summary = "Supprime definitivement le compte de l'utilisateur courant et toutes ses donnees")
    @DeleteMapping
    public ResponseEntity<Void> deleteAccount() {
        accountDeletionService.deleteAccount(SecurityUtils.currentUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * MANQUE-03 de l'audit backend : le DELETE ci-dessus couvre le droit a l'effacement
     * (art. 17 RGPD), ce endpoint couvre le droit a la portabilite (art. 20) - jusqu'ici sans
     * aucun equivalent. Content-Disposition en piece jointe : ce JSON est destine a etre
     * telecharge et conserve par l'utilisateur, pas affiche inline dans un navigateur.
     */
    @Operation(summary = "Exporte toutes les donnees personnelles de l'utilisateur courant (droit a la portabilite, art. 20 RGPD)")
    @GetMapping("/export")
    public ResponseEntity<AccountExportResponse> exportAccount() {
        UUID userId = SecurityUtils.currentUserId();
        AccountExportResponse export = accountExportService.export(userId);

        ContentDisposition disposition = ContentDisposition.attachment()
                .filename("byzi-export-" + userId + ".json")
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(export);
    }
}