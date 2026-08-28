package com.byzi.api.security.apple;

import com.byzi.api.exception.InvalidAppleTokenException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
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
import java.time.Duration;
import java.util.Set;

/**
 * Verifie cryptographiquement un identity token Apple (JWS RS256) avant de lui faire
 * confiance, conformement aux recommandations Apple ("Verifying a User") :
 * <ol>
 *   <li>La signature doit correspondre a une cle publique publiee par Apple (JWKS distant,
 *       recupere et mis en cache automatiquement via {@link JWKSourceBuilder})</li>
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

    /** Duree de vie du cache JWKS : les cles RS256 d'Apple tournent rarement. */
    private static final long JWKS_CACHE_TIME_TO_LIVE_MILLIS = Duration.ofHours(24).toMillis();

    /**
     * Rafraichissement anticipe : le cache est renouvele un peu avant son expiration plutot
     * que d'attendre qu'une connexion Apple tombe sur un cache perime et paie un aller-retour
     * reseau synchrone au pire moment.
     */
    private static final long JWKS_REFRESH_AHEAD_MILLIS = Duration.ofHours(1).toMillis();

    /** Empeche un pic d'echecs de signature de declencher une rafale d'appels au JWKS Apple. */
    private static final long JWKS_RATE_LIMIT_MIN_INTERVAL_MILLIS = Duration.ofSeconds(30).toMillis();

    private final ConfigurableJWTProcessor<SecurityContext> jwtProcessor;
    private final AppleAuthProperties properties;

    public AppleTokenVerifier(AppleAuthProperties properties) {
        this.properties = properties;
        try {
            // RemoteJWKSet (deprecie) n'offre ni cache maitrise, ni limitation du rafraichissement,
            // ni tolerance de panne : une indisponibilite du JWKS d'Apple au mauvais moment
            // faisait echouer TOUTES les authentifications et pouvait accumuler les threads
            // Tomcat en attente (pas de timeout HTTP explicite). JWKSourceBuilder cree en
            // interne un DefaultResourceRetriever avec des timeouts HTTP par defaut (connect et
            // read a 500 ms), et outageTolerant sert le dernier JWKS connu si Apple est en panne.
            JWKSource<SecurityContext> keySource = JWKSourceBuilder
                    .<SecurityContext>create(URI.create(properties.jwksUrl()).toURL())
                    .cache(JWKS_CACHE_TIME_TO_LIVE_MILLIS, JWKSourceBuilder.DEFAULT_CACHE_REFRESH_TIMEOUT)
                    .refreshAheadCache(JWKS_REFRESH_AHEAD_MILLIS, false)
                    .rateLimited(JWKS_RATE_LIMIT_MIN_INTERVAL_MILLIS)
                    .retrying(true)
                    .outageTolerant(true)
                    .build();
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
            boolean emailVerified = parseEmailVerified(claims.getClaim("email_verified"));
            String nonce = claims.getStringClaim("nonce");
            return new AppleIdTokenClaims(subject, email, emailVerified, nonce);
        } catch (InvalidAppleTokenException e) {
            throw e;
        } catch (Exception e) {
            // On journalise le detail technique cote serveur uniquement (jamais renvoye au client).
            log.warn("Echec de validation d'un identity token Apple : {}", e.getMessage());
            throw new InvalidAppleTokenException("Identity token Apple invalide", e);
        }
    }

    /**
     * Apple encode "email_verified" tantot en booleen JSON natif, tantot en chaine "true" /
     * "false" selon le flux d'emission du token : ne lire que le type booleen ferait passer a
     * tort une chaine "true" pour un email non verifie, et bloquerait alors a tort une mise a
     * jour d'email pourtant legitime (cf. AuthService.touchLastLogin).
     * <p>
     * Visibilite package-private (et non private) pour rester testable directement : le chemin
     * nominal de verify() exige une signature JWKS reelle et ne peut pas etre exerce hors ligne
     * (cf. AppleTokenVerifierTest).
     */
    boolean parseEmailVerified(Object claim) {
        if (claim instanceof Boolean bool) {
            return bool;
        }
        if (claim instanceof String str) {
            return Boolean.parseBoolean(str);
        }
        return false;
    }
}
