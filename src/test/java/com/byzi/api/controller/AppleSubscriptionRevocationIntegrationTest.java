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
 * POST /api/v1/me/subscription/apple/revoked - story 07.9.
 * <p>
 * Ce que ces tests protegent : un compte rembourse conservait l'acces jusqu'a la date
 * d'expiration initiale, soit <b>jusqu'a un an</b> sur la formule annuelle. Le client excluait
 * bien la transaction revoquee de {@code currentEntitlements}, mais rien ne le disait au
 * serveur, et la porte de l'app s'ouvre des qu'UNE des deux sources dit oui.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AppleSubscriptionRevocationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private SubscriptionEventRepository subscriptionEventRepository;
    @Autowired
    private JwtService jwtService;

    private User givenSubscriber(Instant expiresAt) {
        return userRepository.save(User.builder()
                .id(UUID.randomUUID())
                .appleSub("apple-sub-" + UUID.randomUUID())
                .role(Role.USER)
                .subscriptionStatus(SubscriptionStatus.ACTIVE)
                .subscriptionExpiresAt(expiresAt)
                .build());
    }

    private String tokenFor(User user) {
        return jwtService.generateAccessToken(user.getId(), Role.USER);
    }

    private String payload(String transactionId, Instant revokedAt) {
        return """
                {"transactionId":"%s","revokedAt":"%s"}
                """.formatted(transactionId, revokedAt.toString());
    }

    /**
     * Le coeur : l'acces tombe IMMEDIATEMENT, et l'expiration est effacee plutot que reculee.
     * Un remboursement ne laisse pas courir un terme.
     */
    @Test
    void aRevocationClosesAccessImmediately() throws Exception {
        Instant farFuture = Instant.now().plus(300, ChronoUnit.DAYS);
        User user = givenSubscriber(farFuture);

        mockMvc.perform(post("/api/v1/me/subscription/apple/revoked")
                        .header("Authorization", "Bearer " + tokenFor(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("2000000123456789", Instant.now())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasActiveAccess").value(false));

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getSubscriptionStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        assertThat(reloaded.getSubscriptionExpiresAt())
                .as("un remboursement coupe l'acces maintenant, il ne recule pas le terme")
                .isNull();
    }

    /**
     * L'app rejoue ses rapports a chaque passage au premier plan : deux envois de la meme
     * revocation ne doivent produire qu'une seule trace.
     */
    @Test
    void theSameRevocationIsAppliedOnlyOnce() throws Exception {
        User user = givenSubscriber(Instant.now().plus(30, ChronoUnit.DAYS));
        String body = payload("2000000987654321", Instant.now());
        long before = subscriptionEventRepository.count();

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/me/subscription/apple/revoked")
                            .header("Authorization", "Bearer " + tokenFor(user))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());
        }

        assertThat(subscriptionEventRepository.count() - before).isEqualTo(1);
    }

    /**
     * La revocation d'une transaction DEJA rapportee comme achat doit passer.
     * <p>
     * C'est le cas normal, et le piege du dedoublonnage : achat et revocation partagent le meme
     * {@code Transaction.id}. Sans prefixe distinct dans l'{@code eventId}, la revocation serait
     * prise pour un doublon de l'achat et silencieusement ignoree - c'est-a-dire exactement le
     * scenario qu'on veut couvrir.
     */
    @Test
    void revokingAnAlreadyReportedPurchaseIsNotMistakenForADuplicate() throws Exception {
        User user = givenSubscriber(null);
        String transactionId = "2000000555555555";
        Instant expiresAt = Instant.now().plus(365, ChronoUnit.DAYS);

        mockMvc.perform(post("/api/v1/me/subscription/apple")
                        .header("Authorization", "Bearer " + tokenFor(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transactionId":"%s","productId":"dopamyn.app.premium.yearly",
                                 "expiresAt":"%s","trialPeriod":false}
                                """.formatted(transactionId, expiresAt)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasActiveAccess").value(true));

        mockMvc.perform(post("/api/v1/me/subscription/apple/revoked")
                        .header("Authorization", "Bearer " + tokenFor(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(transactionId, Instant.now())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasActiveAccess").value(false));

        assertThat(userRepository.findById(user.getId()).orElseThrow().getSubscriptionStatus())
                .isEqualTo(SubscriptionStatus.EXPIRED);
    }

    /** Route authentifiee : sans jeton, on ne revoque rien - meme pour soi-meme. */
    @Test
    void anonymousCallersAreRejected() throws Exception {
        mockMvc.perform(post("/api/v1/me/subscription/apple/revoked")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("2000000111111111", Instant.now())))
                .andExpect(status().isUnauthorized());
    }
}
