package com.byzi.api.dto.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * @param identityToken JWS RS256 emis par Apple, verifie cryptographiquement (cf.
 *                      {@link com.byzi.api.security.apple.AppleTokenVerifier}).
 * @param nonce         nonce ANTI-REJEU brut genere par le client. Le client passe
 *                      {@code SHA-256(nonce)} a la requete Sign in with Apple ; Apple le
 *                      recopie tel quel dans le claim {@code nonce} du token. Le serveur
 *                      re-hashe ce champ et exige l'egalite avec le claim (story 01.2). Peut
 *                      etre nul pour les anciens clients / les tests : la verification n'est
 *                      alors imposee que si le token lui-meme porte un claim {@code nonce}.
 */
public record AppleSignInRequest(
        @NotBlank(message = "IdentityToken est requis")
        String identityToken,

        String nonce
) {
    /** Compatibilite : appels historiques sans nonce (tests unitaires, anciens clients). */
    public AppleSignInRequest(String identityToken) {
        this(identityToken, null);
    }
}
