package com.byzi.api.controller.admin;

import com.byzi.api.domain.Role;
import com.byzi.api.domain.SessionMode;
import com.byzi.api.domain.SubscriptionStatus;
import com.byzi.api.domain.User;
import com.byzi.api.repository.AdminAuditLogRepository;
import com.byzi.api.repository.FocusSessionRepository;
import com.byzi.api.repository.UserRepository;
import com.byzi.api.service.admin.AdminAuditService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Stories 09.2 a 09.7 : ecrans du back-office et actions de support.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminBackOfficeIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FocusSessionRepository focusSessionRepository;

    @Autowired
    private AdminAuditLogRepository auditLogRepository;

    private User givenUser(String email, SubscriptionStatus status) {
        return userRepository.save(User.builder()
                .id(UUID.randomUUID())
                .appleSub("apple-sub-" + UUID.randomUUID())
                .email(email)
                .role(Role.USER)
                .subscriptionStatus(status)
                .build());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor asAdmin() {
        return user("admin@byzi.app").roles("ADMIN");
    }

    // ---------------------------------------------------------------- 09.4 dashboard

    @Test
    void dashboardRendersKpis() throws Exception {
        mockMvc.perform(get("/admin").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/dashboard"))
                .andExpect(model().attributeExists("kpi"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Conversion essai")));
    }

    // ---------------------------------------------------------------- 09.2 liste

    @Test
    void userListShowsAccounts() throws Exception {
        givenUser("liste-" + UUID.randomUUID() + "@byzi.app", SubscriptionStatus.ACTIVE);

        mockMvc.perform(get("/admin/users").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/users"))
                .andExpect(model().attributeExists("users"));
    }

    @Test
    void userListCanBeFilteredByEmail() throws Exception {
        String needle = "aiguille-" + UUID.randomUUID();
        givenUser(needle + "@byzi.app", SubscriptionStatus.TRIAL);
        givenUser("autre-" + UUID.randomUUID() + "@byzi.app", SubscriptionStatus.TRIAL);

        mockMvc.perform(get("/admin/users").param("q", needle).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(needle)));
    }

    @Test
    void emptySearchDoesNotFilterAnything() throws Exception {
        givenUser("vide-" + UUID.randomUUID() + "@byzi.app", SubscriptionStatus.TRIAL);

        mockMvc.perform(get("/admin/users").param("q", "   ").with(asAdmin()))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- 09.3 detail

    @Test
    void userDetailShowsSessionsAndSubscription() throws Exception {
        User user = givenUser("detail-" + UUID.randomUUID() + "@byzi.app", SubscriptionStatus.ACTIVE);
        focusSessionRepository.save(com.byzi.api.domain.FocusSession.builder()
                .id(UUID.randomUUID())
                .user(user)
                .startedAt(Instant.now())
                .plannedDurationSeconds(1500)
                .mode(SessionMode.STANDARD)
                .build());

        mockMvc.perform(get("/admin/users/{id}", user.getId()).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/user-detail"))
                .andExpect(model().attributeExists("detail"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Historique d'abonnement")));
    }

    @Test
    void unknownUserDetailReturnsNotFound() throws Exception {
        mockMvc.perform(get("/admin/users/{id}", UUID.randomUUID()).with(asAdmin()))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- 09.5 actions support

    @Test
    void extendTrialPushesExpiryAndWritesAudit() throws Exception {
        User user = givenUser("prolong-" + UUID.randomUUID() + "@byzi.app", SubscriptionStatus.EXPIRED);

        mockMvc.perform(post("/admin/users/{id}/extend-trial", user.getId())
                        .param("days", "7").with(asAdmin()).with(csrf()))
                .andExpect(redirectedUrl("/admin/users/" + user.getId()));

        User updated = userRepository.findById(user.getId()).orElseThrow();
        assertThat(updated.getSubscriptionExpiresAt()).isAfter(Instant.now());
        assertThat(updated.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.TRIAL);

        assertThat(auditLogRepository.findAllByTargetUserIdOrderByOccurredAtDesc(user.getId()))
                .extracting("action")
                .containsExactly(AdminAuditService.ACTION_EXTEND_TRIAL);
    }

    @Test
    void extendTrialRejectsUnreasonableDuration() throws Exception {
        User user = givenUser("abus-" + UUID.randomUUID() + "@byzi.app", SubscriptionStatus.TRIAL);

        // Une faute de frappe ("365" au lieu de "3") ne doit pas offrir un an d'acces.
        mockMvc.perform(post("/admin/users/{id}/extend-trial", user.getId())
                        .param("days", "365").with(asAdmin()).with(csrf()))
                .andExpect(redirectedUrl("/admin/users/" + user.getId()));

        assertThat(userRepository.findById(user.getId()).orElseThrow().getSubscriptionExpiresAt()).isNull();
    }

    @Test
    void markRefundedRevokesAccessAndWritesAudit() throws Exception {
        User user = givenUser("rembours-" + UUID.randomUUID() + "@byzi.app", SubscriptionStatus.ACTIVE);

        mockMvc.perform(post("/admin/users/{id}/mark-refunded", user.getId())
                        .param("reason", "Remboursement App Store").with(asAdmin()).with(csrf()))
                .andExpect(redirectedUrl("/admin/users/" + user.getId()));

        User updated = userRepository.findById(user.getId()).orElseThrow();
        assertThat(updated.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        assertThat(updated.getSubscriptionExpiresAt()).isNull();
        assertThat(auditLogRepository.findAllByTargetUserIdOrderByOccurredAtDesc(user.getId()))
                .extracting("action")
                .containsExactly(AdminAuditService.ACTION_MARK_REFUNDED);
    }

    // ---------------------------------------------------------------- 09.6 RGPD

    @Test
    void deleteAccountRemovesUserButKeepsAuditTrail() throws Exception {
        User user = givenUser("suppr-" + UUID.randomUUID() + "@byzi.app", SubscriptionStatus.ACTIVE);
        UUID userId = user.getId();

        mockMvc.perform(post("/admin/users/{id}/delete", userId).with(asAdmin()).with(csrf()))
                .andExpect(redirectedUrl("/admin/users"));

        assertThat(userRepository.findById(userId)).isEmpty();
        // Le point clef : la trace survit a la suppression de sa cible, sinon l'audit
        // s'effacerait precisement quand il devient le plus utile.
        assertThat(auditLogRepository.findAllByTargetUserIdOrderByOccurredAtDesc(userId))
                .extracting("action")
                .containsExactly(AdminAuditService.ACTION_DELETE_ACCOUNT);
    }

    // ---------------------------------------------------------------- 09.7 audit

    @Test
    void auditPageListsActions() throws Exception {
        User user = givenUser("audit-" + UUID.randomUUID() + "@byzi.app", SubscriptionStatus.TRIAL);
        mockMvc.perform(post("/admin/users/{id}/extend-trial", user.getId())
                .param("days", "3").with(asAdmin()).with(csrf()));

        mockMvc.perform(get("/admin/audit").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/audit"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(AdminAuditService.ACTION_EXTEND_TRIAL)));
    }

    // ---------------------------------------------------------------- CSRF

    @Test
    void mutatingActionWithoutCsrfTokenIsRejected() throws Exception {
        User user = givenUser("csrf-" + UUID.randomUUID() + "@byzi.app", SubscriptionStatus.ACTIVE);

        // Sans jeton CSRF, un site tiers pourrait faire supprimer un compte a l'insu de
        // l'admin connecte, en s'appuyant sur son cookie de session.
        mockMvc.perform(post("/admin/users/{id}/delete", user.getId()).with(asAdmin()))
                .andExpect(status().isForbidden());

        assertThat(userRepository.findById(user.getId())).isPresent();
    }
}
