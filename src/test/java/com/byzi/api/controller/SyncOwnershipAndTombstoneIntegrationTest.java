package com.byzi.api.controller;

import com.byzi.api.domain.Role;
import com.byzi.api.domain.User;
import com.byzi.api.dto.blockrule.AppBlockRuleRequest;
import com.byzi.api.dto.streak.StreakRecordRequest;
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
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pendant de {@link FocusSessionOwnershipIntegrationTest} pour les deux autres entites
 * synchronisees, et verification du contrat de tombstones.
 * <p>
 * Ces tests couvrent une regression reelle (audit backend - BLOQ-01) : les streaks et les
 * regles de blocage n'avaient PAS le garde-fou que les sessions de focus possedaient deja.
 * Un PUT portant l'id d'une ressource appartenant a un autre compte ne produisait pas une
 * erreur mais un merge Hibernate, qui ecrasait la ligne de la victime - et, sur les streaks,
 * la lui retirait carrement puisque user_id etait alors modifiable.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SyncOwnershipAndTombstoneIntegrationTest {

    private static final String OPAQUE_SELECTION = "eyJhcHBUb2tlbnMiOltdfQ==";

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

    // ------------------------------------------------------------------ BLOQ-01

    @Test
    void userCannotOverwriteAnotherUsersStreakViaUpsert() throws Exception {
        UUID streakId = UUID.randomUUID();
        LocalDate day = LocalDate.now();

        putStreak(tokenA, streakId, day, 100, Instant.now());

        // userB rejoue le MEME id. Sans le garde-fou, save() partait en merge et reaffectait
        // la ligne de userA a userB : la victime perdait purement et simplement son streak.
        putStreak(tokenB, streakId, day, 999, Instant.now().plusSeconds(60));

        mockMvc.perform(get("/api/v1/streak-records/{id}", streakId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.focusMinutes").value(100));
    }

    @Test
    void userCannotOverwriteAnotherUsersBlockRuleViaUpsert() throws Exception {
        UUID ruleId = UUID.randomUUID();

        putRule(tokenA, ruleId, 30, Instant.now());
        putRule(tokenB, ruleId, 999, Instant.now().plusSeconds(60));

        mockMvc.perform(get("/api/v1/app-block-rules/{id}", ruleId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyLimitMinutes").value(30));
    }

    @Test
    void userCannotReadOrDeleteAnotherUsersStreak() throws Exception {
        UUID streakId = UUID.randomUUID();
        putStreak(tokenA, streakId, LocalDate.now(), 42, Instant.now());

        // 404 et non 403 : on ne confirme meme pas l'existence de la ressource.
        mockMvc.perform(get("/api/v1/streak-records/{id}", streakId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/streak-records/{id}", streakId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/streak-records/{id}", streakId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ MANQUE-01 / 02

    @Test
    void deletedResourceDisappearsFromClientViewButSurfacesInDelta() throws Exception {
        Instant before = Instant.now().minus(1, ChronoUnit.MINUTES);
        UUID ruleId = UUID.randomUUID();

        putRule(tokenA, ruleId, 30, Instant.now());

        mockMvc.perform(delete("/api/v1/app-block-rules/{id}", ruleId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());

        // Vue client : la regle n'existe plus, ni en unitaire ni en liste.
        mockMvc.perform(get("/api/v1/app-block-rules/{id}", ruleId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/app-block-rules")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());

        // Delta : le tombstone DOIT apparaitre, sinon un appareil hors ligne au moment de la
        // suppression ne l'apprendrait jamais et recreerait la regle a sa prochaine ecriture.
        mockMvc.perform(get("/api/v1/app-block-rules")
                        .param("updatedSince", before.toString())
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(ruleId.toString()))
                .andExpect(jsonPath("$.content[0].deletedAt").isNotEmpty());
    }

    @Test
    void staleWriteDoesNotResurrectDeletedResource() throws Exception {
        UUID ruleId = UUID.randomUUID();
        Instant staleClientClock = Instant.now().minus(1, ChronoUnit.HOURS);

        putRule(tokenA, ruleId, 30, Instant.now());
        mockMvc.perform(delete("/api/v1/app-block-rules/{id}", ruleId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());

        // Appareil qui n'avait pas encore appris la suppression : son ecriture est perimee.
        putRule(tokenA, ruleId, 45, staleClientClock);

        mockMvc.perform(get("/api/v1/app-block-rules/{id}", ruleId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }

    @Test
    void freshWriteResurrectsDeletedResource() throws Exception {
        UUID ruleId = UUID.randomUUID();

        putRule(tokenA, ruleId, 30, Instant.now());
        mockMvc.perform(delete("/api/v1/app-block-rules/{id}", ruleId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());

        // L'utilisateur recree deliberement la regle apres la suppression.
        putRule(tokenA, ruleId, 45, Instant.now().plus(1, ChronoUnit.HOURS));

        mockMvc.perform(get("/api/v1/app-block-rules/{id}", ruleId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyLimitMinutes").value(45))
                .andExpect(jsonPath("$.deletedAt").doesNotExist());
    }

    /**
     * uk_streak_user_day interdit deux lignes pour le meme (user_id, day). Un streak supprime
     * occupe toujours son creneau : le recreer doit reanimer la ligne existante, pas tenter une
     * insertion qui violerait la contrainte.
     */
    @Test
    void recreatingStreakOnSameDayRevivesTombstoneInsteadOfViolatingUniqueConstraint() throws Exception {
        UUID streakId = UUID.randomUUID();
        LocalDate day = LocalDate.now();

        putStreak(tokenA, streakId, day, 60, Instant.now());
        mockMvc.perform(delete("/api/v1/streak-records/{id}", streakId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNoContent());

        putStreak(tokenA, UUID.randomUUID(), day, 75, Instant.now().plus(1, ChronoUnit.HOURS));

        mockMvc.perform(get("/api/v1/streak-records/{id}", streakId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.focusMinutes").value(75));
    }

    // ------------------------------------------------------------------ helpers

    private void putStreak(String token, UUID id, LocalDate day, int minutes, Instant clientUpdatedAt)
            throws Exception {
        StreakRecordRequest request = new StreakRecordRequest(day, true, minutes, clientUpdatedAt);
        mockMvc.perform(put("/api/v1/streak-records/{id}", id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    private void putRule(String token, UUID id, int dailyLimitMinutes, Instant clientUpdatedAt) throws Exception {
        AppBlockRuleRequest request = new AppBlockRuleRequest(
                OPAQUE_SELECTION, dailyLimitMinutes, "09:00", "17:00", true, clientUpdatedAt);
        mockMvc.perform(put("/api/v1/app-block-rules/{id}", id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }
}
