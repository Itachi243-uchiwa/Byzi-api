package com.byzi.api.controller;

import com.byzi.api.domain.Role;
import com.byzi.api.domain.User;
import com.byzi.api.repository.UserRepository;
import com.byzi.api.security.jwt.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les erreurs qui surviennent AVANT qu'un controller ne soit atteint : route inexistante,
 * methode HTTP non declaree sur la route. Le DispatcherServlet les renvoie vers /error, et
 * c'est {@link com.byzi.api.exception.ApiErrorAttributes} qui leur donne la forme d'un
 * ApiError.
 * <p>
 * Elles exigent un vrai conteneur servlet : MockMvc n'execute pas le dispatch ERROR et
 * laisserait croire que ces reponses n'ont pas de corps. C'est la seule raison pour laquelle
 * ce test est separe de {@link ApiErrorContractIntegrationTest}, au prix d'un demarrage sur
 * port reel - mais c'est ce prix qui rend la verification honnete.
 * <p>
 * Le client HTTP est celui du JDK, et non un TestRestTemplate : quatre appels ne justifient
 * pas d'ajouter une dependance de test au projet.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ApiErrorDispatchIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JwtService jwtService;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

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

    private HttpResponse<String> call(String method, String path, String accept, boolean authenticated)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Accept", accept)
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (authenticated) {
            builder.header("Authorization", "Bearer " + token);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertApiErrorShape(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .as("les erreurs de l'API sont toujours du JSON")
                .startsWith("application/json");
        assertThat(response.body())
                .contains("\"status\":" + status)
                .contains("\"error\":\"" + code + "\"")
                .contains("\"message\":")
                .contains("\"path\":")
                .contains("\"timestamp\":");
    }

    @Test
    void unknownRouteReturnsApiError() throws Exception {
        assertApiErrorShape(call("GET", "/api/v1/route-qui-n-existe-pas", "application/json", true),
                404, "not_found");
    }

    @Test
    void wrongHttpMethodReturnsApiError() throws Exception {
        assertApiErrorShape(call("POST", "/api/v1/me", "application/json", true),
                405, "method_not_allowed");
    }

    @Test
    void anonymousCallerCannotTellWhichRoutesExist() throws Exception {
        // 401 et non 404, et c'est voulu : la chaine de securite rejette avant que le
        // DispatcherServlet ne puisse constater que la route n'existe pas. Repondre 404 a un
        // appelant non authentifie lui permettrait d'enumerer les routes de l'API en comparant
        // les 404 aux 401. La reponse reste un ApiError - une seule structure, toujours.
        HttpResponse<String> response = call("GET", "/api/v1/route-qui-n-existe-pas",
                "application/json", false);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"error\":\"unauthorized\"");
    }

    @Test
    void anonymousBrowserIsRedirectedToLoginRatherThanShownAnError() throws Exception {
        // Le back-office ne divulgue rien non plus : une URL admin inconnue renvoie vers le
        // formulaire de connexion, pas vers une page d'erreur qui confirmerait son absence.
        HttpResponse<String> response = call("GET", "/admin/page-inexistante", "text/html", false);

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location").orElse("")).endsWith("/admin/login");
    }
}
