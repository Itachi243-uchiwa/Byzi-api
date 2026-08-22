package com.buzi.api.controller.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminLoginController {

    /**
     * Page de connexion du back-office. Le POST correspondant n'est pas traite ici : c'est
     * Spring Security qui l'intercepte (loginProcessingUrl), afin de beneficier de sa gestion
     * native des tentatives, de la session et du jeton CSRF.
     */
    @GetMapping("/admin/login")
    public String login() {
        return "admin/login";
    }
}
