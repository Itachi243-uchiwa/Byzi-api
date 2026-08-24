package com.byzi.api.exception;

import com.byzi.api.dto.common.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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
 * <p>
 * <b>Limite a @RestController</b>, et ce n'est pas un detail : le back-office est rendu par
 * des @Controller Thymeleaf, et une regle globale leur renvoyait un objet JSON en pleine
 * page de navigateur au lieu d'une page d'erreur. Les erreurs du back-office partent
 * desormais vers /error, qui rend un template HTML.
 * <p>
 * Ce handler ne voit QUE les exceptions levees une fois un controller atteint. Celles que le
 * DispatcherServlet leve avant (route inconnue) passent par /error : c'est
 * {@code ApiErrorAttributes} qui leur donne le meme format, pour que le client iOS n'ait
 * jamais qu'une seule forme d'erreur a decoder.
 */
@Slf4j
@RestControllerAdvice(annotations = RestController.class)
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

    /**
     * Corps de requete illisible : JSON malforme, type incompatible (une chaine la ou un
     * entier est attendu), enum inconnue. Sans ce handler l'exception finissait en 500, ce
     * qui faisait passer une faute du client pour une panne du serveur - et, cote iOS, brouille
     * completement le diagnostic pendant le developpement.
     * <p>
     * Le message d'exception n'est PAS renvoye : il expose les noms de classes Java internes.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.debug("Corps de requete illisible sur {} : {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "malformed_request",
                "Corps de requete illisible ou mal forme.", request);
    }

    /**
     * Parametre de chemin ou de requete du mauvais type - en pratique, presque toujours un
     * UUID mal forme dans l'URL. Le nom du parametre est renvoye, sa valeur non : elle vient
     * du client, la reflechir telle quelle ouvrirait un reflected XSS si la reponse etait un
     * jour rendue dans un navigateur.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "invalid_parameter",
                "Parametre invalide : " + ex.getName() + ".", request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(
            MissingServletRequestParameterException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "missing_parameter",
                "Parametre obligatoire absent : " + ex.getParameterName() + ".", request);
    }

    /**
     * Contraintes de validation portees par les parametres de methode (@Validated sur un
     * @RequestParam ou un @PathVariable), la ou MethodArgumentNotValidException couvre les
     * corps de requete. Deux exceptions distinctes pour un meme sujet : sans les deux, une
     * partie des violations de contrainte tombe en 500.
     */
    @ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class})
    public ResponseEntity<ApiError> handleParameterValidation(Exception ex, HttpServletRequest request) {
        log.debug("Violation de contrainte sur un parametre de {} : {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "validation_error",
                "Un ou plusieurs parametres sont invalides.", request);
    }

    /**
     * 405 plutot que 500. L'en-tete Allow est renseigne : c'est ce que la norme HTTP exige
     * d'une reponse 405, et c'est ce qui permet a un client de comprendre son erreur.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (ex.getSupportedHttpMethods() != null) {
            builder.allow(ex.getSupportedHttpMethods().toArray(new org.springframework.http.HttpMethod[0]));
        }
        return builder.body(error(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed",
                "Methode HTTP non supportee sur cette route.", request));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type",
                "Content-Type non supporte : utiliser application/json.", request);
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
        return ResponseEntity.status(status).body(error(status, code, message, request));
    }

    private ApiError error(HttpStatus status, String code, String message, HttpServletRequest request) {
        return new ApiError(Instant.now(), status.value(), code, message, request.getRequestURI());
    }
}