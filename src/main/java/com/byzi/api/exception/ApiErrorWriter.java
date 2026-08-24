package com.byzi.api.exception;

import com.byzi.api.dto.common.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;

/**
 * Ecrit un {@link ApiError} directement dans la reponse servlet, pour les composants qui
 * s'executent AVANT que Spring MVC n'entre en jeu et ne peuvent donc pas passer par
 * {@link GlobalExceptionHandler} : l'entree d'authentification, le refus d'acces et le
 * filtre de limitation de debit.
 * <p>
 * Ces trois-la serialisaient chacun leur propre ApiError, et avaient deja diverge : le code
 * d'erreur du 401 valait {@code "Unauthorized"} quand tout le reste de l'API emet du
 * snake_case, et la limitation de debit emettait un objet JSON d'une forme entierement
 * differente. Un client doit pouvoir decoder UNE structure, pas trois.
 */
@Component
public class ApiErrorWriter {

    private final ObjectMapper objectMapper;

    public ApiErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String code,
            String message
    ) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        ApiError error = new ApiError(Instant.now(), status.value(), code, message, request.getRequestURI());
        response.getWriter().write(objectMapper.writeValueAsString(error));
    }
}
