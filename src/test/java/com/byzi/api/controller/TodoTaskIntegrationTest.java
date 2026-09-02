package com.byzi.api.controller;

import com.byzi.api.domain.Role;
import com.byzi.api.domain.User;
import com.byzi.api.dto.todo.TodoTaskRequest;
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
 * To-do list hebdomadaire (backlog app 0ter T9). Meme contrat que les autres ressources
 * synchronisees : scope par userId, last-write-wins, tombstone visible dans le delta.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TodoTaskIntegrationTest {

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

    private String body(String title, boolean done, Instant clientUpdatedAt) {
        return objectMapper.writeValueAsString(new TodoTaskRequest(
                title, null, "2026-08-31", "2026-09-06", done, done ? clientUpdatedAt : null, clientUpdatedAt));
    }

    private void upsert(String token, UUID id, String payload, int expectedStatus) throws Exception {
        mockMvc.perform(put("/api/v1/todos/{id}", id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void createsThenReadsBackTask() throws Exception {
        UUID id = UUID.randomUUID();
        upsert(tokenA, id, body("Montage du contenu IDV", false, Instant.now()), 200);

        mockMvc.perform(get("/api/v1/todos/{id}", id).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Montage du contenu IDV"))
                .andExpect(jsonPath("$.weekKey").value("2026-08-31"))
                .andExpect(jsonPath("$.done").value(false))
                .andExpect(jsonPath("$.doneAt").doesNotExist());
    }

    @Test
    void rejectsMalformedWeekKey() throws Exception {
        String payload = objectMapper.writeValueAsString(new TodoTaskRequest(
                "Tache", null, "semaine-36", null, false, null, Instant.now()));
        upsert(tokenA, UUID.randomUUID(), payload, 400);
    }

    @Test
    void rejectsBlankTitle() throws Exception {
        String payload = objectMapper.writeValueAsString(new TodoTaskRequest(
                "   ", null, "2026-08-31", null, false, null, Instant.now()));
        upsert(tokenA, UUID.randomUUID(), payload, 400);
    }

    /** Une tache decochee ne doit pas garder de date de completion orpheline. */
    @Test
    void clearsDoneAtWhenTaskIsUnchecked() throws Exception {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        upsert(tokenA, id, body("Finir le site", true, now), 200);

        mockMvc.perform(get("/api/v1/todos/{id}", id).header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.doneAt").exists());

        upsert(tokenA, id, body("Finir le site", false, now.plus(1, ChronoUnit.MINUTES)), 200);

        mockMvc.perform(get("/api/v1/todos/{id}", id).header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.done").value(false))
                .andExpect(jsonPath("$.doneAt").doesNotExist());
    }

    /** Last-write-wins : une ecriture plus ancienne que l'etat serveur ne s'applique pas. */
    @Test
    void staleWriteIsIgnored() throws Exception {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        upsert(tokenA, id, body("Version recente", false, now), 200);
        upsert(tokenA, id, body("Version ancienne", false, now.minus(1, ChronoUnit.HOURS)), 200);

        mockMvc.perform(get("/api/v1/todos/{id}", id).header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.title").value("Version recente"));
    }

    /** Defense IDOR : le PUT d'un autre compte ne doit jamais ecraser la tache de la victime. */
    @Test
    void otherAccountCannotOverwriteOrReadTask() throws Exception {
        UUID id = UUID.randomUUID();
        upsert(tokenA, id, body("Tache de A", false, Instant.now()), 200);

        upsert(tokenB, id, body("Tentative de B", false, Instant.now()), 200);

        mockMvc.perform(get("/api/v1/todos/{id}", id).header("Authorization", "Bearer " + tokenA))
                .andExpect(jsonPath("$.title").value("Tache de A"));

        mockMvc.perform(get("/api/v1/todos/{id}", id).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    /** Suppression logique : absente des listes, presente dans le delta comme tombstone. */
    @Test
    void deletedTaskLeavesATombstoneInTheDelta() throws Exception {
        UUID id = UUID.randomUUID();
        Instant before = Instant.now().minus(1, ChronoUnit.MINUTES);
        upsert(tokenA, id, body("A supprimer", false, Instant.now()), 200);

        mockMvc.perform(delete("/api/v1/todos/{id}", id).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/todos").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id=='" + id + "')]").isEmpty());

        mockMvc.perform(get("/api/v1/todos")
                        .param("updatedSince", before.toString())
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id=='" + id + "')].deletedAt").isNotEmpty());
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/todos")).andExpect(status().isUnauthorized());
    }
}
