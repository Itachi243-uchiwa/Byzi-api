package com.byzi.api.controller;

import com.byzi.api.repository.SubscriptionEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * App Store Server Notifications V2 (story 07.10) - le chemin de REJET.
 * <p>
 * Ce qui est testable ici, et ce qui ne l'est pas. Un chemin nominal demanderait de forger un
 * JWS signe par une chaine de certificats remontant a l'AppleRootCA-G3 : sans la cle privee
 * d'Apple, c'est par construction impossible - et c'est precisement la garantie qu'on cherche.
 * <p>
 * Ce que ces tests protegent est donc l'autre moitie, celle qui compte pour la securite : que
 * l'endpoint soit joignable sans jeton (Apple appelle sans s'authentifier) <b>et</b> qu'il
 * refuse tout ce qui n'est pas signe par Apple. Une regression qui accepterait un corps
 * arbitraire laisserait n'importe qui s'octroyer un abonnement a vie.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AppleServerNotificationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SubscriptionEventRepository subscriptionEventRepository;

    /**
     * Joignable sans authentification : Apple n'a pas de compte chez nous. La protection est la
     * signature, pas un jeton - si cette route repassait derriere le filtre JWT, plus aucune
     * notification n'arriverait et les abonnements cesseraient silencieusement de se mettre a
     * jour.
     */
    @Test
    void theEndpointIsReachableWithoutAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/apple")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"signedPayload\":\"pas-un-jws\"}"))
                // 401 et non 403 : la requete est bien arrivee au controleur, qui a rejete la
                // signature. Un 403 signalerait au contraire un blocage par la securite web.
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anUnsignedPayloadChangesNothing() throws Exception {
        long before = subscriptionEventRepository.count();

        mockMvc.perform(post("/api/v1/webhooks/apple")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"signedPayload\":\"eyJhbGciOiJub25lIn0.eyJub3RpZmljYXRpb25UeXBlIjoiU1VCU0NSSUJFRCJ9.\"}"))
                .andExpect(status().isUnauthorized());

        assertThat(subscriptionEventRepository.count())
                .as("un corps non signe ne doit produire aucun evenement d'abonnement")
                .isEqualTo(before);
    }

    /** Un corps vide n'est pas une notification : on refuse avant meme de tenter la verification. */
    @Test
    void anEmptyPayloadIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/webhooks/apple")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"signedPayload\":\"\"}"))
                .andExpect(status().isUnauthorized());
    }
}
