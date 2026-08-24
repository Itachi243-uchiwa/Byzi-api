package com.byzi.api.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Limitation de debit (fenetre fixe) sur les seules surfaces accessibles sans JWT prealable,
 * cibles naturelles du brute-force et du credential stuffing (OWASP API4:2023 - Unrestricted
 * Resource Consumption).
 * <p>
 * Seules les requetes POST sont comptees : brute-forcer se fait en POST, alors que limiter les
 * GET bloquerait un administrateur qui recharge simplement sa page de connexion.
 * <p>
 * <b>Deploiement derriere un reverse proxy</b> : la cle de comptage est
 * {@code getRemoteAddr()}, qui vaut l'IP du proxy pour TOUS les clients si les en-tetes
 * transmis ne sont pas exploites - la limite deviendrait alors globale et casserait
 * l'authentification de toute l'application. La configuration de production active donc
 * {@code server.forward-headers-strategy: framework}, ce qui suppose que le proxy ECRASE le
 * {@code X-Forwarded-For} recu du client (comportement par defaut de Caddy et du
 * {@code proxy_set_header} recommande de nginx). Sans cette garantie cote proxy, l'en-tete
 * redevient falsifiable et la limite contournable.
 * <p>
 * NOTE : compteur en memoire locale, donc valable pour UNE instance. Des que Byzi tourne sur
 * plusieurs instances, remplacer par un compteur partage (Redis + Bucket4j) pour que la limite
 * s'applique globalement et non par instance.
 * <p>
 * Volontairement PAS annote @Component : declare comme @Bean explicite dans SecurityConfig
 * pour maitriser sa position dans chaque chaine de securite.
 */
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final Duration WINDOW = Duration.ofSeconds(60);

    /** Nombre maximal de clients suivis simultanement, cf. commentaire du cache ci-dessous. */
    private static final int MAX_TRACKED_CLIENTS = 100_000;

    /**
     * Une surface protegee : un prefixe d'URL et le nombre de POST tolere par fenetre et par
     * client. Le plafond est porte par la surface, et non partage, parce que les trafics
     * legitimes n'ont rien de comparable - cf. {@link #DEFAULT_SURFACES}.
     */
    public record Surface(String pathPrefix, int maxRequestsPerWindow) {
    }

    /**
     * <ul>
     *   <li>{@code /api/v1/auth/} - echange du token Apple et rotation des refresh tokens.
     *       Trafic humain : une dizaine de tentatives par minute depasse deja largement ce
     *       qu'une app legitime demande.</li>
     *   <li>{@code /admin/login} - la SEULE authentification par mot de passe du systeme, et
     *       celle qui donne acces a la suppression de comptes. Elle n'etait pas protegee : le
     *       filtre n'etait branche que sur la chaine API, que le back-office ne traverse
     *       pas.</li>
     *   <li>{@code /api/v1/webhooks/} - endpoint necessairement ouvert, dont le secret partage
     *       pouvait etre martele sans limite. Son plafond est deux ordres de grandeur au-dessus
     *       des deux autres, et c'est deliberé : RevenueCat appelle depuis un petit nombre
     *       d'IP fixes, pour TOUS les abonnes a la fois, avec des rejeux en rafale apres une
     *       panne reseau. Un plafond a taille humaine y ferait perdre des evenements de
     *       facturation - un abonne qui renouvelle et perd son acces. A 600 par minute, le
     *       trafic legitime passe tandis qu'une recherche exhaustive du secret reste hors de
     *       portee : le secret est long et aleatoire, le plafond ne fait que borner le
     *       debit.</li>
     * </ul>
     */
    private static final List<Surface> DEFAULT_SURFACES = List.of(
            new Surface("/api/v1/auth/", 10),
            new Surface("/admin/login", 10),
            new Surface("/api/v1/webhooks/", 600));

    private final List<Surface> surfaces;

    /**
     * Caffeine et non une ConcurrentHashMap : la map precedente n'evacuait jamais ses entrees.
     * Une entree par adresse IP, conservee indefiniment, suffisait a faire grossir le tas
     * jusqu'a l'OutOfMemoryError - il suffisait de faire varier l'IP source. L'expiration et le
     * plafond de taille bornent desormais la memoire consommee quoi qu'il arrive.
     */
    private final Cache<String, Window> windowsByClient;

    public RateLimitingFilter() {
        this(DEFAULT_SURFACES);
    }

    public RateLimitingFilter(List<Surface> surfaces) {
        this.surfaces = List.copyOf(surfaces);
        this.windowsByClient = Caffeine.newBuilder()
                // Deux fenetres : assez pour qu'une entree survive a la fenetre qu'elle mesure,
                // sans conserver des compteurs devenus sans objet.
                .expireAfterWrite(WINDOW.multipliedBy(2))
                .maximumSize(MAX_TRACKED_CLIENTS)
                .build();
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        Surface surface = matchedSurface(request);
        if (surface == null) {
            filterChain.doFilter(request, response);
            return;
        }

        Window window = windowsByClient.get(clientKey(request, surface), key -> new Window());

        if (window.isExpired()) {
            window.reset();
        }

        if (window.count.incrementAndGet() > surface.maxRequestsPerWindow()) {
            response.setStatus(429); // Too Many Requests
            response.setContentType("application/json");
            response.getWriter().write("""
                    {"error":"too_many_requests","message":"Trop de tentatives, reessaie dans une minute."}""");
            return;
        }

        filterChain.doFilter(request, response);
    }

    /** @return la surface protegee correspondante, ou null si la requete n'est pas concernee. */
    private Surface matchedSurface(HttpServletRequest request) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return null;
        }
        String uri = request.getRequestURI();
        return surfaces.stream()
                .filter(surface -> uri.startsWith(surface.pathPrefix()))
                .findFirst()
                .orElse(null);
    }

    /**
     * La cle combine l'adresse du client et la SURFACE - pas l'URI complete.
     * <p>
     * La surface, parce que les tentatives de connexion au back-office et les appels
     * d'authentification de l'app iOS ne doivent pas partager le meme seau : depuis un reseau
     * d'entreprise ou tout le monde sort par la meme IP publique, un administrateur qui se
     * trompe de mot de passe bloquerait les utilisateurs de l'app, et reciproquement.
     * <p>
     * Pas l'URI complete, parce que cela offrirait un quota separe a chaque route d'une meme
     * surface : /auth/apple et /auth/refresh cumuleraient deux fois la limite alors qu'ils
     * constituent une seule et meme surface d'attaque.
     */
    private String clientKey(HttpServletRequest request, Surface surface) {
        return request.getRemoteAddr() + "|" + surface.pathPrefix();
    }

    private static final class Window {
        private final AtomicInteger count = new AtomicInteger(0);
        private volatile Instant windowStart = Instant.now();

        boolean isExpired() {
            return Instant.now().isAfter(windowStart.plus(WINDOW));
        }

        synchronized void reset() {
            if (isExpired()) {
                windowStart = Instant.now();
                count.set(0);
            }
        }
    }
}
