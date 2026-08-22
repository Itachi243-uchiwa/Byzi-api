package com.buzi.api.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Protection anti brute-force des routes d'authentification (OWASP API4:2023).
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
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
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
}
