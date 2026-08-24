package com.byzi.api.controller;

import com.byzi.api.domain.Role;
import com.byzi.api.domain.SubscriptionStatus;
import com.byzi.api.domain.User;
import com.byzi.api.repository.ReferralRedemptionRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Parrainage (backlog 10.8) : code de partage, utilisation, et regles de refus.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReferralIntegrationTest {

    private static final int EXPECTED_DAYS = 7;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ReferralRedemptionRepository redemptionRepository;
    @Autowired
    private JwtService jwtService;

    private User newUser(SubscriptionStatus status) {
        return userRepository.save(User.builder()
                .id(UUID.randomUUID())
                .appleSub("apple-sub-" + UUID.randomUUID())
                .role(Role.USER)
                .subscriptionStatus(status)
                .build());
    }

    private String tokenFor(User user) {
        return jwtService.generateAccessToken(user.getId(), Role.USER);
    }

    private String codeOf(User user) throws Exception {
        String json = mockMvc.perform(get("/api/v1/referrals/me")
                        .header("Authorization", "Bearer " + tokenFor(user)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.replaceAll(".*\"code\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }

    private void redeem(User caller, String code, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/v1/referrals/redeem")
                        .header("Authorization", "Bearer " + tokenFor(caller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().is(expectedStatus));
    }

    // ------------------------------------------------------------------ code de partage

    @Test
    void codeIsCreatedOnFirstCallAndNeverChanges() throws Exception {
        User user = newUser(SubscriptionStatus.TRIAL);

        String first = codeOf(user);
        String second = codeOf(user);

        // Le code a pu etre partage entre les deux appels : le regenerer invaliderait ce que
        // l'utilisateur a deja diffuse.
        assertThat(first).isNotBlank().hasSize(6).isEqualTo(second);
    }

    @Test
    void codeAvoidsCharactersThatAreEasilyMisread() throws Exception {
        // Les codes sont recopies a la main depuis une capture d'ecran : O/0, I/1 et L
        // coutent des tentatives echouees et des messages au support.
        assertThat(codeOf(newUser(SubscriptionStatus.TRIAL))).doesNotContainAnyWhitespaces()
                .matches("[ABCDEFGHJKMNPQRSTVWXYZ23456789]{6}");
    }

    @Test
    void shareScreenExposesTheRewardSoTheAppNeedNotHardcodeIt() throws Exception {
        mockMvc.perform(get("/api/v1/referrals/me")
                        .header("Authorization", "Bearer " + tokenFor(newUser(SubscriptionStatus.TRIAL))))
                .andExpect(jsonPath("$.daysPerRedemption").value(EXPECTED_DAYS))
                .andExpect(jsonPath("$.redemptionCount").value(0));
    }

    // ------------------------------------------------------------------------ utilisation

    @Test
    void redeemingGrantsDaysToBothSides() throws Exception {
        User referrer = newUser(SubscriptionStatus.TRIAL);
        User referred = newUser(SubscriptionStatus.TRIAL);
        String code = codeOf(referrer);

        mockMvc.perform(post("/api/v1/referrals/redeem")
                        .header("Authorization", "Bearer " + tokenFor(referred))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daysGranted").value(EXPECTED_DAYS))
                .andExpect(jsonPath("$.referrerRewarded").value(true))
                .andExpect(jsonPath("$.subscriptionExpiresAt").isNotEmpty());

        Instant expectedFloor = Instant.now().plus(EXPECTED_DAYS - 1, ChronoUnit.DAYS);
        assertThat(userRepository.findById(referrer.getId()).orElseThrow().getSubscriptionExpiresAt())
                .as("le parrain aussi est recompense, c'est tout l'interet du dispositif")
                .isAfter(expectedFloor);
        assertThat(userRepository.findById(referred.getId()).orElseThrow().getSubscriptionExpiresAt())
                .isAfter(expectedFloor);
    }

    @Test
    void redemptionIsCountedOnTheShareScreen() throws Exception {
        User referrer = newUser(SubscriptionStatus.TRIAL);
        String code = codeOf(referrer);
        redeem(newUser(SubscriptionStatus.TRIAL), code, 200);

        mockMvc.perform(get("/api/v1/referrals/me")
                        .header("Authorization", "Bearer " + tokenFor(referrer)))
                .andExpect(jsonPath("$.redemptionCount").value(1));
    }

    @Test
    void codeIsAcceptedInAnyCaseAndWithSpaces() throws Exception {
        User referrer = newUser(SubscriptionStatus.TRIAL);
        String code = codeOf(referrer);

        // Le code est lu sur une capture d'ecran et recopie a la main.
        redeem(newUser(SubscriptionStatus.TRIAL), " " + code.toLowerCase() + " ", 200);
    }

    // ------------------------------------------------------------------------------ refus

    @Test
    void unknownCodeIsRejected() throws Exception {
        redeem(newUser(SubscriptionStatus.TRIAL), "ZZZZZZ", 404);
    }

    @Test
    void ownCodeIsRejected() throws Exception {
        User user = newUser(SubscriptionStatus.TRIAL);
        redeem(user, codeOf(user), 409);
    }

    @Test
    void anAccountCanOnlyBeReferredOnce() throws Exception {
        User referred = newUser(SubscriptionStatus.TRIAL);
        redeem(referred, codeOf(newUser(SubscriptionStatus.TRIAL)), 200);

        // Seule protection anti-abus disponible avant la detection de fraude de la V2, et
        // elle est portee par une contrainte d'unicite en base.
        redeem(referred, codeOf(newUser(SubscriptionStatus.TRIAL)), 409);
    }

    @Test
    void payingSubscriberCannotRedeem() throws Exception {
        // Les jours s'ecrivent dans subscription_expires_at, que le webhook RevenueCat suivant
        // reecrirait : accepter la demande annoncerait une recompense qui disparaitrait.
        redeem(newUser(SubscriptionStatus.ACTIVE), codeOf(newUser(SubscriptionStatus.TRIAL)), 409);
    }

    @Test
    void expiredAccountCanStillRedeem() throws Exception {
        // Un compte expire est exactement la cible du dispositif : il n'est pas payant, et
        // les jours offerts sont une raison de revenir.
        redeem(newUser(SubscriptionStatus.EXPIRED), codeOf(newUser(SubscriptionStatus.TRIAL)), 200);
    }

    @Test
    void payingReferrerGetsTheCreditRecordedButNoDays() throws Exception {
        User referrer = newUser(SubscriptionStatus.ACTIVE);
        String code = codeOf(referrer);
        Instant before = userRepository.findById(referrer.getId()).orElseThrow().getSubscriptionExpiresAt();

        mockMvc.perform(post("/api/v1/referrals/redeem")
                        .header("Authorization", "Bearer " + tokenFor(newUser(SubscriptionStatus.TRIAL)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referrerRewarded").value(false));

        // L'utilisation est enregistree - le back-office doit voir la conversion - mais le
        // compte payant n'est pas touche : son acces est pilote par RevenueCat.
        assertThat(userRepository.findById(referrer.getId()).orElseThrow().getSubscriptionExpiresAt())
                .isEqualTo(before);
        assertThat(redemptionRepository.countByReferrer_Id(referrer.getId())).isEqualTo(1);
    }

    @Test
    void deletingAnAccountRemovesItsReferralHistory() throws Exception {
        User referrer = newUser(SubscriptionStatus.TRIAL);
        User referred = newUser(SubscriptionStatus.TRIAL);
        redeem(referred, codeOf(referrer), 200);

        // Suppression RGPD : la cascade du schema doit emporter aussi les lignes de
        // parrainage, sans quoi la suppression laisserait derriere elle un lien entre deux
        // comptes reels.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/account")
                        .header("Authorization", "Bearer " + tokenFor(referred)))
                .andExpect(status().isNoContent());

        assertThat(redemptionRepository.countByReferrer_Id(referrer.getId())).isZero();
    }

    @Test
    void referralEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/referrals/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/referrals/redeem")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"ABCDEF\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void gdprExportCarriesTheReferralDataWithoutNamingTheOtherParty() throws Exception {
        User referrer = newUser(SubscriptionStatus.TRIAL);
        User referred = newUser(SubscriptionStatus.TRIAL);
        redeem(referred, codeOf(referrer), 200);

        String export = mockMvc.perform(get("/api/v1/account/export")
                        .header("Authorization", "Bearer " + tokenFor(referred)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referral.referredAt").isNotEmpty())
                .andExpect(jsonPath("$.referral.daysReceived").value(EXPECTED_DAYS))
                .andExpect(jsonPath("$.referral.peopleReferred").value(0))
                .andReturn().getResponse().getContentAsString();

        // Une ligne de parrainage concerne deux personnes. Le droit a la portabilite du
        // filleul ne s'etend pas a l'identite de son parrain.
        assertThat(export)
                .as("l'export ne doit pas nommer l'autre partie")
                .doesNotContain(referrer.getId().toString())
                .doesNotContain(referrer.getAppleSub());
    }
}
