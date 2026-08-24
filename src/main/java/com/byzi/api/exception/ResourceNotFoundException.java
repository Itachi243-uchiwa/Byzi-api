package com.byzi.api.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Levee aussi bien quand la ressource n'existe pas que quand elle appartient a un autre
 * utilisateur : cote reponse HTTP, les deux cas rendent un 404 identique (jamais un 403),
 * pour ne pas confirmer a un attaquant qu'un ID existe mais appartient a quelqu'un d'autre
 * (OWASP A01 - eviter l'enumeration de ressources).
 * <p>
 * L'annotation @ResponseStatus n'est pas redondante avec {@link GlobalExceptionHandler}, qui
 * traite deja cette exception : celui-ci ne couvre que les @RestController. Le back-office est
 * fait de @Controller Thymeleaf, et sans cette annotation un compte introuvable s'y affichait
 * en erreur 500 - une panne, la ou il ne s'agit que d'une URL obsolete dans un signet.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
