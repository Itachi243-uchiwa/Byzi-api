package com.byzi.api.controller.admin;

import com.byzi.api.service.admin.AdminAccountService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Le compte de l'administrateur connecte - aujourd'hui, son mot de passe et rien d'autre.
 * <p>
 * <b>Pourquoi cet ecran existe (2026-09-09).</b> Le back-office etait complet cote metier mais
 * n'offrait aucun moyen de changer son propre mot de passe. Combine au bootstrap de demarrage
 * ({@code AdminBootstrap}), ca donnait une impasse : le mot de passe initial, pose par variable
 * d'environnement, devait rester en place pour toujours. Or un secret de bootstrap doit pouvoir
 * etre remplace <b>puis retire de la configuration</b> - c'est tout l'interet de le nommer
 * "bootstrap".
 * <p>
 * Ouvert a tous les roles d'administration, y compris le support et la finance : ce n'est pas un
 * privilege, c'est l'hygiene minimale d'un compte a mot de passe.
 */
@Controller
@RequestMapping("/admin/account")
@RequiredArgsConstructor
public class AdminAccountController {

    private final AdminAccountService accountService;

    @GetMapping
    public String account() {
        return "admin/account";
    }

    @PostMapping("/password")
    public String changePassword(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam String currentPassword,
            @RequestParam String newPassword,
            @RequestParam String confirmPassword,
            RedirectAttributes redirect
    ) {
        try {
            accountService.changePassword(principal.getUsername(), currentPassword, newPassword, confirmPassword);
            redirect.addFlashAttribute("success", "Mot de passe mis a jour.");
        } catch (IllegalArgumentException e) {
            // Message porte par l'exception : il est deja redige pour un humain, et il ne
            // distingue jamais "mot de passe actuel faux" de "compte inconnu".
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/account";
    }
}
