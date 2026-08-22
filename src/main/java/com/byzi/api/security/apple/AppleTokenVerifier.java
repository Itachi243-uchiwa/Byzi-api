package com.byzi.api.security.apple;

import com.byzi.api.exception.InvalidAppleTokenException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.MalformedURLException;
import java.net.URI;
import java.util.Set;

/**
 * Verifie cryptographiquement un identity token Apple (JWS RS256) avant de lui faire
 * confiance, conformement aux recommandations Apple ("Verifying a User") :
 * <ol>
 *   <li>La signature doit correspondre a une cle publique publiee par Apple (JWKS distant,
 *       recupere et mis en cache automatiquement par {@link RemoteJWKSet})</li>
 *   <li>Le claim "iss" doit valoir https://appleid.apple.com</li>
 *   <li>Le claim "aud" doit correspondre exactement au bundle ID de l'app Byzi</li>
 *   <li>Le token ne doit pas etre expire</li>
 * </ol>
 * On ne fait JAMAIS confiance a un payload JWT non verifie : parser le token sans verifier
 * sa signature (ex. via un simple decodage Base64) serait une faille d'authentification
 * critique (OWASP A02 - Broken Authentication / A08 - Software and Data Integrity Failures).
 */
@Slf4j
@Component
public class AppleTokenVerifier {

    private final ConfigurableJWTProcessor<SecurityContext> jwtProcessor;
    private final AppleAuthProperties properties;

    public AppleTokenVerifier(AppleAuthProperties properties) {
        this.properties = properties;
        try {
            JWKSource<SecurityContext> keySource = new RemoteJWKSet<>(URI.create(properties.jwksUrl()).toURL());
            JWSKeySelector<SecurityContext> keySelector =
                    new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keySource);

            DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
            processor.setJWSKeySelector(keySelector);
            processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                    new JWTClaimsSet.Builder()
                            .issuer(properties.issuer())
                            .audience(properties.audience())
                            .build(),
                    Set.of("sub", "exp", "iat")
            ));
            this.jwtProcessor = processor;
        } catch (MalformedURLException | IllegalArgumentException e) {
            // IllegalArgumentException est levee par URI.toURL() quand l'URI n'est pas absolue
            // (ex. valeur de configuration oubliee ou tronquee). Sans ce catch, l'application
            // echouait au demarrage sur un "URI is not absolute" qui ne dit pas quelle
            // propriete corriger.
            throw new IllegalStateException(
                    "byzi.security.apple.jwks-url invalide : " + properties.jwksUrl(), e);
        }
    }

    public AppleIdTokenClaims verify(String identityToken) {
        if (identityToken == null || identityToken.isBlank()) {
            throw new InvalidAppleTokenException("Identity token Apple manquant");
        }
        try {
            JWTClaimsSet claims = jwtProcessor.process(identityToken, null);
            String subject = claims.getSubject();
            if (subject == null || subject.isBlank()) {
                throw new InvalidAppleTokenException("Token Apple sans claim 'sub'");
            }
            String email = claims.getStringClaim("email");
            return new AppleIdTokenClaims(subject, email);
        } catch (InvalidAppleTokenException e) {
            throw e;
        } catch (Exception e) {
            // On journalise le detail technique cote serveur uniquement (jamais renvoye au client).
            log.warn("Echec de validation d'un identity token Apple : {}", e.getMessage());
            throw new InvalidAppleTokenException("Identity token Apple invalide", e);
        }
    }
}