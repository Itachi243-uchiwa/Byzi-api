package com.buzi.api.security;

import com.buzi.api.exception.UnauthenticatedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/**
 * Point d'acces UNIQUE a l'identite de l'utilisateur authentifie.
 * <p>
 * Centraliser cette lecture ici (plutot que de laisser chaque controller lire le
 * SecurityContext a sa maniere) evite qu'un controller lise par erreur un userId
 * depuis le corps de la requete ou un parametre d'URL - la source de verite de
 * "qui suis-je" est TOUJOURS le token JWT valide, jamais une donnee fournie par le
 * client dans le payload (defense de fond contre l'IDOR, OWASP A01).
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static UUID currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof JwtPrincipal principal)) {
            throw new UnauthenticatedException("Aucun utilisateur authentifie dans le contexte courant");
        }
        return principal.userId();
    }

    public static JwtPrincipal currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof JwtPrincipal principal)) {
            throw new UnauthenticatedException("Aucun utilisateur authentifie dans le contexte courant");
        }
        return principal;
    }
}