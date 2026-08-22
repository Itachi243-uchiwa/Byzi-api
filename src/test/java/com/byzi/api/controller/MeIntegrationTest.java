package com.byzi.api.controller;

import com.byzi.api.domain.Role;
import com.byzi.api.domain.SubscriptionStatus;
import com.byzi.api.domain.User;
import com.byzi.api.repository.UserRepository;
import com.byzi.api.security.jwt.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HAUT-01 de l'audit backend : GET /api/v1/me est le seul endpoint qui expose le statut
 * d'abonnement a l'app iOS. Ce qui est verifie en priorite ici, c'est le calcul serveur de
 * hasActiveAccess (EPIC-07) : le client Swift ne doit jamais avoir a comparer
 * subscriptionExpiresAt a une horloge locale, donc ce booleen doit refleter fidelement chaque
 * combinaison statut/date - y compris les cas limites qu'un test unitaire de service pourrait
 * manquer de couvrir via la vraie chaine HTTP + serialisation JSON.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MeIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JwtService jwtService;

    private String tokenFor(User user) {
        return jwtService.generateAccessToken(user.getId(), Role.USER);
    }

    private User save(SubscriptionStatus status, Instant expiresAt, String email) {
        return userRepository.save(User.builder()
                .id(UUID.randomUUID())
                .appleSub("apple-sub-" + UUID.randomUUID())
                .email(email)
                .role(Role.USER)
                .subscriptionStatus(status)
                .subscriptionExpiresAt(expiresAt)
                .build());
    }

    @Test
    void meRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void freshTrialAccountWithoutConfirmedExpirationHasNoAccess() throws Exception {
        // Statut par defaut a la creation du compte (AuthService.createUser) : TRIAL sans
        // subscriptionExpiresAt tant qu'aucun webhook RevenueCat n'a confirme l'essai.
        User user = save(SubscriptionStatus.TRIAL, null, "trial@byzi.app");

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + tokenFor(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(user.getId().toString()))
                .andExpect(jsonPath("$.email").value("trial@byzi.app"))
                .andExpect(jsonPath("$.subscriptionStatus").value("TRIAL"))
                .andExpect(jsonPath("$.subscriptionExpiresAt").doesNotExist())
                .andExpect(jsonPath("$.hasActiveAccess").value(false));
    }

    @Test
    void activeSubscriptionWithFutureExpirationHasAccess() throws Exception {
        User user = save(SubscriptionStatus.ACTIVE, Instant.now().plus(10, ChronoUnit.DAYS), "active@byzi.app");

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + tokenFor(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasActiveAccess").value(true));
    }

    @Test
    void activeStatusWithPastExpirationHasNoAccess() throws Exception {
        // L'app iOS ne doit jamais faire cette comparaison elle-meme (EPIC-07) : c'est
        // exactement ce que ce test verifie cote serveur, de bout en bout HTTP.
        User user = save(SubscriptionStatus.ACTIVE, Instant.now().minus(1, ChronoUnit.DAYS), "stale@byzi.app");

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + tokenFor(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasActiveAccess").value(false));
    }

    @Test
    void gracePeriodHasAccessRegardlessOfExpirationDate() throws Exception {
        // Convention alignee sur AdminDashboardService.POST_TRIAL_STATUSES : GRACE_PERIOD est
        // traite comme "converti", au meme titre qu'ACTIVE - l'utilisateur a souscrit, seul le
        // prelevement a echoue.
        User user = save(SubscriptionStatus.GRACE_PERIOD, Instant.now().minus(1, ChronoUnit.DAYS), "grace@byzi.app");

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + tokenFor(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptionStatus").value("GRACE_PERIOD"))
                .andExpect(jsonPath("$.hasActiveAccess").value(true));
    }

    @Test
    void expiredStatusNeverHasAccessEvenWithFutureDate() throws Exception {
        // Date incoherente volontairement testee : le statut EXPIRED doit l'emporter dans tous
        // les cas, la date ne doit jamais pouvoir "sauver" un acces refuse par le statut.
        User user = save(SubscriptionStatus.EXPIRED, Instant.now().plus(30, ChronoUnit.DAYS), "expired@byzi.app");

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + tokenFor(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasActiveAccess").value(false));
    }

    @Test
    void meReturnsNotFoundWhenUnderlyingAccountWasDeleted() throws Exception {
        // Meme scenario que AuthAndAccountIntegrationTest#deletedAccountTokenNoLongerGrantsAccess :
        // le JWT reste cryptographiquement valide apres la suppression RGPD du compte, jusqu'a
        // son expiration naturelle.
        User user = save(SubscriptionStatus.TRIAL, null, "todelete@byzi.app");
        String token = tokenFor(user);
        userRepository.deleteById(user.getId());

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }
}
