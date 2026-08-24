package com.byzi.api.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Protection anti brute-force des surfaces ouvertes (OWASP API4:2023) : authentification de
 * l'app, connexion au back-office et webhook RevenueCat.
 */
class RateLimitingFilterTest {

    private static final int MAX_REQUESTS = 10;

    private RateLimitingFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new RateLimitingFilter();
        chain = mock(FilterChain.class);
    }

    private MockHttpServletResponse callOnce(String uri, String ip) throws Exception {
        return callOnce("POST", uri, ip);
    }

    private MockHttpServletResponse callOnce(String method, String uri, String ip) throws Exception {
        return call(filter, method, uri, ip);
    }

    private MockHttpServletResponse call(RateLimitingFilter target, String method, String uri, String ip)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        target.doFilter(request, response, chain);
        return response;
    }

    @Test
    void doesNotLimitRoutesOutsideAuth() throws Exception {
        for (int i = 0; i < MAX_REQUESTS + 5; i++) {
            assertThat(callOnce("/api/v1/focus-sessions", "10.0.0.1").getStatus()).isEqualTo(200);
        }
        // Le rate limiting ne doit pas degrader les endpoints metier, qui sont deja proteges
        // par le JWT : seule la surface ouverte est concernee.
        verify(chain, times(MAX_REQUESTS + 5)).doFilter(any(), any());
    }

    @Test
    void allowsRequestsUpToTheLimit() throws Exception {
        for (int i = 0; i < MAX_REQUESTS; i++) {
            assertThat(callOnce("/api/v1/auth/apple", "10.0.0.2").getStatus()).isEqualTo(200);
        }
        verify(chain, times(MAX_REQUESTS)).doFilter(any(), any());
    }

    @Test
    void blocksBeyondTheLimitWith429() throws Exception {
        for (int i = 0; i < MAX_REQUESTS; i++) {
            callOnce("/api/v1/auth/apple", "10.0.0.3");
        }
        MockHttpServletResponse blocked = callOnce("/api/v1/auth/apple", "10.0.0.3");

        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getContentAsString()).contains("too_many_requests");
        // La requete bloquee ne doit surtout pas atteindre le controller d'authentification.
        verify(chain, times(MAX_REQUESTS)).doFilter(any(), any());
    }

    @Test
    void countsPerClientAddress() throws Exception {
        for (int i = 0; i < MAX_REQUESTS; i++) {
            callOnce("/api/v1/auth/apple", "10.0.0.4");
        }
        // Une autre IP ne doit pas heriter du compteur de la premiere, sinon un seul client
        // abusif bloquerait tous les utilisateurs legitimes.
        assertThat(callOnce("/api/v1/auth/apple", "10.0.0.5").getStatus()).isEqualTo(200);
    }

    @Test
    void refreshRouteIsAlsoLimited() throws Exception {
        for (int i = 0; i < MAX_REQUESTS; i++) {
            callOnce("/api/v1/auth/refresh", "10.0.0.6");
        }
        assertThat(callOnce("/api/v1/auth/refresh", "10.0.0.6").getStatus()).isEqualTo(429);
    }

    @Test
    void blockedResponseIsJson() throws Exception {
        for (int i = 0; i < MAX_REQUESTS; i++) {
            callOnce("/api/v1/auth/apple", "10.0.0.7");
        }
        // Le client est l'app iOS : elle attend du JSON, y compris sur les erreurs.
        assertThat(callOnce("/api/v1/auth/apple", "10.0.0.7").getContentType()).isEqualTo("application/json");
    }

    @Test
    void adminLoginIsLimited() throws Exception {
        for (int i = 0; i < MAX_REQUESTS; i++) {
            callOnce("/admin/login", "10.0.0.8");
        }
        // Le back-office valide un mot de passe BCrypt et ouvre la suppression de comptes :
        // c'est la cible la plus interessante de l'application, et elle etait la seule
        // authentification sans limitation de debit.
        assertThat(callOnce("/admin/login", "10.0.0.8").getStatus()).isEqualTo(429);
    }

    @Test
    void webhookIsLimitedButFarAboveHumanPace() throws Exception {
        // RevenueCat appelle depuis un petit nombre d'IP fixes, pour TOUS les abonnes a la
        // fois : le plafond du webhook doit laisser passer un trafic sans commune mesure avec
        // celui d'un formulaire de connexion, sous peine de perdre des evenements de
        // facturation. Il borne le debit, il n'imite pas un rythme humain.
        for (int i = 0; i < MAX_REQUESTS * 10; i++) {
            assertThat(callOnce("/api/v1/webhooks/revenuecat", "10.0.0.9").getStatus()).isEqualTo(200);
        }
    }

    @Test
    void webhookStillHasACeiling() throws Exception {
        // L'endpoint est necessairement en permitAll() : sans aucun plafond, son secret
        // partage pourrait etre martele indefiniment.
        RateLimitingFilter narrow = new RateLimitingFilter(
                List.of(new RateLimitingFilter.Surface("/api/v1/webhooks/", 3)));

        for (int i = 0; i < 3; i++) {
            assertThat(call(narrow, "POST", "/api/v1/webhooks/revenuecat", "10.0.0.20").getStatus())
                    .isEqualTo(200);
        }
        assertThat(call(narrow, "POST", "/api/v1/webhooks/revenuecat", "10.0.0.20").getStatus())
                .isEqualTo(429);
    }

    @Test
    void eachProtectedSurfaceHasItsOwnBucket() throws Exception {
        for (int i = 0; i < MAX_REQUESTS; i++) {
            callOnce("/admin/login", "10.0.0.10");
        }
        // Meme IP publique (reseau d'entreprise), surfaces differentes : un administrateur
        // qui se trompe de mot de passe ne doit pas couper l'authentification de l'app iOS.
        assertThat(callOnce("/api/v1/auth/apple", "10.0.0.10").getStatus()).isEqualTo(200);
    }

    @Test
    void routesOfTheSameSurfaceShareTheirBucket() throws Exception {
        for (int i = 0; i < MAX_REQUESTS; i++) {
            callOnce("/api/v1/auth/apple", "10.0.0.11");
        }
        // /auth/apple et /auth/refresh forment une seule surface d'attaque : leur donner un
        // quota chacun reviendrait a doubler la limite annoncee.
        assertThat(callOnce("/api/v1/auth/refresh", "10.0.0.11").getStatus()).isEqualTo(429);
    }

    @Test
    void doesNotLimitGetOnTheLoginPage() throws Exception {
        for (int i = 0; i < MAX_REQUESTS + 5; i++) {
            assertThat(callOnce("GET", "/admin/login", "10.0.0.12").getStatus()).isEqualTo(200);
        }
        // On brute-force en POST. Compter les GET bloquerait un administrateur qui recharge
        // simplement son formulaire de connexion.
        verify(chain, times(MAX_REQUESTS + 5)).doFilter(any(), any());
    }
}
