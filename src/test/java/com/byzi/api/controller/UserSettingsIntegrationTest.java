package com.byzi.api.controller;

import com.byzi.api.domain.Role;
import com.byzi.api.domain.User;
import com.byzi.api.dto.settings.UserSettingsRequest;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Réglages de l'utilisateur (objectif de focus quotidien). Ce qui est vérifié : la ligne est
 * créée à la volée avec les valeurs par défaut, le scope par userId (jamais les réglages d'un
 * autre compte), et le last-write-wins sur updatedAt.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserSettingsIntegrationTest {

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

    private String body(int minutes, Instant clientUpdatedAt) {
        return objectMapper.writeValueAsString(new UserSettingsRequest(minutes, clientUpdatedAt));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/user-settings")).andExpect(status().isUnauthorized());
    }

    @Test
    void getCreatesDefaultRowOnFirstCall() throws Exception {
        mockMvc.perform(get("/api/v1/user-settings").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyGoalMinutes").value(25))
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    void putUpdatesAndPersists() throws Exception {
        mockMvc.perform(put("/api/v1/user-settings")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(60, Instant.now())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyGoalMinutes").value(60));

        mockMvc.perform(get("/api/v1/user-settings").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyGoalMinutes").value(60));
    }

    @Test
    void rejectsOutOfRangeGoal() throws Exception {
        mockMvc.perform(put("/api/v1/user-settings")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(999, Instant.now())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void settingsAreScopedPerUser() throws Exception {
        mockMvc.perform(put("/api/v1/user-settings")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(90, Instant.now())))
                .andExpect(status().isOk());

        // B n'a jamais rien écrit : il voit le défaut, pas les 90 de A.
        mockMvc.perform(get("/api/v1/user-settings").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyGoalMinutes").value(25));
    }

    @Test
    void staleClientUpdateIsIgnored() throws Exception {
        Instant now = Instant.now();

        mockMvc.perform(put("/api/v1/user-settings")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(60, now)))
                .andExpect(status().isOk());

        // Une écriture plus ancienne que l'updatedAt serveur ne doit pas gagner.
        mockMvc.perform(put("/api/v1/user-settings")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(15, now.minus(1, ChronoUnit.HOURS))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyGoalMinutes").value(60));
    }
}
