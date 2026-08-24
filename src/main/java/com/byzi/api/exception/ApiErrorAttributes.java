package com.byzi.api.exception;

import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.WebRequest;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Donne aux erreurs traitees par /error exactement la meme forme que celles de
 * {@link GlobalExceptionHandler} - c'est-a-dire celle de
 * {@link com.byzi.api.dto.common.ApiError}.
 * <p>
 * Pourquoi c'est necessaire : le GlobalExceptionHandler ne voit que les exceptions levees
 * une fois un controller atteint. Une route inexistante n'atteint aucun controller ; le
 * DispatcherServlet la renvoie vers /error, ou Spring Boot produisait sa propre forme
 * ({@code "error": "Not Found"}, un libelle anglais lisible par un humain mais instable, et
 * pas de {@code message}). Le client iOS se retrouvait avec DEUX formes d'erreur a decoder
 * selon que l'URL etait fautive ou la requete.
 * <p>
 * La cle {@code error} porte donc ici un code stable en snake_case, destine a etre teste par
 * le code client, la ou {@code message} s'adresse a un humain. Les messages restent
 * generiques : /error est aussi le chemin des erreurs 500, ou tout detail supplementaire
 * serait une fuite d'information (OWASP A09).
 */
@Component
public class ApiErrorAttributes extends DefaultErrorAttributes {

    @Override
    public Map<String, Object> getErrorAttributes(WebRequest webRequest, ErrorAttributeOptions options) {
        Map<String, Object> defaults = super.getErrorAttributes(webRequest, options);

        HttpStatus status = resolveStatus(defaults.get("status"));

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("timestamp", Instant.now());
        attributes.put("status", status.value());
        attributes.put("error", codeFor(status));
        attributes.put("message", messageFor(status));
        attributes.put("path", defaults.getOrDefault("path", ""));
        return attributes;
    }

    private HttpStatus resolveStatus(Object rawStatus) {
        if (rawStatus instanceof Integer value) {
            HttpStatus resolved = HttpStatus.resolve(value);
            if (resolved != null) {
                return resolved;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    /**
     * Les codes repris a l'identique de GlobalExceptionHandler sont ecrits en dur plutot que
     * derives du nom du statut : ce sont des constantes du contrat d'API, et le client Swift
     * les comparera litteralement. Les deriver automatiquement les ferait changer au gre d'une
     * montee de version de Spring.
     */
    private String codeFor(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "bad_request";
            case UNAUTHORIZED -> "unauthorized";
            case FORBIDDEN -> "forbidden";
            case NOT_FOUND -> "not_found";
            case METHOD_NOT_ALLOWED -> "method_not_allowed";
            case NOT_ACCEPTABLE -> "not_acceptable";
            case UNSUPPORTED_MEDIA_TYPE -> "unsupported_media_type";
            case TOO_MANY_REQUESTS -> "too_many_requests";
            default -> status.is4xxClientError() ? "bad_request" : "internal_error";
        };
    }

    private String messageFor(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> "Ressource ou route introuvable.";
            case METHOD_NOT_ALLOWED -> "Methode HTTP non supportee sur cette route.";
            case UNSUPPORTED_MEDIA_TYPE -> "Content-Type non supporte : utiliser application/json.";
            case NOT_ACCEPTABLE -> "Aucune representation acceptable pour cet Accept.";
            case UNAUTHORIZED -> "Authentification requise.";
            case FORBIDDEN -> "Acces refuse.";
            case TOO_MANY_REQUESTS -> "Trop de tentatives, reessaie dans une minute.";
            default -> status.is4xxClientError()
                    ? "Requete invalide."
                    : "Une erreur inattendue est survenue.";
        };
    }
}
