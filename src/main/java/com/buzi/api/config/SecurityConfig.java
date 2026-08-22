package com.buzi.api.config;

import com.buzi.api.security.RateLimitingFilter;
import com.buzi.api.security.RestAccessDeniedHandler;
import com.buzi.api.security.RestAuthenticationEntryPoint;
import com.buzi.api.security.jwt.JwtAuthenticationFilter;
import com.buzi.api.security.jwt.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Configuration Spring Security de bout en bout pour une API stateless consommee
 * exclusivement par l'app iOS (et plus tard le back-office, EPIC-09).
 * <p>
 * Principes OWASP appliques :
 * - deny-by-default : tout endpoint non explicitement permitAll() exige un JWT valide
 * - CSRF desactive DELIBEREMENT : legitime uniquement parce que l'API est 100% stateless
 *   (aucun cookie de session, authentification par header Authorization Bearer only,
 *   donc aucune requete "portee par le navigateur" ne peut etre rejouee involontairement)
 * - en-tetes de securite explicites (HSTS, no-sniff, frame-options, referrer-policy)
 * - CORS restreint a une liste blanche d'origines configurable par environnement
 * <p>
 * Les filtres custom (JwtAuthenticationFilter, RateLimitingFilter) sont declares ici comme
 * @Bean plutot que via @Component sur leur propre classe : cela evite que Spring Boot les
 * enregistre AUSSI automatiquement comme filtres servlet generiques sur toutes les requetes
 * (double execution / ordre ambigu), en plus de leur position precise dans cette chaine.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    private final JwtService jwtService;

    @Value("${byzi.security.cors.allowed-origins}")
    private List<String> allowedOrigins;

    public SecurityConfig(
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler,
            JwtService jwtService
    ) {
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.jwtService = jwtService;
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter() {
        return new JwtAuthenticationFilter(jwtService);
    }

    @Bean
    public RateLimitingFilter rateLimitingFilter() {
        return new RateLimitingFilter();
    }

    /**
     * BCrypt : algorithme lent par construction, avec sel integre. Un hash rapide (SHA-256 &
     * co.) permettrait de tester des milliards de mots de passe par seconde si la base
     * fuitait (OWASP A02 - Cryptographic Failures).
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Chaine du back-office (EPIC-09.1), declaree AVANT celle de l'API (@Order(1)) et limitee
     * a /admin/** par securityMatcher.
     * <p>
     * Elle est volontairement l'exact oppose de la chaine API : session + formulaire de
     * connexion + CSRF actif, la ou l'API est stateless + Bearer + CSRF desactive. Ce sont
     * deux modeles d'authentification differents pour deux clients differents (navigateur vs
     * app iOS), et les melanger dans une chaine unique reviendrait a appliquer a l'un les
     * compromis de l'autre - notamment a desactiver CSRF sur des formulaires portes par des
     * cookies de session, ce qui serait une vraie faille.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain adminSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/admin/**")
                // CSRF ACTIF ici : les formulaires admin sont portes par un cookie de session,
                // donc rejouables par un site tiers sans jeton anti-CSRF.
                .csrf(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        // Les ressources statiques du back-office doivent rester accessibles
                        // sans session : elles sont chargees par la page de connexion
                        // elle-meme, donc avant toute authentification.
                        .requestMatchers("/admin/login", "/admin/css/**", "/admin/images/**").permitAll()
                        // Deny-by-default : tout le reste du back-office exige le role ADMIN.
                        .anyRequest().hasRole("ADMIN"))
                .formLogin(form -> form
                        .loginPage("/admin/login")
                        .loginProcessingUrl("/admin/login")
                        .defaultSuccessUrl("/admin", true)
                        .failureUrl("/admin/login?error")
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/admin/logout")
                        .logoutSuccessUrl("/admin/login?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID"))
                .headers(headers -> headers
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(frame -> frame.deny())
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000))
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN)));

        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Stateless + Bearer only => le CSRF classique (base sur les cookies de session)
                // ne s'applique pas a ce modele d'authentification.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .headers(headers -> headers
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(frame -> frame.deny())
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000))
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .authorizeHttpRequests(auth -> auth
                        // Auth Apple + refresh : doivent rester accessibles sans JWT prealable.
                        .requestMatchers("/api/v1/auth/apple", "/api/v1/auth/refresh").permitAll()
                        // Webhooks tiers : appeles par un serveur, jamais par un utilisateur, donc
                        // aucun JWT possible. L'authentification se fait par secret partage verifie
                        // dans le controller (WebhookAuthenticator), pas par cette chaine.
                        .requestMatchers("/api/v1/webhooks/**").permitAll()
                        // Sonde de sante (load balancer / orchestrateur), pas de donnee sensible exposee.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // Documentation API - a restreindre/desactiver en production si besoin (application-prod.yml).
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // Back-office (EPIC-09) : reserve au role ADMIN, verifie en plus a chaque
                        // methode sensible par @PreAuthorize dans les controllers admin (defense en profondeur).
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(rateLimitingFilter(), UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(jwtAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // Liste blanche explicite par environnement (application-dev.yml / application-prod.yml),
        // jamais de wildcard "*" combine a allowCredentials(true) (OWASP A05).
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}