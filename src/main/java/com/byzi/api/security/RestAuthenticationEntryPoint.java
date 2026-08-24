package com.byzi.api.security;

import com.byzi.api.exception.ApiErrorWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Reponse a une requete API non authentifiee : un 401 au format ApiError, jamais la
 * redirection vers un formulaire de connexion que Spring Security produirait par defaut -
 * l'app iOS ne saurait qu'en faire.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ApiErrorWriter errorWriter;

    public RestAuthenticationEntryPoint(ApiErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException {
        // Le code etait "Unauthorized" : ni le snake_case du reste de l'API, ni une valeur
        // que le client puisse comparer sans se demander laquelle des deux graphies arrive.
        errorWriter.write(request, response, HttpStatus.UNAUTHORIZED, "unauthorized",
                "Authentification requise ou token invalide.");
    }
}
