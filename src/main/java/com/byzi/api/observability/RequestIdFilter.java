package com.byzi.api.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Attache un identifiant unique a chaque requete, le place dans le MDC pour que le motif de
 * journalisation le fasse apparaitre sur CHAQUE ligne de log, et le renvoie au client dans
 * l'en-tete {@code X-Request-Id}.
 * <p>
 * Sans cela, diagnostiquer un incident signale par l'app iOS consistait a chercher dans les
 * logs par horodatage approximatif, en esperant qu'un seul utilisateur soit concerne. Avec
 * cet identifiant, un rapport de bug qui cite le {@code X-Request-Id} de la reponse mene
 * directement aux lignes de log de cette requete-la.
 * <p>
 * L'identifiant fourni par le client est REGENERE et non repris tel quel : c'est une donnee
 * non fiable, qui finit dans les fichiers de log. Un client pourrait y glisser des sauts de
 * ligne et forger de fausses entrees de journal (log injection, OWASP A09), ou simplement
 * envoyer la meme valeur a chaque appel et rendre la correlation inutilisable. Seule sa
 * presence est conservee, sous une cle distincte, pour les cas ou un systeme amont trace
 * deja la requete.
 * <p>
 * Priorite maximale : le filtre doit s'executer avant la chaine de securite (ordre -100),
 * pour que meme un 401 - le cas ou le diagnostic est le plus utile - porte un identifiant.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    static final String MDC_REQUEST_ID = "requestId";
    static final String MDC_UPSTREAM_REQUEST_ID = "upstreamRequestId";

    /** Assez long pour ne pas collisionner sur une journee de logs, assez court pour se citer. */
    private static final int REQUEST_ID_LENGTH = 12;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String requestId = newRequestId();
        MDC.put(MDC_REQUEST_ID, requestId);

        String upstream = sanitize(request.getHeader(REQUEST_ID_HEADER));
        if (upstream != null) {
            MDC.put(MDC_UPSTREAM_REQUEST_ID, upstream);
        }

        // Pose avant la suite de la chaine : une reponse d'erreur ecrite par un filtre de
        // securite ne repasse pas par ici, et doit pourtant porter l'en-tete.
        response.setHeader(REQUEST_ID_HEADER, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // Obligatoire : les threads du conteneur sont recycles, et un MDC non nettoye
            // ferait porter l'identifiant d'une requete aux logs de la suivante.
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_UPSTREAM_REQUEST_ID);
        }
    }

    private String newRequestId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, REQUEST_ID_LENGTH);
    }

    /**
     * Ne conserve que des caracteres inoffensifs dans un fichier de log, et borne la longueur :
     * une valeur d'en-tete est entierement sous le controle de l'appelant.
     */
    private String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.replaceAll("[^A-Za-z0-9._-]", "");
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }
}
