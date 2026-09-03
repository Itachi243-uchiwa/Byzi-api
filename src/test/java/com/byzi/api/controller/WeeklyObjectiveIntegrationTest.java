package com.byzi.api.controller;

import com.byzi.api.domain.Role;
import com.byzi.api.domain.User;
import com.byzi.api.dto.objective.WeeklyObjectiveRequest;
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
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Objectifs hebdomadaires (backlog app 0ter T10). Au-dela du contrat de synchronisation
 * commun, deux regles specifiques a cette ressource sont verifiees ici : la normalisation des
 * ids lies (sinon la synchronisation ferait rebondir deux appareils l'un contre l'autre) et
 * l'effacement de achievedAt quand l'objectif redevient non atteint (sinon l'app compterait
 * une serie pour un objectif abandonne).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WeeklyObjectiveIntegrationTest {

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

    private String body(String title, Set<UUID> linked, boolean achieved, Instant clientUpdatedAt) {
        return objectMapper.writeValueAsString(new WeeklyObjectiveRequest(
                title, "2026-08-31", linked, achieved, achieved ? clientUpdatedAt : null,
                clientUpdatedAt, clientUpdatedAt));
    }

    private void upsert(String token, UUID id, String payload, int expectedStatus) throws Exception {
        mockMvc.perform(put("/api/v1/weekly-objectives/{id}", id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void createsThenReadsBackObjective() throws Exception {
        UUID id = UUID.randomUUID();
        UUID task = UUID.randomUUID();
        upsert(tokenA, id, body("Faire le montage IDV", Set.of(task), false, Instant.now()), 200);

        mockMvc.perform(get("/api/v1/weekly-objectives/{id}", id).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Faire le montage IDV"))
                .andExpect(jsonPath("$.weekKey").value("2026-08-31"))
                .andExpect(jsonPath("$.linkedTaskIds[0]").value(task.toString()))
                .andExpect(jsonPath("$.achieved").value(false));
    }

    /**
     * Les ids lies doivent ressortir TRIES quel que soit l'ordre d'envoi : deux appareils qui
     * envoient la meme selection dans un ordre different ne doivent pas se voir mutuellement
     * comme une modification.
     */
    @Test
    void linkedTaskIdsAreNormalisedRegardlessOfInputOrder() throws Exception {
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");

        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        upsert(tokenA, idA, body("A", new LinkedHashSet<>(java.util.List.of(second, first)), false, Instant.now()), 200);
        upsert(tokenA, idB, body("B", new LinkedHashSet<>(java.util.List.of(first, second)), false, Instant.now()), 200);

        for (UUID id : java.util.List.of(idA, idB)) {
            mockMvc.perform(get("/api/v1/weekly-objectives/{id}", id).header("Authorization", "Bearer " + tokenA))
                    .andExpect(jsonPath("$.linkedTaskIds[0]").value(first.toString()))
                    .andExpect(jsonPath("$.linkedTaskIds[1]").value(second.toString()));
        }
    }

    /** Un objectif redevenu non atteint ne garde pas de date d'atteinte : c'est elle qui compte pour la serie. */
    @Test
    void clearsAchievedAtWhenObjectiveIsUnachieved() throws Exception {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        upsert(tokenA, id, body("Objectif", Set.of(), true, now), 200);

        mockMvc.perform(get("/api/v1/weekly-objectives/{id}", id).header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.achievedAt").exists());

        upsert(tokenA, id, body("Objectif", Set.of(), false, now.plus(1, ChronoUnit.MINUTES)), 200);

        mockMvc.perform(get("/api/v1/weekly-objectives/{id}", id).header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.achieved").value(false))
                .andExpect(jsonPath("$.achievedAt").doesNotExist());
    }

    @Test
    void rejectsMalformedWeekKeyAndBlankTitle() throws Exception {
        upsert(tokenA, UUID.randomUUID(), objectMapper.writeValueAsString(new WeeklyObjectiveRequest(
                "Objectif", "S36", Set.of(), false, null, Instant.now(), null)), 400);
        upsert(tokenA, UUID.randomUUID(), objectMapper.writeValueAsString(new WeeklyObjectiveRequest(
                "  ", "2026-08-31", Set.of(), false, null, Instant.now(), null)), 400);
    }

    @Test
    void staleWriteIsIgnored() throws Exception {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        upsert(tokenA, id, body("Version recente", Set.of(), false, now), 200);
        upsert(tokenA, id, body("Version ancienne", Set.of(), false, now.minus(1, ChronoUnit.HOURS)), 200);

        mockMvc.perform(get("/api/v1/weekly-objectives/{id}", id).header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.title").value("Version recente"));
    }

    @Test
    void otherAccountCannotOverwriteOrReadObjective() throws Exception {
        UUID id = UUID.randomUUID();
        upsert(tokenA, id, body("Objectif de A", Set.of(), false, Instant.now()), 200);
        upsert(tokenB, id, body("Tentative de B", Set.of(), false, Instant.now()), 200);

        mockMvc.perform(get("/api/v1/weekly-objectives/{id}", id).header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.title").value("Objectif de A"));
        mockMvc.perform(get("/api/v1/weekly-objectives/{id}", id).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletedObjectiveLeavesATombstoneInTheDelta() throws Exception {
        UUID id = UUID.randomUUID();
        Instant before = Instant.now().minus(1, ChronoUnit.MINUTES);
        upsert(tokenA, id, body("A supprimer", Set.of(), false, Instant.now()), 200);

        mockMvc.perform(delete("/api/v1/weekly-objectives/{id}", id).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/weekly-objectives").header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.content[?(@.id=='" + id + "')]").isEmpty());

        mockMvc.perform(get("/api/v1/weekly-objectives")
                        .param("updatedSince", before.toString())
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.content[?(@.id=='" + id + "')].deletedAt").isNotEmpty());
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/weekly-objectives")).andExpect(status().isUnauthorized());
    }
}
