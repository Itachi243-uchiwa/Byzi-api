package com.buzi.api.controller;

import com.buzi.api.domain.Role;
import com.buzi.api.domain.User;
import com.buzi.api.repository.UserRepository;
import com.buzi.api.security.jwt.JwtService;
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
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Story 01.4 (CRUD scope par userId) et 01.5 (last-write-wins) sur les streak records.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StreakRecordIntegrationTest {

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

    private String body(LocalDate day, boolean goalReached, int minutes, Instant clientUpdatedAt) {
        return objectMapper.writeValueAsString(new com.buzi.api.dto.streak.StreakRecordRequest(
                day, goalReached, minutes, clientUpdatedAt));
    }

    private void upsert(String token, UUID id, String payload, int expectedStatus) throws Exception {
        mockMvc.perform(put("/api/v1/streak-records/{id}", id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void createsThenReadsBackStreakRecord() throws Exception {
        UUID id = UUID.randomUUID();
        upsert(tokenA, id, body(LocalDate.now(), true, 90, Instant.now()), 200);

        mockMvc.perform(get("/api/v1/streak-records/{id}", id)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goalReached").value(true))
                .andExpect(jsonPath("$.focusMinutes").value(90));
    }

    @Test
    void listsOwnRecordsOnly() throws Exception {
        upsert(tokenA, UUID.randomUUID(), body(LocalDate.now(), true, 30, Instant.now()), 200);

        mockMvc.perform(get("/api/v1/streak-records")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void updatesExistingRecordForSameDay() throws Exception {
        LocalDate day = LocalDate.now();
        UUID id = UUID.randomUUID();
        upsert(tokenA, id, body(day, false, 10, Instant.now().minus(1, ChronoUnit.HOURS)), 200);

        // Meme jour, autre id client : doit mettre a jour l'enregistrement existant plutot que
        // d'en creer un second (contrainte uk_streak_user_day).
        upsert(tokenA, UUID.randomUUID(), body(day, true, 120, Instant.now()), 200);

        mockMvc.perform(get("/api/v1/streak-records/{id}", id)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.focusMinutes").value(120));
    }

    @Test
    void staleClientWriteIsRejectedByLastWriteWins() throws Exception {
        LocalDate day = LocalDate.now();
        UUID id = UUID.randomUUID();
        upsert(tokenA, id, body(day, true, 100, Instant.now()), 200);

        // Ecriture d'un appareil desynchronise, plus ancienne que l'etat serveur : elle ne
        // doit pas ecraser la valeur la plus recente (story 01.5).
        upsert(tokenA, id, body(day, false, 5, Instant.now().minus(2, ChronoUnit.DAYS)), 200);

        mockMvc.perform(get("/api/v1/streak-records/{id}", id)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.focusMinutes").value(100));
    }

    @Test
    void otherUserCannotReadRecord() throws Exception {
        UUID id = UUID.randomUUID();
        upsert(tokenA, id, body(LocalDate.now(), true, 45, Instant.now()), 200);

        mockMvc.perform(get("/api/v1/streak-records/{id}", id)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    void otherUserCannotDeleteRecord() throws Exception {
        UUID id = UUID.randomUUID();
        upsert(tokenA, id, body(LocalDate.now(), true, 45, Instant.now()), 200);

        mockMvc.perform(delete("/api/v1/streak-records/{id}", id)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/streak-records/{id}", id)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());
    }

    @Test
    void ownerCanDeleteRecord() throws Exception {
        UUID id = UUID.randomUUID();
        upsert(tokenA, id, body(LocalDate.now(), true, 45, Instant.now()), 200);

        mockMvc.perform(delete("/api/v1/streak-records/{id}", id)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/streak-records/{id}", id)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidPayloadIsRejected() throws Exception {
        // date manquante : @NotNull sur le DTO.
        upsert(tokenA, UUID.randomUUID(), "{\"goalReached\": true, \"focusMinutes\": 10}", 400);
    }

    @Test
    void negativeFocusMinutesIsRejected() throws Exception {
        upsert(tokenA, UUID.randomUUID(), body(LocalDate.now(), true, -5, Instant.now()), 400);
    }

    @Test
    void requestWithoutTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/streak-records")).andExpect(status().isUnauthorized());
    }
}
