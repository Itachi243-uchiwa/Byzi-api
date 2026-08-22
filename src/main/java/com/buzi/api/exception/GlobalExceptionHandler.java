package com.buzi.api.exception;

import com.buzi.api.dto.common.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.stream.Collectors;

/**
 * Point de sortie UNIQUE pour toutes les exceptions de l'API : garantit un format de reponse
 * homogene et empeche qu'une exception non geree fasse fuiter une stack trace ou un message
 * d'implementation au client (OWASP A09 - Security Logging and Error Handling).
 * <p>
 * Regle stricte : le "message" renvoye au client est toujours un texte generique et sur.
 * Le detail technique complet (avec stack trace) est journalise cote serveur uniquement,
 * jamais expose dans la reponse HTTP.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "not_found", ex.getMessage(), request);
    }

    @ExceptionHandler(UnauthenticatedException.class)
    public ResponseEntity<ApiError> handleUnauthenticated(UnauthenticatedException ex, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "unauthorized", ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidAppleTokenException.class)
    public ResponseEntity<ApiError> handleInvalidAppleToken(InvalidAppleTokenException ex, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "invalid_apple_token", "Authentification Apple invalide.", request);
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ApiError> handleInvalidRefreshToken(InvalidRefreshTokenException ex, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "invalid_refresh_token", "Refresh token invalide ou expire.", request);
    }

    @ExceptionHandler(ForbiddenOperationException.class)
    public ResponseEntity<ApiError> handleForbiddenOperation(ForbiddenOperationException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "forbidden_operation", ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, "validation_error", message, request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Violation de contrainte d'integrite sur {} : {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.CONFLICT, "data_conflict", "Conflit de donnees.", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        // Seule ligne du fichier qui journalise la stack trace complete - pour tout le reste,
        // le message d'exception seul (deja explicite) suffit et evite du bruit dans les logs.
        log.error("Erreur non geree sur {}", request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                "Une erreur inattendue est survenue.", request);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message, HttpServletRequest request) {
        ApiError error = new ApiError(Instant.now(), status.value(), code, message, request.getRequestURI());
        return ResponseEntity.status(status).body(error);
    }
}