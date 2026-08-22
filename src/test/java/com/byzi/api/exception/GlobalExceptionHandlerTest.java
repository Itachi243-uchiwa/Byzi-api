package com.byzi.api.exception;

import com.byzi.api.dto.common.ApiError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le handler est le point de sortie unique des erreurs : sa regle non negociable est qu'aucun
 * detail d'implementation (message d'exception technique, stack trace, requete SQL) ne doit
 * atteindre le client (OWASP A09).
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest("GET", "/api/v1/focus-sessions/42");
        request.setRequestURI("/api/v1/focus-sessions/42");
    }

    @Test
    void notFoundReturns404WithStableCode() {
        ResponseEntity<ApiError> response =
                handler.handleNotFound(new ResourceNotFoundException("Session introuvable"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("not_found");
        assertThat(response.getBody().path()).isEqualTo("/api/v1/focus-sessions/42");
    }

    @Test
    void unauthenticatedReturns401() {
        ResponseEntity<ApiError> response =
                handler.handleUnauthenticated(new UnauthenticatedException("pas de contexte"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().error()).isEqualTo("unauthorized");
    }

    @Test
    void appleTokenFailureDoesNotLeakTechnicalDetail() {
        ResponseEntity<ApiError> response = handler.handleInvalidAppleToken(
                new InvalidAppleTokenException("Signed JWT rejected: no matching key(s) found"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // Le detail cryptographique reste dans les logs serveur, jamais dans la reponse.
        assertThat(response.getBody().message()).doesNotContain("JWT").doesNotContain("key");
    }

    @Test
    void refreshTokenFailureDoesNotLeakTechnicalDetail() {
        ResponseEntity<ApiError> response = handler.handleInvalidRefreshToken(
                new InvalidRefreshTokenException("token hash 9f2a... deja revoque"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().message()).doesNotContain("9f2a");
    }

    @Test
    void dataIntegrityViolationDoesNotLeakConstraintNames() {
        ResponseEntity<ApiError> response = handler.handleDataIntegrity(
                new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"uk_users_apple_sub\""),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // Divulguer le nom d'une contrainte renseigne sur le schema de la base.
        assertThat(response.getBody().message()).doesNotContain("uk_users_apple_sub");
    }

    @Test
    void unexpectedExceptionReturnsGenericMessage() {
        ResponseEntity<ApiError> response = handler.handleUnexpected(
                new NullPointerException("Cannot invoke \"User.getId()\" because \"user\" is null"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().error()).isEqualTo("internal_error");
        assertThat(response.getBody().message()).doesNotContain("getId").doesNotContain("null");
    }

    @Test
    void forbiddenOperationReturnsConflict() {
        ResponseEntity<ApiError> response = handler.handleForbiddenOperation(
                new ForbiddenOperationException("Operation impossible"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().error()).isEqualTo("forbidden_operation");
    }

    @Test
    void everyErrorCarriesTimestampStatusAndPath() {
        ApiError error = handler.handleNotFound(new ResourceNotFoundException("x"), request).getBody();

        assertThat(error.timestamp()).isNotNull();
        assertThat(error.status()).isEqualTo(404);
        assertThat(error.path()).isEqualTo("/api/v1/focus-sessions/42");
    }
}
