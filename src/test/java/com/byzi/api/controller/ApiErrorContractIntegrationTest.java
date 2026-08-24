package com.byzi.api.controller;

import com.byzi.api.domain.Role;
import com.byzi.api.domain.User;
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
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Le contrat d'erreur de l'API, vu du client iOS : quoi qu'il arrive, une reponse en echec est
 * un JSON de forme {@code ApiError} - timestamp, status, error (code stable en snake_case),
 * message, path.
 * <p>
 * Ce test couvre les erreurs levees UNE FOIS un controller atteint. Celles qui surviennent
 * avant - route inconnue, methode HTTP inexistante sur la route - passent par le dispatch
 * /error, que MockMvc ne simule pas : elles sont verifiees sur un vrai conteneur par
 * {@link ApiErrorDispatchIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiErrorContractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JwtService jwtService;

    private String token;

    @BeforeEach
    void setUp() {
        User user = userRepository.save(User.builder()
                .id(UUID.randomUUID())
                .appleSub("apple-sub-" + UUID.randomUUID())
                .role(Role.USER)
                .build());
        token = jwtService.generateAccessToken(user.getId(), Role.USER);
    }

    /** Toute reponse d'erreur porte les cinq champs, sans exception. */
    private void expectsApiErrorShape(ResultActions result, int status, String code) throws Exception {
        result.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.error").value(code))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").isNotEmpty());
    }

    @Test
    void malformedJsonBodyReturns400() throws Exception {
        // Cas le plus courant pendant le developpement du client : une virgule en trop.
        // Il finissait en 500, faisant passer une faute du client pour une panne serveur.
        expectsApiErrorShape(
                mockMvc.perform(put("/api/v1/app-block-rules/{id}", UUID.randomUUID())
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"selectionData\": \"abc\",}"))
                        .andExpect(status().isBadRequest()),
                400, "malformed_request");
    }

    @Test
    void malformedUuidInPathReturns400() throws Exception {
        expectsApiErrorShape(
                mockMvc.perform(get("/api/v1/app-block-rules/{id}", "pas-un-uuid")
                                .header("Authorization", "Bearer " + token))
                        .andExpect(status().isBadRequest()),
                400, "invalid_parameter");
    }

    @Test
    void unsupportedContentTypeReturns415() throws Exception {
        expectsApiErrorShape(
                mockMvc.perform(put("/api/v1/app-block-rules/{id}", UUID.randomUUID())
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.TEXT_PLAIN)
                                .content("selectionData=abc"))
                        .andExpect(status().isUnsupportedMediaType()),
                415, "unsupported_media_type");
    }

    @Test
    void validationFailureReturns400() throws Exception {
        expectsApiErrorShape(
                mockMvc.perform(put("/api/v1/app-block-rules/{id}", UUID.randomUUID())
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"selectionData\":\"\",\"active\":true}"))
                        .andExpect(status().isBadRequest()),
                400, "validation_error");
    }

    @Test
    void missingTokenReturnsTheSameShapeAsEverythingElse() throws Exception {
        // Le code valait "Unauthorized" ici et "unauthorized" ailleurs : le client aurait du
        // traiter les deux graphies.
        expectsApiErrorShape(
                mockMvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized()),
                401, "unauthorized");
    }

    @Test
    void insufficientRoleReturns403InTheSameShape() throws Exception {
        expectsApiErrorShape(
                mockMvc.perform(get("/api/v1/admin/users")
                                .header("Authorization", "Bearer " + token))
                        .andExpect(status().isForbidden()),
                403, "forbidden");
    }

    @Test
    void htmlClientGetsAPageNotAJsonObject() throws Exception {
        // Le back-office est rendu par des @Controller Thymeleaf. Tant que la regle
        // @RestControllerAdvice etait globale, une erreur du back-office s'affichait sous la
        // forme d'un objet JSON en pleine page de navigateur. Elle est desormais limitee aux
        // @RestController, et /error rend templates/error.html aux clients HTML.
        mockMvc.perform(get("/error").accept(MediaType.TEXT_HTML))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Retour au back-office")));
    }
}
