package com.byzi.api.controller;

import com.byzi.api.domain.AppBlockRule;
import com.byzi.api.domain.Role;
import com.byzi.api.domain.SessionMode;
import com.byzi.api.domain.StreakRecord;
import com.byzi.api.domain.SubscriptionEvent;
import com.byzi.api.domain.SubscriptionStatus;
import com.byzi.api.domain.User;
import com.byzi.api.repository.AppBlockRuleRepository;
import com.byzi.api.repository.FocusSessionRepository;
import com.byzi.api.repository.StreakRecordRepository;
import com.byzi.api.repository.SubscriptionEventRepository;
import com.byzi.api.repository.UserRepository;
import com.byzi.api.security.apple.AppleIdTokenClaims;
import com.byzi.api.security.apple.AppleTokenVerifier;
import com.byzi.api.security.jwt.JwtService;
import com.byzi.api.exception.InvalidAppleTokenException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stories 01.2 (POST /auth/apple) et 01.6 (DELETE /account, RGPD).
 * <p>
 * AppleTokenVerifier est remplace par un mock : le chemin nominal exigerait sinon de signer un
 * token avec la vraie cle privee d'Apple. Ce qui est verifie ici, c'est le comportement de
 * l'API autour de la verification, pas la cryptographie elle-meme (couverte par
 * AppleTokenVerifierTest).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthAndAccountIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FocusSessionRepository focusSessionRepository;
    @Autowired
    private StreakRecordRepository streakRecordRepository;
    @Autowired
    private AppBlockRuleRepository appBlockRuleRepository;
    @Autowired
    private SubscriptionEventRepository subscriptionEventRepository;
    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private AppleTokenVerifier appleTokenVerifier;

    private String signInAndGetAccessToken(String appleSub) throws Exception {
        when(appleTokenVerifier.verify(anyString()))
                .thenReturn(new AppleIdTokenClaims(appleSub, appleSub + "@byzi.app", true));

        String response = mockMvc.perform(post("/api/v1/auth/apple")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identityToken\": \"un-token-apple\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andReturn().getResponse().getContentAsString();

        return response.replaceAll(".*\"accessToken\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }

    @Test
    void signInWithAppleCreatesAccountAndReturnsTokens() throws Exception {
        String appleSub = "apple-sub-" + UUID.randomUUID();
        signInAndGetAccessToken(appleSub);

        assertThat(userRepository.findByAppleSub(appleSub)).isPresent();
    }

    @Test
    void signInTwiceReusesTheSameAccount() throws Exception {
        String appleSub = "apple-sub-" + UUID.randomUUID();
        signInAndGetAccessToken(appleSub);
        signInAndGetAccessToken(appleSub);

        // Une seconde connexion ne doit pas dupliquer le compte (contrainte uk_users_apple_sub).
        assertThat(userRepository.findByAppleSub(appleSub)).isPresent();
    }

    @Test
    void signInWithInvalidAppleTokenIsRejected() throws Exception {
        when(appleTokenVerifier.verify(anyString()))
                .thenThrow(new InvalidAppleTokenException("token invalide"));

        mockMvc.perform(post("/api/v1/auth/apple")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identityToken\": \"forge\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_apple_token"));
    }

    @Test
    void signInWithBlankTokenIsRejectedByValidation() throws Exception {
        mockMvc.perform(post("/api/v1/auth/apple")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identityToken\": \"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refreshWithUnknownTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\": \"un-token-qui-nexiste-pas\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutSucceedsForAuthenticatedUser() throws Exception {
        User user = userRepository.save(User.builder()
                .id(UUID.randomUUID()).appleSub("sub-" + UUID.randomUUID()).role(Role.USER).build());
        String token = jwtService.generateAccessToken(user.getId(), Role.USER);

        mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteAccountRemovesUserAndCascadesChildData() throws Exception {
        User user = userRepository.save(User.builder()
                .id(UUID.randomUUID()).appleSub("sub-" + UUID.randomUUID()).role(Role.USER).build());
        focusSessionRepository.save(com.byzi.api.domain.FocusSession.builder()
                .id(UUID.randomUUID()).user(user).startedAt(Instant.now())
                .plannedDurationSeconds(1500).mode(SessionMode.STANDARD).build());
        String token = jwtService.generateAccessToken(user.getId(), Role.USER);

        mockMvc.perform(delete("/api/v1/account").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        assertThat(userRepository.findById(user.getId())).isEmpty();
        // Guideline 5.1.1(v) : la suppression doit emporter les donnees liees, pas seulement
        // la ligne de compte.
        assertThat(focusSessionRepository.countByUser_Id(user.getId())).isZero();
    }

    @Test
    void deleteAccountRequiresAuthentication() throws Exception {
        mockMvc.perform(delete("/api/v1/account")).andExpect(status().isUnauthorized());
    }

    @Test
    void deletedAccountTokenNoLongerGrantsAccess() throws Exception {
        User user = userRepository.save(User.builder()
                .id(UUID.randomUUID()).appleSub("sub-" + UUID.randomUUID()).role(Role.USER).build());
        String token = jwtService.generateAccessToken(user.getId(), Role.USER);

        mockMvc.perform(delete("/api/v1/account").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // Le JWT reste cryptographiquement valide jusqu'a son expiration, mais le compte
        // n'existe plus : les donnees associees doivent etre vides, pas celles d'un autre.
        mockMvc.perform(get("/api/v1/focus-sessions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // --- MANQUE-03 : export RGPD (art. 20) --------------------------------------------------

    @Test
    void exportAccountRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/account/export")).andExpect(status().isUnauthorized());
    }

    @Test
    void exportAccountOffersDownloadableJsonWithOwnDataOnly() throws Exception {
        User owner = userRepository.save(User.builder()
                .id(UUID.randomUUID()).appleSub("sub-" + UUID.randomUUID()).email("owner@byzi.app")
                .role(Role.USER).subscriptionStatus(SubscriptionStatus.ACTIVE)
                .subscriptionExpiresAt(Instant.now().plusSeconds(3600)).build());
        User other = userRepository.save(User.builder()
                .id(UUID.randomUUID()).appleSub("sub-" + UUID.randomUUID()).role(Role.USER).build());
        String ownerToken = jwtService.generateAccessToken(owner.getId(), Role.USER);

        focusSessionRepository.save(com.byzi.api.domain.FocusSession.builder()
                .id(UUID.randomUUID()).user(owner).startedAt(Instant.now())
                .plannedDurationSeconds(1500).mode(SessionMode.STANDARD).build());
        streakRecordRepository.save(StreakRecord.builder()
                .id(UUID.randomUUID()).user(owner).day(LocalDate.now()).goalReached(true).focusMinutes(45).build());
        String opaqueBlob = "eyJhcHBUb2tlbnMiOlsiQUFBQ0FnRUEiXX0=";
        appBlockRuleRepository.save(AppBlockRule.builder()
                .id(UUID.randomUUID()).user(owner).selectionData(opaqueBlob).isActive(true).build());
        subscriptionEventRepository.save(SubscriptionEvent.builder()
                .id(UUID.randomUUID()).user(owner).eventId("evt-" + UUID.randomUUID())
                .eventType("INITIAL_PURCHASE").resultingStatus(SubscriptionStatus.ACTIVE)
                .expiresAt(Instant.now().plusSeconds(3600)).occurredAt(Instant.now())
                .receivedAt(Instant.now()).build());

        // Donnees d'un autre compte : ne doivent apparaitre nulle part dans l'export du owner.
        focusSessionRepository.save(com.byzi.api.domain.FocusSession.builder()
                .id(UUID.randomUUID()).user(other).startedAt(Instant.now())
                .plannedDurationSeconds(900).mode(SessionMode.DEEP_FOCUS).build());

        mockMvc.perform(get("/api/v1/account/export").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString(owner.getId().toString())))
                .andExpect(jsonPath("$.profile.userId").value(owner.getId().toString()))
                .andExpect(jsonPath("$.profile.appleSub").value(owner.getAppleSub()))
                .andExpect(jsonPath("$.profile.email").value("owner@byzi.app"))
                // Ni passwordHash ni role ne doivent jamais apparaitre dans l'export.
                .andExpect(jsonPath("$.profile.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.profile.role").doesNotExist())
                .andExpect(jsonPath("$.focusSessions.length()").value(1))
                .andExpect(jsonPath("$.streakRecords.length()").value(1))
                .andExpect(jsonPath("$.appBlockRules.length()").value(1))
                // Le blob opaque ressort octet pour octet : aucune interpretation cote serveur.
                .andExpect(jsonPath("$.appBlockRules[0].selectionData").value(opaqueBlob))
                .andExpect(jsonPath("$.subscriptionHistory.length()").value(1))
                .andExpect(jsonPath("$.subscriptionHistory[0].eventType").value("INITIAL_PURCHASE"))
                .andExpect(jsonPath("$.subscriptionHistory[0].id").doesNotExist())
                .andExpect(jsonPath("$.subscriptionHistory[0].eventId").doesNotExist());
    }

    @Test
    void exportAccountForUserWithNoDataReturnsEmptyCollections() throws Exception {
        User user = userRepository.save(User.builder()
                .id(UUID.randomUUID()).appleSub("sub-" + UUID.randomUUID()).role(Role.USER).build());
        String token = jwtService.generateAccessToken(user.getId(), Role.USER);

        mockMvc.perform(get("/api/v1/account/export").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.focusSessions.length()").value(0))
                .andExpect(jsonPath("$.streakRecords.length()").value(0))
                .andExpect(jsonPath("$.appBlockRules.length()").value(0))
                .andExpect(jsonPath("$.subscriptionHistory.length()").value(0));
    }

    // --- Prenom du profil (backlog app 0ter T8) ---

    @Test
    void updatesAndClearsGivenName() throws Exception {
        User user = userRepository.save(User.builder()
                .id(java.util.UUID.randomUUID())
                .appleSub("apple-sub-" + java.util.UUID.randomUUID())
                .role(Role.USER)
                .build());
        String token = jwtService.generateAccessToken(user.getId(), Role.USER);

        // Les espaces sont rognes.
        mockMvc.perform(put("/api/v1/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"givenName\":\"  Martinez  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.givenName").value("Martinez"));

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.givenName").value("Martinez"));

        // Chaine vide == null : une seule representation de "pas de prenom".
        mockMvc.perform(put("/api/v1/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"givenName\":\"   \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.givenName").doesNotExist());
    }

    @Test
    void updatingProfileRequiresAuthentication() throws Exception {
        mockMvc.perform(put("/api/v1/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"givenName\":\"X\"}"))
                .andExpect(status().isUnauthorized());
    }

}
