package com.buzi.api.security;

import com.buzi.api.domain.Role;
import com.buzi.api.exception.UnauthenticatedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SecurityUtils est le point d'acces UNIQUE a l'identite de l'appelant : si elle renvoyait un
 * userId dans un contexte non authentifie, tout le scoping par proprietaire de l'API tomberait.
 */
class SecurityUtilsTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(UUID userId, Role role) {
        JwtPrincipal principal = new JwtPrincipal(userId, role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }

    @Test
    void returnsUserIdFromAuthenticatedPrincipal() {
        UUID userId = UUID.randomUUID();
        authenticateAs(userId, Role.USER);

        assertThat(SecurityUtils.currentUserId()).isEqualTo(userId);
    }

    @Test
    void returnsFullPrincipal() {
        UUID userId = UUID.randomUUID();
        authenticateAs(userId, Role.ADMIN);

        assertThat(SecurityUtils.currentPrincipal()).isEqualTo(new JwtPrincipal(userId, Role.ADMIN));
    }

    @Test
    void failsWhenNoAuthenticationInContext() {
        assertThatThrownBy(SecurityUtils::currentUserId).isInstanceOf(UnauthenticatedException.class);
        assertThatThrownBy(SecurityUtils::currentPrincipal).isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    void failsWhenPrincipalIsNotAByziPrincipal() {
        // Un principal d'un autre type (ex. chaine de caracteres posee par un autre mecanisme
        // d'authentification) ne doit jamais etre interprete comme un utilisateur Byzi.
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("un-simple-nom", null, List.of()));

        assertThatThrownBy(SecurityUtils::currentUserId).isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    void failsForAnonymousAuthentication() {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser",
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThatThrownBy(SecurityUtils::currentUserId).isInstanceOf(UnauthenticatedException.class);
    }
}
