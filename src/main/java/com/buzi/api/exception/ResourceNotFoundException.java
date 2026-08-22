package com.buzi.api.exception;

/**
 * Levee aussi bien quand la ressource n'existe pas que quand elle appartient a un autre
 * utilisateur : cote reponse HTTP, les deux cas rendent un 404 identique (jamais un 403),
 * pour ne pas confirmer a un attaquant qu'un ID existe mais appartient a quelqu'un d'autre
 * (OWASP A01 - eviter l'enumeration de ressources).
 */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}