package com.buzi.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Limitation de debit simple (fenetre fixe) appliquee uniquement aux routes /api/v1/auth/**,
 * la seule surface accessible sans JWT prealable et donc la cible naturelle du
 * brute-force / credential stuffing (OWASP API4:2023 - Unrestricted Resource Consumption).
 * <p>
 * NOTE IMPORTANTE : implementation en memoire locale, adaptee a une seule instance.
 * Des que Byzi tourne sur plusieurs instances (scaling horizontal), remplacer par un
 * compteur partage (ex. Redis + Bucket4j) pour que la limite s'applique globalement
 * et non par instance.
 * <p>
 * Volontairement PAS annote @Component, pour la meme raison que JwtAuthenticationFilter :
 * declare comme @Bean explicite dans SecurityConfig pour eviter un double enregistrement.
 */
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final int MAX_REQUESTS_PER_WINDOW = 10;
    private static final long WINDOW_SECONDS = 60;

    private final ConcurrentHashMap<String, Window> windowsByClient = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        if (!request.getRequestURI().startsWith("/api/v1/auth/")) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientKey = clientKey(request);
        Window window = windowsByClient.computeIfAbsent(clientKey, k -> new Window());

        if (window.isExpired()) {
            window.reset();
        }

        if (window.count.incrementAndGet() > MAX_REQUESTS_PER_WINDOW) {
            response.setStatus(429); // Too Many Requests
            response.setContentType("application/json");
            response.getWriter().write("""
                    {"error":"too_many_requests","message":"Trop de tentatives, reessaie dans une minute."}""");
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Cle du "seau" de rate limiting. L'adresse IP directe (pas de header X-Forwarded-For non
     * verifie, facilement falsifiable) est utilisee par defaut ; a adapter selon la topologie
     * reseau reelle (reverse proxy de confiance) au moment du deploiement.
     */
    private String clientKey(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    private static final class Window {
        private final AtomicInteger count = new AtomicInteger(0);
        private volatile Instant windowStart = Instant.now();

        boolean isExpired() {
            return Instant.now().isAfter(windowStart.plusSeconds(WINDOW_SECONDS));
        }

        synchronized void reset() {
            if (isExpired()) {
                windowStart = Instant.now();
                count.set(0);
            }
        }
    }
}