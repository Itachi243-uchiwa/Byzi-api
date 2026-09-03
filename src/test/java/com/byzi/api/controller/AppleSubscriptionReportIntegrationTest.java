package com.byzi.api.controller;

import com.byzi.api.domain.Role;
import com.byzi.api.domain.SubscriptionStatus;
import com.byzi.api.domain.User;
import com.byzi.api.repository.SubscriptionEventRepository;
import com.byzi.api.repository.UserRepository;
import com.byzi.api.security.jwt.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/v1/me/subscription/apple : ce que l'app iOS rapporte apres lecture de
 * Transaction.currentEntitlements (StoreKit 2 pur, pas de SDK RevenueCat cote client, decision
 * 2026-09-03). Cf. la javadoc d'AppleSubscriptionReportRequest et
 * SubscriptionService.applyClientReportedApplePurchase pour la nuance avec un webhook verifie.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AppleSubscriptionReportIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private SubscriptionEventRepository subscriptionEventRepository;
    @Autowired
    private JwtService jwtService;

    private User givenUser() {
        return userRepository.save(User.builder()
                .id(UUID.randomUUID())
                .appleSub("apple-sub-" + UUID.randomUUID())
                .role(Role.USER)
                .subscriptionStatus(SubscriptionStatus.TRIAL)
                .build());
    }

    private String tokenFor(User user) {
        return jwtService.generateAccessToken(user.getId(), Role.USER);
    }

    private String payload(String transactionId, String productId, Instant expiresAt, boolean trial) {
        return """
                {
                  "transactionId": "%s",
                  "productId": "%s",
                  "expiresAt": "%s",
                  "trialPeriod": %s
                }
                """.formatted(transactionId, productId, expiresAt, trial);
    }

    @Test
    void reportWithoutAuthenticationIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/me/subscription/apple")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(UUID.randomUUID().toString(), "monthly",
                                Instant.now().plusSeconds(2_592_000), false)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void activePurchaseGrantsAccessImmediately() throws Exception {
        User user = givenUser();
        Instant expiresAt = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);

        mockMvc.perform(post("/api/v1/me/subscription/apple")
                        .header("Authorization", "Bearer " + tokenFor(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("1000000900000001", "premium.monthly", expiresAt, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptionStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.hasActiveAccess").value(true));

        User updated = userRepository.findById(user.getId()).orElseThrow();
        assertThat(updated.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(updated.getSubscriptionExpiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void trialPurchaseIsRecordedAsTrialNotActive() throws Exception {
        User user = givenUser();

        mockMvc.perform(post("/api/v1/me/subscription/apple")
                        .header("Authorization", "Bearer " + tokenFor(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("1000000900000002", "premium.yearly",
                                Instant.now().plus(3, ChronoUnit.DAYS), true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptionStatus").value("TRIAL"))
                .andExpect(jsonPath("$.hasActiveAccess").value(true));
    }

    @Test
    void userCanOnlyReportForThemselves() throws Exception {
        // Le corps de la requete ne porte aucun userId : c'est le JWT qui tranche. Un compte ne
        // peut donc jamais accorder d'acces a un autre, quoi qu'il envoie.
        User user = givenUser();
        User victim = givenUser();

        mockMvc.perform(post("/api/v1/me/subscription/apple")
                        .header("Authorization", "Bearer " + tokenFor(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("1000000900000003", "premium.monthly",
                                Instant.now().plusSeconds(2_592_000), false)))
                .andExpect(status().isOk());

        assertThat(userRepository.findById(victim.getId()).orElseThrow().getSubscriptionStatus())
                .isEqualTo(SubscriptionStatus.TRIAL);
    }

    @Test
    void replayedTransactionIsAcknowledgedButAppliedOnlyOnce() throws Exception {
        User user = givenUser();
        String body = payload("1000000900000004", "premium.monthly",
                Instant.now().plusSeconds(2_592_000), false);
        String auth = "Bearer " + tokenFor(user);

        mockMvc.perform(post("/api/v1/me/subscription/apple")
                        .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        // Reouverture de l'app, meme transaction toujours active : pas une erreur, juste un
        // rapport redondant, silencieusement ignore (comme un rejeu de webhook RevenueCat).
        mockMvc.perform(post("/api/v1/me/subscription/apple")
                        .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        assertThat(subscriptionEventRepository.findAllByUser_IdOrderByOccurredAtDesc(user.getId())).hasSize(1);
    }

    @Test
    void expiredExpiryDateStillPersistsButGrantsNoAccess() throws Exception {
        // L'app ne rapporte normalement que des entitlements actifs (StoreKit ne les liste que
        // tant qu'ils le sont), mais le serveur ne doit pas faire confiance a un statut
        // "ACTIVE" les yeux fermes : c'est hasActiveAccess, recalcule ici, qui tranche.
        User user = givenUser();

        mockMvc.perform(post("/api/v1/me/subscription/apple")
                        .header("Authorization", "Bearer " + tokenFor(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("1000000900000005", "premium.monthly",
                                Instant.now().minusSeconds(60), false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasActiveAccess").value(false));
    }

    @Test
    void malformedPayloadIsRejected() throws Exception {
        User user = givenUser();

        mockMvc.perform(post("/api/v1/me/subscription/apple")
                        .header("Authorization", "Bearer " + tokenFor(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\": \"premium.monthly\"}"))
                .andExpect(status().isBadRequest());
    }
}
