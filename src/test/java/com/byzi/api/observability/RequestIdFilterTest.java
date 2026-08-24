package com.byzi.api.observability;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L'identifiant de requete est ce qui relie un incident signale par l'app iOS aux lignes de
 * log du serveur. Trois proprietes le rendent utile, et chacune casse silencieusement : il
 * doit etre present, unique, et nettoye du MDC a la fin de la requete.
 */
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    private MockHttpServletResponse call(String clientProvidedId) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        if (clientProvidedId != null) {
            request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, clientProvidedId);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
        });
        return response;
    }

    @Test
    void putsARequestIdInTheResponse() throws Exception {
        assertThat(call(null).getHeader(RequestIdFilter.REQUEST_ID_HEADER))
                .isNotBlank()
                .hasSize(12);
    }

    @Test
    void generatesADistinctIdPerRequest() throws Exception {
        // Un identifiant constant ne correle rien du tout.
        assertThat(call(null).getHeader(RequestIdFilter.REQUEST_ID_HEADER))
                .isNotEqualTo(call(null).getHeader(RequestIdFilter.REQUEST_ID_HEADER));
    }

    @Test
    void exposesTheIdToTheLoggingContextDuringTheRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String[] seenInsideChain = new String[1];
        FilterChain chain = (req, res) -> seenInsideChain[0] = MDC.get(RequestIdFilter.MDC_REQUEST_ID);

        filter.doFilter(request, response, chain);

        assertThat(seenInsideChain[0])
                .as("le motif de log lit cette cle : vide, chaque ligne perd sa correlation")
                .isEqualTo(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER));
    }

    @Test
    void clearsTheLoggingContextAfterTheRequest() throws Exception {
        call(null);

        // Les threads du conteneur sont recycles : un MDC non nettoye ferait porter
        // l'identifiant d'une requete aux logs de la requete suivante, ce qui est pire
        // qu'une absence d'identifiant.
        assertThat(MDC.get(RequestIdFilter.MDC_REQUEST_ID)).isNull();
        assertThat(MDC.get(RequestIdFilter.MDC_UPSTREAM_REQUEST_ID)).isNull();
    }

    @Test
    void neverReusesTheIdentifierSuppliedByTheClient() throws Exception {
        String forged = "aaaaaaaaaaaa";

        // Reprendre la valeur du client permettrait d'envoyer la meme a chaque appel et de
        // rendre la correlation inutilisable - ou pire, d'y glisser du texte arbitraire.
        assertThat(call(forged).getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isNotEqualTo(forged);
    }

    @Test
    void stripsLogInjectionAttemptsFromTheUpstreamIdentifier() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "abc\n2026-01-01 ERROR faux message");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String[] recorded = new String[1];
        filter.doFilter(request, response,
                (req, res) -> recorded[0] = MDC.get(RequestIdFilter.MDC_UPSTREAM_REQUEST_ID));

        // Le saut de ligne forgerait une fausse entree de journal (OWASP A09).
        assertThat(recorded[0]).doesNotContain("\n").isEqualTo("abc2026-01-01ERRORfauxmessage");
    }
}
