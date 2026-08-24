package com.byzi.api.controller.admin;

import com.byzi.api.domain.Role;
import com.byzi.api.domain.User;
import com.byzi.api.exception.ForbiddenOperationException;
import com.byzi.api.repository.UserRepository;
import com.byzi.api.service.admin.AdminUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

/**
 * Ecrans de gestion des comptes (stories 09.2, 09.3, 09.5, 09.6).
 * <p>
 * Toutes les actions mutantes sont en POST : un GET destructeur serait declenchable par un
 * simple lien ou une image distante, et serait rejoue par le prefetch du navigateur.
 */
@Controller
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;
    private final UserRepository userRepository;

    @GetMapping
    public String list(@RequestParam(value = "q", required = false) String query,
                       @PageableDefault(size = 25) Pageable pageable,
                       Model model) {
        model.addAttribute("users", adminUserService.search(query, pageable));
        model.addAttribute("query", query);
        return "admin/users";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable UUID id, Model model) {
        model.addAttribute("detail", adminUserService.detail(id));
        return "admin/user-detail";
    }

    @PostMapping("/{id}/extend-trial")
    public String extendTrial(@PathVariable UUID id,
                              @RequestParam int days,
                              Authentication authentication,
                              RedirectAttributes redirectAttributes) {
        AdminIdentity admin = identify(authentication);
        try {
            adminUserService.extendTrial(id, days, admin.id(), admin.label());
            redirectAttributes.addFlashAttribute("success", "Acces prolonge de " + days + " jour(s).");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/users/" + id;
    }

    @PostMapping("/{id}/mark-refunded")
    public String markRefunded(@PathVariable UUID id,
                               @RequestParam(required = false) String reason,
                               Authentication authentication,
                               RedirectAttributes redirectAttributes) {
        AdminIdentity admin = identify(authentication);
        adminUserService.markRefunded(id, admin.id(), admin.label(), reason);
        redirectAttributes.addFlashAttribute("success", "Compte marque comme rembourse, acces revoque.");
        return "redirect:/admin/users/" + id;
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable UUID id,
                         Authentication authentication,
                         RedirectAttributes redirectAttributes) {
        AdminIdentity admin = identify(authentication);
        adminUserService.deleteAccount(id, admin.id(), admin.label());
        redirectAttributes.addFlashAttribute("success", "Compte supprime definitivement.");
        return "redirect:/admin/users";
    }

    /**
     * Story 17.4 - attribution d'un role. Le formulaire n'est rendu qu'aux ADMIN complets, mais
     * la verification qui compte est le @PreAuthorize du service : masquer un bouton n'empeche
     * personne de poster l'URL a la main.
     */
    @PostMapping("/{id}/role")
    public String changeRole(@PathVariable UUID id,
                             @RequestParam Role role,
                             Authentication authentication,
                             RedirectAttributes redirectAttributes) {
        AdminIdentity admin = identify(authentication);
        try {
            boolean canSignIn = adminUserService.changeRole(id, role, admin.id(), admin.label());
            redirectAttributes.addFlashAttribute("success", canSignIn
                    ? "Role mis a jour : " + role + "."
                    : "Role mis a jour : " + role + ". Ce compte n'a pas de mot de passe et ne "
                      + "pourra pas encore se connecter au back-office.");
        } catch (ForbiddenOperationException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/users/" + id;
    }

    /**
     * Identifiant de repli quand le principal authentifie ne correspond a aucune ligne users
     * (compte supprime pendant la session). L'audit est en not null : mieux vaut une trace
     * explicitement "non resolue", avec l'email conserve dans admin_label, qu'une action
     * qui echoue ou, pire, qui s'execute sans laisser de trace.
     */
    private static final UUID UNRESOLVED_ADMIN = new UUID(0L, 0L);

    /**
     * Retrouve l'admin connecte. Le principal Spring Security ne porte que l'email (c'est
     * l'identifiant de connexion), or l'audit veut aussi un id stable : le libelle seul
     * deviendrait ambigu si un email etait reattribue.
     */
    private AdminIdentity identify(Authentication authentication) {
        String email = authentication.getName();
        UUID id = userRepository.findByEmail(email).map(User::getId).orElse(UNRESOLVED_ADMIN);
        return new AdminIdentity(id, email);
    }

    private record AdminIdentity(UUID id, String label) {
    }
}
