package com.byzi.api.controller.admin;

import com.byzi.api.domain.Role;
import com.byzi.api.domain.SubscriptionStatus;
import com.byzi.api.domain.User;
import com.byzi.api.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Story 17.4 - separation des roles d'administration.
 * <p>
 * Le principe verifie ici : personne ne dispose en permanence de droits dont son travail
 * quotidien n'a pas besoin. Le support consulte mais n'engage aucune depense ni suppression ;
 * la finance agit sur les acces payes mais ne supprime pas de comptes ; seul l'ADMIN complet
 * distribue les roles et efface.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminRoleSeparationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private User givenAccount(Role role) {
        return userRepository.save(User.builder()
                .id(UUID.randomUUID())
                .appleSub("apple-sub-" + UUID.randomUUID())
                .email(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@byzi.app")
                .role(role)
                .subscriptionStatus(SubscriptionStatus.TRIAL)
                .passwordHash(passwordEncoder.encode("motdepasse"))
                .build());
    }

    /** Session du back-office portant le role reel du compte, comme apres un formLogin. */
    private RequestPostProcessor as(Role role) {
        return user("admin@byzi.app").roles(role.name());
    }

    private MockHttpServletRequestBuilder extendTrial(User target) {
        return post("/admin/users/{id}/extend-trial", target.getId()).param("days", "7").with(csrf());
    }

    // ------------------------------------------------------------------- porte d'entree

    @Test
    void everyAdminRoleCanEnterTheBackOffice() throws Exception {
        for (Role role : Role.values()) {
            if (!role.isAdmin()) {
                continue;
            }
            mockMvc.perform(get("/admin/users").with(as(role)))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void appUsersStillHaveNoAccessAtAll() throws Exception {
        // La porte s'ouvre desormais a trois roles au lieu d'un : l'occasion typique de
        // laisser passer USER par inadvertance.
        mockMvc.perform(get("/admin/users").with(as(Role.USER)))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------------- support

    @Test
    void supportCanReadAccountsButNotExtendTrials() throws Exception {
        User target = givenAccount(Role.USER);

        mockMvc.perform(get("/admin/users/{id}", target.getId()).with(as(Role.ADMIN_SUPPORT)))
                .andExpect(status().isOk());
        mockMvc.perform(extendTrial(target).with(as(Role.ADMIN_SUPPORT)))
                .andExpect(status().isForbidden());
    }

    @Test
    void supportCannotDeleteAnAccount() throws Exception {
        User target = givenAccount(Role.USER);

        mockMvc.perform(post("/admin/users/{id}/delete", target.getId())
                        .with(csrf()).with(as(Role.ADMIN_SUPPORT)))
                .andExpect(status().isForbidden());

        assertThat(userRepository.existsById(target.getId()))
                .as("un refus d'autorisation ne doit surtout pas s'accompagner d'un effet")
                .isTrue();
    }

    // ----------------------------------------------------------------------- finance

    @Test
    void financeCanExtendTrials() throws Exception {
        mockMvc.perform(extendTrial(givenAccount(Role.USER)).with(as(Role.ADMIN_FINANCE)))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void financeCannotDeleteAnAccount() throws Exception {
        User target = givenAccount(Role.USER);

        mockMvc.perform(post("/admin/users/{id}/delete", target.getId())
                        .with(csrf()).with(as(Role.ADMIN_FINANCE)))
                .andExpect(status().isForbidden());

        assertThat(userRepository.existsById(target.getId())).isTrue();
    }

    // ------------------------------------------------------- hierarchie et attribution

    @Test
    void fullAdminInheritsEverySpecialisedRole() throws Exception {
        // C'est le role de la hierarchie declaree dans SecurityConfig : sans elle, chaque
        // @PreAuthorize devrait enumerer les roles habilites, et l'ADMIN complet finirait par
        // etre oublie dans l'un d'eux.
        User target = givenAccount(Role.USER);

        mockMvc.perform(extendTrial(target).with(as(Role.ADMIN)))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/admin/users/{id}/delete", target.getId())
                        .with(csrf()).with(as(Role.ADMIN)))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void onlyFullAdminMayHandOutRoles() throws Exception {
        User target = givenAccount(Role.USER);

        mockMvc.perform(post("/admin/users/{id}/role", target.getId())
                        .param("role", Role.ADMIN.name()).with(csrf()).with(as(Role.ADMIN_SUPPORT)))
                .andExpect(status().isForbidden());

        assertThat(userRepository.findById(target.getId()).orElseThrow().getRole())
                .as("distribuer les droits est en soi le droit le plus sensible")
                .isEqualTo(Role.USER);
    }

    @Test
    void grantingARoleTakesEffect() throws Exception {
        User target = givenAccount(Role.USER);

        mockMvc.perform(post("/admin/users/{id}/role", target.getId())
                        .param("role", Role.ADMIN_SUPPORT.name()).with(csrf()).with(as(Role.ADMIN)))
                .andExpect(status().is3xxRedirection());

        assertThat(userRepository.findById(target.getId()).orElseThrow().getRole())
                .isEqualTo(Role.ADMIN_SUPPORT);
    }

    // -------------------------------------------------------------------- interface

    @Test
    void supportIsNotShownButtonsItCannotUse() throws Exception {
        User target = givenAccount(Role.USER);

        String page = mockMvc.perform(get("/admin/users/{id}", target.getId())
                        .with(as(Role.ADMIN_SUPPORT)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Masquer n'est pas la protection - les tests ci-dessus s'en chargent - mais proposer
        // un bouton qui repond 403 est une facon sure de faire douter le support de ses outils.
        assertThat(page).doesNotContain("Marquer comme rembourse")
                .doesNotContain("Supprimer le compte")
                .doesNotContain("Appliquer le role");
    }

    @Test
    void fullAdminSeesEveryAction() throws Exception {
        User target = givenAccount(Role.USER);

        mockMvc.perform(get("/admin/users/{id}", target.getId()).with(as(Role.ADMIN)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Marquer comme rembourse")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Supprimer le compte")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Appliquer le role")));
    }
}
