package com.byzi.api.config;

import com.byzi.api.domain.Role;
import com.byzi.api.exception.ApiErrorWriter;
import com.byzi.api.security.RateLimitingFilter;
import com.byzi.api.security.RestAccessDeniedHandler;
import com.byzi.api.security.RestAuthenticationEntryPoint;
import com.byzi.api.security.jwt.JwtAuthenticationFilter;
import com.byzi.api.security.jwt.JwtService;
import jakarta.servlet.Filter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
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
 * @Bean plutot que via @Component sur leur propre classe, afin de maitriser leur position
 * exacte dans chaque chaine. Attention : cela ne suffit PAS a empecher leur enregistrement
 * automatique comme filtres servlet generiques - Spring Boot enregistre tout bean de type
 * Filter, quelle que soit la facon dont il est declare. C'est le role des
 * FilterRegistrationBean desactives plus bas.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    private final JwtService jwtService;
    private final ApiErrorWriter apiErrorWriter;

    @Value("${byzi.security.cors.allowed-origins}")
    private List<String> allowedOrigins;

    public SecurityConfig(
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler,
            JwtService jwtService,
            ApiErrorWriter apiErrorWriter
    ) {
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.jwtService = jwtService;
        this.apiErrorWriter = apiErrorWriter;
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter() {
        return new JwtAuthenticationFilter(jwtService);
    }

    @Bean
    public RateLimitingFilter rateLimitingFilter() {
        return new RateLimitingFilter(apiErrorWriter);
    }

    /**
     * Spring Boot enregistre automatiquement, aupres du conteneur servlet, tout bean de type
     * Filter qu'il trouve dans le contexte - y compris ceux que l'on destine a une chaine de
     * securite precise. Les deux filtres ci-dessus tourneraient donc DEUX fois : une fois a
     * leur place voulue dans la chaine Spring Security, une fois sur toutes les requetes de
     * l'application, y compris celles que la chaine n'aurait pas retenues.
     * <p>
     * OncePerRequestFilter neutralise aujourd'hui la seconde execution, mais c'est une
     * protection de hasard : elle ne tient que tant que la chaine de securite s'execute en
     * premier. On coupe donc l'enregistrement automatique a la source. C'est la maniere
     * canonique de le faire : un FilterRegistrationBean explicite, marque setEnabled(false).
     */
    @Bean
    public FilterRegistrationBean<RateLimitingFilter> rateLimitingFilterServletRegistration(
            RateLimitingFilter filter
    ) {
        return disableServletRegistration(filter);
    }

    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterServletRegistration(
            JwtAuthenticationFilter filter
    ) {
        return disableServletRegistration(filter);
    }

    private <T extends Filter> FilterRegistrationBean<T> disableServletRegistration(T filter) {
        FilterRegistrationBean<T> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * Hierarchie des roles d'administration (story 17.4).
     * <p>
     * ADMIN implique les deux roles specialises : une regle ecrite
     * {@code hasRole('ADMIN_FINANCE')} est donc satisfaite par un administrateur complet, sans
     * avoir a enumerer les roles a chaque annotation. Sans cette hierarchie, chaque
     * @PreAuthorize devrait lister tous les roles habilites - et l'un d'eux finirait par etre
     * oublie, ce qui fermerait silencieusement une action a l'administrateur complet.
     * <p>
     * La relation n'est volontairement PAS transitive entre support et finance : ce sont deux
     * metiers distincts, pas deux niveaux d'un meme escalier.
     */
    @Bean
    public RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role(Role.ADMIN.name()).implies(Role.ADMIN_SUPPORT.name(), Role.ADMIN_FINANCE.name())
                .build();
    }

    /**
     * La hierarchie ci-dessus ne s'applique aux annotations @PreAuthorize que si elle est
     * injectee dans l'evaluateur d'expressions de la securite de methode. C'est un piege
     * classique : declarer le bean RoleHierarchy suffit pour les regles d'URL, et laisse les
     * annotations l'ignorer en silence - un ADMIN se verrait refuser une action reservee au
     * role finance, sans qu'aucune erreur ne le signale.
     */
    @Bean
    static MethodSecurityExpressionHandler methodSecurityExpressionHandler(RoleHierarchy roleHierarchy) {
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        handler.setRoleHierarchy(roleHierarchy);
        return handler;
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
                        // Deny-by-default : le reste du back-office exige un role d'administration,
                        // quel qu'il soit. Le detail de ce que chaque role a le droit de FAIRE est
                        // porte par les @PreAuthorize des services - au plus pres de l'effet, donc
                        // valable quel que soit le point d'entree.
                        .anyRequest().hasAnyRole(Role.adminRoleNames()))
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
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN)))
                // Le rate limiting doit AUSSI s'appliquer ici. Declare uniquement sur la chaine
                // API, il laissait /admin/login sans aucune protection contre le brute-force :
                // c'est pourtant la seule authentification par mot de passe du systeme, et celle
                // qui ouvre la suppression de comptes et les donnees de tous les utilisateurs.
                .addFilterBefore(rateLimitingFilter(), UsernamePasswordAuthenticationFilter.class);

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
                        // Le dispatch vers /error doit rester ouvert : c'est par lui que passent les
                        // erreurs de TOUTE l'application, back-office compris. L'exiger authentifie
                        // transformerait chaque 404 en 401, y compris pour un visiteur non connecte,
                        // et masquerait la vraie erreur derriere une erreur d'authentification.
                        // Aucune donnee n'y transite : ApiErrorAttributes n'expose que statut,
                        // code, message generique et chemin.
                        .requestMatchers("/error").permitAll()
                        // Sonde de sante (load balancer / orchestrateur), pas de donnee sensible exposee.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // Tout le reste de l'actuator est reserve aux ADMIN. Il tombait sinon dans
                        // le "anyRequest().authenticated()" plus bas, c'est-a-dire lisible par
                        // n'importe quel utilisateur de l'app muni d'un JWT valide - alors que ces
                        // endpoints decrivent l'etat interne du serveur.
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        // Documentation API - a restreindre/desactiver en production si besoin (application-prod.yml).
                        //
                        // "/swagger-ui.html" est liste EN PLUS de "/swagger-ui/**" : c'est le chemin
                        // configure dans application.yml et celui que tout le projet documente, mais
                        // il ne tombe pas sous le motif "/swagger-ui/**" (pas de separateur avant
                        // ".html"). Sans lui, la redirection vers /swagger-ui/index.html repondait 401
                        // et la documentation paraissait cassee sur tout environnement deploye, alors
                        // meme que SWAGGER_UI_ENABLED valait true.
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
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
        // false : l'API est stateless et purement Bearer, aucun cookie n'est jamais envoye par
        // un client. Autoriser les credentials n'apporterait donc rien et elargirait inutilement
        // ce que le navigateur accepte de transmettre a une origine de la liste blanche.
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}