package com.byzi.api.controller;

import com.byzi.api.domain.Role;
import com.byzi.api.domain.SubscriptionStatus;
import com.byzi.api.domain.User;
import com.byzi.api.repository.SubscriptionEventRepository;
import com.byzi.api.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Couvre l'AC de l'EPIC-07 : l'etat d'abonnement vient du serveur (webhook RevenueCat),
 * jamais d'une date calculee sur l'appareil.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RevenueCatWebhookIntegrationTest {

    private static final String SECRET = "test-revenuecat-webhook-secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SubscriptionEventRepository subscriptionEventRepository;

    private User givenUser() {
        return userRepository.save(User.builder()
                .id(UUID.randomUUID())
                .appleSub("apple-sub-" + UUID.randomUUID())
                .role(Role.USER)
                .subscriptionStatus(SubscriptionStatus.TRIAL)
                .build());
    }

    private String payload(String eventId, String type, UUID userId, String periodType, Long expirationMs) {
        return """
                {
                  "event": {
                    "id": "%s",
                    "type": "%s",
                    "app_user_id": "%s",
                    "period_type": "%s",
                    "expiration_at_ms": %s,
                    "event_timestamp_ms": %d,
                    "store": "APP_STORE",
                    "un_champ_que_revenuecat_ajoutera_un_jour": "ignore"
                  }
                }
                """.formatted(eventId, type, userId, periodType,
                expirationMs == null ? "null" : expirationMs.toString(),
                Instant.now().toEpochMilli());
    }

    private void postWebhook(String body, String authorization, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/revenuecat")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void initialPurchaseActivatesSubscription() throws Exception {
        User user = givenUser();
        long expiration = Instant.now().plusSeconds(2_592_000).toEpochMilli();

        postWebhook(payload(UUID.randomUUID().toString(), "INITIAL_PURCHASE", user.getId(), "NORMAL", expiration),
                SECRET, 200);

        User updated = userRepository.findById(user.getId()).orElseThrow();
        assertThat(updated.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(updated.getSubscriptionExpiresAt()).isEqualTo(Instant.ofEpochMilli(expiration));
    }

    @Test
    void expirationDowngradesSubscription() throws Exception {
        User user = givenUser();

        postWebhook(payload(UUID.randomUUID().toString(), "EXPIRATION", user.getId(), "NORMAL", null),
                SECRET, 200);

        assertThat(userRepository.findById(user.getId()).orElseThrow().getSubscriptionStatus())
                .isEqualTo(SubscriptionStatus.EXPIRED);
    }

    @Test
    void webhookWithoutValidSecretIsRejectedAndChangesNothing() throws Exception {
        User user = givenUser();
        String body = payload(UUID.randomUUID().toString(), "INITIAL_PURCHASE", user.getId(), "NORMAL", null);

        postWebhook(body, "mauvais-secret", 401);

        // Le point critique : sans ce controle, n'importe qui pourrait s'octroyer un abonnement.
        assertThat(userRepository.findById(user.getId()).orElseThrow().getSubscriptionStatus())
                .isEqualTo(SubscriptionStatus.TRIAL);
    }

    @Test
    void webhookWithoutAuthorizationHeaderIsRejected() throws Exception {
        User user = givenUser();
        mockMvc.perform(post("/api/v1/webhooks/revenuecat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(UUID.randomUUID().toString(), "RENEWAL", user.getId(), "NORMAL", null)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void replayedEventIsAcknowledgedButAppliedOnlyOnce() throws Exception {
        User user = givenUser();
        String eventId = UUID.randomUUID().toString();
        long expiration = Instant.now().plusSeconds(2_592_000).toEpochMilli();
        String body = payload(eventId, "INITIAL_PURCHASE", user.getId(), "NORMAL", expiration);

        postWebhook(body, SECRET, 200);
        // RevenueCat rejoue tant qu'il n'a pas d'accuse de reception : le rejeu doit renvoyer
        // 200 (sinon boucle infinie) sans reappliquer la transition.
        postWebhook(body, SECRET, 200);

        assertThat(subscriptionEventRepository.findAllByUser_IdOrderByOccurredAtDesc(user.getId())).hasSize(1);
    }

    @Test
    void unknownEventTypeIsAcknowledgedWithoutChangingStatus() throws Exception {
        User user = givenUser();

        postWebhook(payload(UUID.randomUUID().toString(), "SUBSCRIBER_ALIAS", user.getId(), "NORMAL", null),
                SECRET, 200);

        assertThat(userRepository.findById(user.getId()).orElseThrow().getSubscriptionStatus())
                .isEqualTo(SubscriptionStatus.TRIAL);
        assertThat(subscriptionEventRepository.findAllByUser_IdOrderByOccurredAtDesc(user.getId())).isEmpty();
    }

    @Test
    void webhookForUnknownUserIsAcknowledged() throws Exception {
        // Compte supprime (RGPD) alors qu'un webhook etait encore en vol : on acquitte sans
        // recreer de compte fantome.
        postWebhook(payload(UUID.randomUUID().toString(), "RENEWAL", UUID.randomUUID(), "NORMAL", null),
                SECRET, 200);
    }

    @Test
    void anonymousRevenueCatIdIsAcknowledged() throws Exception {
        String body = """
                {"event": {"id": "%s", "type": "RENEWAL", "app_user_id": "$RCAnonymousID:abc123",
                 "event_timestamp_ms": %d}}
                """.formatted(UUID.randomUUID(), Instant.now().toEpochMilli());

        postWebhook(body, SECRET, 200);
    }

    @Test
    void malformedPayloadIsRejected() throws Exception {
        postWebhook("{\"event\": {\"type\": \"RENEWAL\"}}", SECRET, 400);
    }

    @Test
    void trialPurchaseIsRecordedAsTrialNotActive() throws Exception {
        User user = givenUser();

        postWebhook(payload(UUID.randomUUID().toString(), "INITIAL_PURCHASE", user.getId(), "TRIAL", null),
                SECRET, 200);

        // Compter un essai comme ACTIVE fausserait le taux de conversion du dashboard (09.4).
        assertThat(userRepository.findById(user.getId()).orElseThrow().getSubscriptionStatus())
                .isEqualTo(SubscriptionStatus.TRIAL);
    }
}
