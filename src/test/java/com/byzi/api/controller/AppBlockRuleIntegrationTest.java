package com.byzi.api.controller;

import com.byzi.api.domain.Role;
import com.byzi.api.domain.User;
import com.byzi.api.dto.blockrule.AppBlockRuleRequest;
import com.byzi.api.repository.UserRepository;
import com.byzi.api.security.jwt.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Story 01.4 sur les regles de blocage, plus l'AC de l'EPIC-01 : "selectionData est stocke tel
 * quel cote serveur, jamais deserialise/interprete".
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AppBlockRuleIntegrationTest {

    /** Blob opaque produit par iOS (FamilyActivitySelection encodee) : le serveur n'en sait rien. */
    private static final String OPAQUE_BLOB = "eyJhcHBUb2tlbnMiOlsiQUFBQ0FnRUEiXX0=";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private ObjectMapper objectMapper;

    private String tokenA;
    private String tokenB;

    @BeforeEach
    void setUp() {
        tokenA = tokenFor(newUser());
        tokenB = tokenFor(newUser());
    }

    private User newUser() {
        return userRepository.save(User.builder()
                .id(UUID.randomUUID())
                .appleSub("apple-sub-" + UUID.randomUUID())
                .role(Role.USER)
                .build());
    }

    private String tokenFor(User user) {
        return jwtService.generateAccessToken(user.getId(), Role.USER);
    }

    private String body(String selectionData, Integer limit, String start, String end,
                        boolean active, Instant clientUpdatedAt) {
        return objectMapper.writeValueAsString(new AppBlockRuleRequest(
                selectionData, limit, start, end, active, clientUpdatedAt));
    }

    private void upsert(String token, UUID id, String payload, int expectedStatus) throws Exception {
        mockMvc.perform(put("/api/v1/app-block-rules/{id}", id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void storesSelectionDataVerbatim() throws Exception {
        UUID id = UUID.randomUUID();
        upsert(tokenA, id, body(OPAQUE_BLOB, 60, "09:00", "18:00", true, Instant.now()), 200);

        // Le blob ressort octet pour octet : aucune normalisation, aucun decodage.
        mockMvc.perform(get("/api/v1/app-block-rules/{id}", id)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectionData").value(OPAQUE_BLOB))
                .andExpect(jsonPath("$.dailyLimitMinutes").value(60))
                .andExpect(jsonPath("$.scheduleStart").value("09:00"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void ruleWithoutDailyLimitIsAccepted() throws Exception {
        // Une regle peut n'avoir qu'une plage horaire, sans quota de minutes.
        UUID id = UUID.randomUUID();
        upsert(tokenA, id, body(OPAQUE_BLOB, null, "09:00", "18:00", true, Instant.now()), 200);

        mockMvc.perform(get("/api/v1/app-block-rules/{id}", id)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.dailyLimitMinutes").doesNotExist());
    }

    @Test
    void updatesExistingRule() throws Exception {
        UUID id = UUID.randomUUID();
        upsert(tokenA, id, body(OPAQUE_BLOB, 60, "09:00", "18:00", true,
                Instant.now().minus(1, ChronoUnit.HOURS)), 200);
        upsert(tokenA, id, body(OPAQUE_BLOB, 30, "10:00", "12:00", false, Instant.now()), 200);

        mockMvc.perform(get("/api/v1/app-block-rules/{id}", id)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.dailyLimitMinutes").value(30))
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void staleClientWriteDoesNotOverwrite() throws Exception {
        UUID id = UUID.randomUUID();
        upsert(tokenA, id, body(OPAQUE_BLOB, 60, "09:00", "18:00", true, Instant.now()), 200);
        upsert(tokenA, id, body(OPAQUE_BLOB, 5, "01:00", "02:00", false,
                Instant.now().minus(2, ChronoUnit.DAYS)), 200);

        mockMvc.perform(get("/api/v1/app-block-rules/{id}", id)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.dailyLimitMinutes").value(60));
    }

    @Test
    void listReturnsOnlyOwnRules() throws Exception {
        upsert(tokenA, UUID.randomUUID(), body(OPAQUE_BLOB, 60, null, null, true, Instant.now()), 200);

        mockMvc.perform(get("/api/v1/app-block-rules")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void otherUserCannotReadOrDeleteRule() throws Exception {
        UUID id = UUID.randomUUID();
        upsert(tokenA, id, body(OPAQUE_BLOB, 60, null, null, true, Instant.now()), 200);

        mockMvc.perform(get("/api/v1/app-block-rules/{id}", id)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/app-block-rules/{id}", id)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    void ownerCanDeleteRule() throws Exception {
        UUID id = UUID.randomUUID();
        upsert(tokenA, id, body(OPAQUE_BLOB, 60, null, null, true, Instant.now()), 200);

        mockMvc.perform(delete("/api/v1/app-block-rules/{id}", id)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());
    }

    @Test
    void blankSelectionDataIsRejected() throws Exception {
        upsert(tokenA, UUID.randomUUID(), body("   ", 60, null, null, true, Instant.now()), 400);
    }

    @Test
    void malformedScheduleIsRejected() throws Exception {
        // Le format HH:mm est la seule chose que le serveur valide sur les horaires.
        upsert(tokenA, UUID.randomUUID(), body(OPAQUE_BLOB, 60, "9h", "18:00", true, Instant.now()), 400);
    }

    @Test
    void negativeDailyLimitIsRejected() throws Exception {
        upsert(tokenA, UUID.randomUUID(), body(OPAQUE_BLOB, -10, null, null, true, Instant.now()), 400);
    }

    @Test
    void cappedPageSizeProtectsAgainstOversizedResponses() throws Exception {
        // @PageableDefault(size = 50) ne fixe que la valeur PAR DEFAUT. Sans plafond, ce seul
        // appel chargerait 2 000 regles dont le selectionData peut peser 200 000 caracteres -
        // de quoi faire tomber la JVM en une requete. Le plafond vit dans application.yml
        // (spring.data.web.pageable.max-page-size), donc hors de portee du code : ce test est
        // ce qui empeche de le perdre lors d'un remaniement de configuration.
        mockMvc.perform(get("/api/v1/app-block-rules")
                        .param("size", "2000")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }
}
