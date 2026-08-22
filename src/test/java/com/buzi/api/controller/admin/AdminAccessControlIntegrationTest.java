package com.buzi.api.controller.admin;

import com.buzi.api.domain.Role;
import com.buzi.api.domain.User;
import com.buzi.api.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Story 09.1 : "module /admin securise, role ADMIN distinct des users".
 * <p>
 * Le point sensible est qu'un utilisateur ordinaire de l'app - qui possede un compte valide
 * et un JWT valide - ne doit obtenir aucun acces au back-office.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminAccessControlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User givenAdmin(String email, String rawPassword) {
        return userRepository.save(User.builder()
                .id(UUID.randomUUID())
                .appleSub("apple-sub-" + UUID.randomUUID())
                .email(email)
                .role(Role.ADMIN)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .build());
    }

    @Test
    void anonymousIsRedirectedToLogin() throws Exception {
        mockMvc.perform(get("/admin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/login"));
    }

    @Test
    void loginPageIsPubliclyReachable() throws Exception {
        mockMvc.perform(get("/admin/login")).andExpect(status().isOk());
    }

    @Test
    void loginPageAssetsAreReachableWithoutSession() throws Exception {
        // La feuille de style et le logo sont charges par la page de connexion elle-meme,
        // donc avant toute authentification : les proteger les ferait rediriger vers /login
        // et la page s'afficherait sans style ni logo.
        mockMvc.perform(get("/admin/css/byzi-admin.css")).andExpect(status().isOk());
        mockMvc.perform(get("/admin/images/logo.png")).andExpect(status().isOk());
    }

    @Test
    void authenticatedNonAdminIsForbidden() throws Exception {
        // Un utilisateur de l'app iOS, authentifie mais sans le role ADMIN.
        mockMvc.perform(get("/admin").with(user("someone").roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanReachBackOffice() throws Exception {
        mockMvc.perform(get("/admin").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void adminCanLogInWithCorrectPassword() throws Exception {
        givenAdmin("admin-ok@byzi.app", "un-mot-de-passe-solide");

        mockMvc.perform(formLogin("/admin/login").user("admin-ok@byzi.app").password("un-mot-de-passe-solide"))
                .andExpect(authenticated().withRoles("ADMIN"));
    }

    @Test
    void loginIsRejectedWithWrongPassword() throws Exception {
        givenAdmin("admin-ko@byzi.app", "un-mot-de-passe-solide");

        mockMvc.perform(formLogin("/admin/login").user("admin-ko@byzi.app").password("mauvais"))
                .andExpect(unauthenticated());
    }

    @Test
    void appUserWithoutAdminRoleCannotLogIn() throws Exception {
        // Meme avec un mot de passe correct en base, un compte USER n'entre pas : c'est le
        // role qui tranche, pas la seule possession d'un mot de passe.
        userRepository.save(User.builder()
                .id(UUID.randomUUID())
                .appleSub("apple-sub-" + UUID.randomUUID())
                .email("simple-user@byzi.app")
                .role(Role.USER)
                .passwordHash(passwordEncoder.encode("un-mot-de-passe-solide"))
                .build());

        mockMvc.perform(formLogin("/admin/login").user("simple-user@byzi.app").password("un-mot-de-passe-solide"))
                .andExpect(unauthenticated());
    }

    @Test
    void adminAccountWithoutPasswordCannotLogIn() throws Exception {
        userRepository.save(User.builder()
                .id(UUID.randomUUID())
                .appleSub("apple-sub-" + UUID.randomUUID())
                .email("admin-sans-mdp@byzi.app")
                .role(Role.ADMIN)
                .build());

        mockMvc.perform(formLogin("/admin/login").user("admin-sans-mdp@byzi.app").password("nimporte-quoi"))
                .andExpect(unauthenticated());
    }

    @Test
    void apiChainStaysStatelessAndUnaffectedByAdminChain() throws Exception {
        // La chaine admin ne doit pas "deborder" sur l'API : une requete API sans JWT reste
        // un 401 JSON, pas une redirection vers le formulaire de connexion admin.
        mockMvc.perform(get("/api/v1/focus-sessions"))
                .andExpect(status().isUnauthorized());
    }
}
