package com.byzi.api.controller;

import com.byzi.api.domain.Role;
import com.byzi.api.domain.SessionMode;
import com.byzi.api.domain.User;
import com.byzi.api.dto.session.FocusSessionRequest;
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
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ce test materialise directement l'exigence de la story 01.4 :
 * "un utilisateur ne peut jamais lire/ecrire les donnees d'un autre userId".
 * <p>
 * Il passe par la stack HTTP complete (MockMvc -> filtres Spring Security -> controller ->
 * service -> repository -> H2), pas uniquement par la couche service, pour verifier que
 * la protection tient de bout en bout et pas seulement "sur le papier" dans un test unitaire
 * isole.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FocusSessionOwnershipIntegrationTest {

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
        User userA = userRepository.save(User.builder()
                .id(UUID.randomUUID())
                .appleSub("apple-sub-a-" + UUID.randomUUID())
                .role(Role.USER)
                .build());
        User userB = userRepository.save(User.builder()
                .id(UUID.randomUUID())
                .appleSub("apple-sub-b-" + UUID.randomUUID())
                .role(Role.USER)
                .build());

        tokenA = jwtService.generateAccessToken(userA.getId(), Role.USER);
        tokenB = jwtService.generateAccessToken(userB.getId(), Role.USER);
    }

    @Test
    void userCannotReadAnotherUsersFocusSession() throws Exception {
        UUID sessionId = UUID.randomUUID();
        createSessionAs(tokenA, sessionId);

        // 404, jamais 403 : on ne confirme meme pas a userB que la ressource existe (cf.
        // ResourceNotFoundException) - defense contre l'enumeration de ressources.
        mockMvc.perform(get("/api/v1/focus-sessions/{id}", sessionId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    void userCannotDeleteAnotherUsersFocusSession() throws Exception {
        UUID sessionId = UUID.randomUUID();
        createSessionAs(tokenA, sessionId);

        mockMvc.perform(delete("/api/v1/focus-sessions/{id}", sessionId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        // La ressource doit toujours exister pour son proprietaire legitime : la tentative
        // de userB n'a rien supprime.
        mockMvc.perform(get("/api/v1/focus-sessions/{id}", sessionId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());
    }

    @Test
    void userCannotOverwriteAnotherUsersFocusSessionViaUpsert() throws Exception {
        UUID sessionId = UUID.randomUUID();
        createSessionAs(tokenA, sessionId);

        FocusSessionRequest hostileUpdate = new FocusSessionRequest(
                Instant.now(), Instant.now(), 60, SessionMode.STANDARD, true, Instant.now());

        // userB reutilise le MEME id que la session de userA : doit creer SA PROPRE
        // ressource distincte (scopee a son userId), jamais ecraser celle de userA.
        mockMvc.perform(put("/api/v1/focus-sessions/{id}", sessionId)
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(hostileUpdate)))
                .andExpect(status().isOk());

        // La session originale de userA reste inchangee (pas "completed", pas la duree de userB).
        mockMvc.perform(get("/api/v1/focus-sessions/{id}", sessionId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPathCompletedFalse());
    }

    @Test
    void requestWithoutTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/focus-sessions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void requestWithTamperedTokenIsRejected() throws Exception {
        String tampered = tokenA.substring(0, tokenA.length() - 2) + "xx";
        mockMvc.perform(get("/api/v1/focus-sessions")
                        .header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    private void createSessionAs(String token, UUID sessionId) throws Exception {
        FocusSessionRequest request = new FocusSessionRequest(
                Instant.now(), null, 1500, SessionMode.STANDARD, false, Instant.now());

        mockMvc.perform(put("/api/v1/focus-sessions/{id}", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultMatcher jsonPathCompletedFalse() {
        return org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.completed")
                .value(false);
    }
}