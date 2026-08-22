# Byzi — Audit du backend

**Périmètre** : `byzi-api` (Spring Boot 4.1, Java 21, PostgreSQL/Flyway) — architecture, sécurité, et ce qu'il faut corriger avant d'attaquer le frontend Swift.
**Date** : 22 août 2026
**Méthode** : lecture complète du code source (`src/main`), des migrations Flyway, de la configuration Spring et de l'infrastructure Docker.

---

## Synthèse

**Ce backend est nettement au-dessus de la moyenne d'un projet étudiant.** Les fondations de sécurité sont correctes et — c'est plus rare — *justifiées* : les commentaires expliquent systématiquement le *pourquoi* d'un choix et le risque qu'il écarte. Plusieurs décisions sont franchement bonnes :

- **Isolation par locataire faite au bon endroit.** Le `userId` vient toujours du JWT via `SecurityUtils`, jamais du corps de la requête, et il est poussé jusque dans la clause `WHERE` (`findByIdAndUser_Id`). Une ressource d'autrui est *invisible*, pas « visible mais refusée ». C'est la bonne façon de traiter l'IDOR.
- **Deux chaînes de sécurité distinctes** (`@Order(1)` admin avec session+CSRF, `@Order(2)` API stateless Bearer sans CSRF) au lieu d'une chaîne unique qui appliquerait à l'une les compromis de l'autre.
- **Refresh tokens** stockés hachés, rotatifs, avec révocation en cascade sur réutilisation d'un token déjà révoqué — le comportement attendu face à un vol de token.
- **Vérification cryptographique complète** de l'identity token Apple (signature JWKS + `iss` + `aud` + expiration), pas un décodage base64 naïf.
- **Idempotence des webhooks** portée par une contrainte d'unicité en base, et non par un `select if exists` applicatif que deux webhooks concurrents traverseraient.
- **Ordre des événements d'abonnement** vérifié (`isStale`) : un `RENEWAL` retardataire ne peut pas ressusciter un abonnement expiré. Ce détail échappe à la plupart des implémentations.
- **Journal d'audit admin délibérément sans clé étrangère** vers `users`, pour que la trace survive à la suppression du compte concerné.
- **Seuil de couverture JaCoCo à 80 % bloquant**, avec des exclusions honnêtes (DTO, enums, config — pas de gonflage artificiel).

**Ce qui ne va pas** se répartit ainsi :

| Sévérité | Nombre | Nature |
|---|---|---|
| 🔴 Bloquant | 3 | Écrasement de données inter-comptes · rate limiting inopérant en production · aucun log applicatif |
| 🟠 Élevé | 2 | Aucun endpoint pour lire l'abonnement (bloque le frontend) · croissance illimitée des refresh tokens |
| 🟡 Moyen | 6 | Documentation d'API publique en prod · cookie de session · en-têtes de proxy · email Apple · pagination · résilience JWKS |
| 🔵 Faible | 5 | CORS · webhook · qualité de code · nommage · dépendances |

Aucune faille ne permet une prise de contrôle du serveur ni une escalade de privilèges vers le rôle ADMIN. Les problèmes sont des **défauts d'exploitation en production** et **une faille d'isolation entre comptes** — sérieux, mais tous corrigeables en une journée de travail.

---

# 🔴 Bloquants — à corriger avant le frontend

## BLOQ-01 · Écrasement de données entre comptes sur `PUT` (streaks et règles de blocage)

**Fichiers** : `service/StreakRecordService.java:49-53`, `service/AppBlockRuleService.java:47-51`
**Classe** : contrôle d'accès cassé (OWASP A01) — écriture inter-locataires
**Preuve que c'est un oubli** : `FocusSessionService` traite le cas correctement, les deux autres non.

### Le mécanisme

L'identifiant d'une ressource est **choisi par le client** (il vient de SwiftData) pour rendre la synchronisation idempotente. `FocusSessionService` en tire la bonne conséquence :

```java
// FocusSessionService.java — resolveIdForNewSession()
if (focusSessionRepository.existsById(requestedId)) {
    UUID reassigned = UUID.randomUUID();   // l'id est déjà pris par un AUTRE compte
    ...
    return reassigned;
}
```

`StreakRecordService` et `AppBlockRuleService` ne font **pas** cette vérification. Leur logique est :

```java
var existing = repository.findByIdAndUser_Id(id, userId);   // vide si l'id appartient à autrui
...
entity = mapper.toNewEntity(id, owner, request);            // on construit avec CET id
return mapper.toResponse(repository.save(entity));          // save() → merge() → UPDATE
```

`save()` sur une entité dont l'identifiant est déjà en base ne fait pas un `INSERT` qui échouerait : Spring Data appelle `merge()`, qui charge la ligne existante et **l'écrase**.

### Les deux impacts, différents

| Entité | `user_id` | Résultat de l'attaque |
|---|---|---|
| `AppBlockRule` | `updatable = false` | La ligne de la victime est écrasée par le contenu de l'attaquant, mais **reste chez la victime**. → corruption de données silencieuse. |
| `StreakRecord` | **pas** de `updatable = false` (`domain/StreakRecord.java:24`) | Le `UPDATE` réattribue `user_id` à l'attaquant. → **la victime perd purement et simplement la ligne**, qui change de propriétaire. |

### Scénario concret

Un attaquant authentifié envoie `PUT /api/v1/streak-records/{uuid-de-la-victime}` avec un corps valide. Le serveur ne trouve rien pour *son* couple (id, userId), crée une entité neuve portant l'id de la victime, et `save()` transfère la ligne de la victime vers l'attaquant.

**Prérequis** : connaître l'UUID d'une ligne d'autrui. Ces identifiants sont générés côté client et ne sont renvoyés qu'à leur propriétaire — l'exploitation à l'aveugle est donc improbable (deviner un UUIDv4 n'arrive pas). Mais un UUID qui fuit par un log, un export de support, une capture d'écran ou une sauvegarde suffit. Et un simple bug de génération d'UUID côté iOS provoque la même corruption **sans aucun attaquant**.

C'est cette dernière raison qui rend le correctif obligatoire : ce n'est pas seulement une faille, c'est un risque de corruption accidentelle.

### Correction

Deux niveaux, à faire dans l'ordre.

**Immédiat** — appliquer le garde-fou déjà écrit dans `FocusSessionService` :

```java
// StreakRecordService.upsert(), branche "création"
User owner = userRepository.getReferenceById(userId);
UUID safeId = streakRecordRepository.existsById(id) ? UUID.randomUUID() : id;
entity = mapper.toNewEntity(safeId, owner, recordRequest);
```

Idem dans `AppBlockRuleService`. Ajoute `updatable = false` sur le `@JoinColumn` de `StreakRecord` (le commentaire « Correction de la colonne de jointure » ligne 24 suggère que ce champ a déjà été touché sans que la contrainte soit remise).

**De fond** — la vraie cause est qu'un identifiant fourni par le client sert de clé primaire globale. Deux corrections structurelles possibles :

1. **Clé unique composite** `(id_client, user_id)` avec une clé primaire technique côté serveur. Deux utilisateurs peuvent alors présenter le même UUID sans conflit possible — la collision devient structurellement impossible plutôt que rattrapée au vol.
2. Garder la PK client mais ajouter `user_id` à **toutes** les requêtes d'écriture, y compris l'insertion.

L'option 1 est plus propre et supprime la réattribution d'id, qui oblige aujourd'hui le client Swift à gérer le cas « le serveur m'a renvoyé un autre id que celui que j'ai envoyé ». **À trancher maintenant : ce choix conditionne le code de synchronisation Swift que tu vas écrire la semaine prochaine.**

**Tests à ajouter** : un test d'intégration « l'utilisateur B ne peut pas écraser la ressource de A » pour chacune des trois entités. L'AC de la story 01.4 le demande explicitement, et `FocusSessionOwnershipIntegrationTest` existe déjà — il faut ses équivalents streak et block-rule.

---

## BLOQ-02 · Le rate limiting sera inopérant, voire bloquera toute l'application

**Fichier** : `security/RateLimitingFilter.java:71` (clé), `:33` (stockage), `config/SecurityConfig.java` (branchement)
**Classe** : OWASP API4:2023 — consommation de ressources non maîtrisée

Trois problèmes distincts dans le même filtre.

### (a) La clé de comptage sera l'IP du reverse proxy

```java
private String clientKey(HttpServletRequest request) {
    return request.getRemoteAddr();
}
```

Le commentaire assume ce choix (« pas de `X-Forwarded-For` non vérifié, facilement falsifiable ») — le raisonnement est juste. Mais dès que l'application tourne derrière Caddy, nginx ou un load balancer — c'est-à-dire **dès sa mise en production** —, `getRemoteAddr()` renvoie l'IP du proxy pour *tous* les utilisateurs.

Conséquence : **10 tentatives d'authentification par minute pour l'ensemble de tes utilisateurs**, toutes IP confondues. À 500 abonnés, l'authentification tombe en panne aux heures de pointe. Ce n'est pas une faille, c'est une panne de production certaine.

**Correction** — faire confiance au proxy, mais uniquement à lui :

```yaml
# application-prod.yml
server:
  forward-headers-strategy: framework
```

Spring renseigne alors `getRemoteAddr()` depuis `X-Forwarded-For`, et il faut **configurer Caddy/nginx pour écraser cet en-tête** s'il vient du client (sinon la falsification que le commentaire craignait devient réelle). En Caddy, `reverse_proxy` le fait par défaut.

### (b) Le back-office admin n'a aucune protection contre le brute-force

Le filtre est branché sur la chaîne API (`@Order(2)`) et ne matche que `/api/v1/auth/`. Or `/admin/login` :

- est dans la chaîne `@Order(1)`, que le filtre ne traverse jamais ;
- valide un **mot de passe BCrypt** — la seule authentification par mot de passe de tout le système ;
- donne accès à la suppression de comptes, la prolongation d'abonnements et les données de tous les utilisateurs.

C'est la cible la plus intéressante de l'application, et c'est la seule sans limitation de débit. **Correction** : ajouter le filtre à la chaîne admin, ou brancher un `AuthenticationFailureHandler` qui verrouille temporairement après N échecs.

### (c) Fuite mémoire lente

```java
private final ConcurrentHashMap<String, Window> windowsByClient = new ConcurrentHashMap<>();
```

Une entrée par IP, **jamais supprimée**. La map grandit indéfiniment avec le nombre d'IP distinctes. Un attaquant qui fait varier son IP source la fait gonfler jusqu'à l'`OutOfMemoryError`.

**Correction** — remplacer par un cache à expiration :

```xml
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
</dependency>
```

```java
private final Cache<String, Window> windows = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(2))
        .maximumSize(100_000)
        .build();
```

**Note pour plus tard** : le commentaire du fichier signale déjà, à raison, que ce compteur en mémoire ne fonctionne plus en multi-instance. Ce n'est pas un problème à 500 abonnés (une seule instance), mais c'est bien noté pour le jour venu.

---

## BLOQ-03 · Aucun log applicatif n'est jamais écrit

**Fichiers** : `application.yml:36`, `application-dev.yml:7`, `application-prod.yml:8`

```yaml
logging:
  level:
    com.byzi.api: INFO      # ← ce package n'existe pas
```

Le code est dans **`com.buzi.api`** (avec un `u`). La configuration cible **`com.byzi.api`** (avec un `y`). Seul `application-demo.yml:32` a la bonne orthographe.

Comme `logging.level.root` vaut `WARN` en production, **aucun `log.info` ni `log.debug` de ton code n'est écrit**. Ce que tu perds, en production, précisément quand tu en as besoin :

| Log perdu | Pourquoi c'est grave |
|---|---|
| `"Refresh token deja revoque presente… possible vol de token"` (`RefreshTokenService`) | **C'est ton unique signal de détection d'un vol de session.** Il est en `WARN`, donc il passe — mais uniquement grâce à `root: WARN`, pas grâce à ta config. |
| `"Nouveau compte Byzi cree"` (`AuthService`) | Aucune traçabilité des inscriptions |
| `"Abonnement mis a jour"` (`SubscriptionService`) | Impossible de diagnostiquer un litige de facturation |
| `"Compte supprime — conformite RGPD"` (`AccountDeletionService`) | **Aucune preuve d'exécution d'une suppression RGPD.** Problématique en cas de contrôle. |
| `"Id de session deja utilise par un autre compte"` (`FocusSessionService`) | Le signal d'alerte de BLOQ-01 est muet |

**Correction** — remplacer `com.byzi.api` par `com.buzi.api` dans les trois fichiers. Une minute de travail, mais c'est la différence entre exploiter une application et la subir.

**Et surtout** : trancher le nommage. Le produit s'appelle **Byzi**, le `groupId` Maven est `com.byzi`, le dossier est `Buzi`, le package Java est `com.buzi`, et l'`artifactId` est `byzi-api`. C'est exactement ce genre d'incohérence qui a produit ce bug. **Renomme le package en `com.byzi.api` maintenant** — un refactor IntelliJ de 30 secondes tant que le projet ne compte que 80 fichiers, et une bonne partie de tes noms de classes DTO seront copiés dans le client Swift.

---

# 🟠 Élevé

## HAUT-01 · L'app iOS n'a aucun moyen de connaître le statut d'abonnement

**Constat** : il n'existe **aucun endpoint** exposant `subscriptionStatus` ou `subscriptionExpiresAt`. Vérifié : aucune occurrence dans `controller/` ni dans `dto/`, et `AuthResponse` ne contient que `userId`, `accessToken`, `accessTokenExpiresInSeconds`, `refreshToken`.

C'est en contradiction directe avec l'exigence que le code lui-même énonce :

> *« Le serveur est la SEULE source de vérité de l'état d'abonnement : l'app iOS ne fait que le lire. »*
> — `SubscriptionService`, javadoc

Le serveur reçoit bien les webhooks RevenueCat, calcule bien l'état, le stocke bien en base… et **ne le publie nulle part**. L'app iOS ne peut pas le lire. Le back-office est aujourd'hui le seul consommateur de cette donnée.

**Impact direct sur ton planning** : ton paywall, ton verrouillage du mode Deep Focus et ton écran de compte dépendent tous de cette information. Si tu commences le Swift sans cet endpoint, tu vas soit coder en dur un statut factice, soit — bien pire — recalculer l'expiration à partir d'une date locale, ce que l'EPIC-07 interdit explicitement (trivial à contourner en reculant l'horloge de l'iPhone).

**Correction** — l'endpoint manquant :

```java
@Tag(name = "Account")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    @GetMapping
    public ResponseEntity<MeResponse> me() {
        return ResponseEntity.ok(userService.currentProfile(SecurityUtils.currentUserId()));
    }
}

public record MeResponse(
        UUID userId,
        String email,
        SubscriptionStatus subscriptionStatus,
        Instant subscriptionExpiresAt,
        boolean hasActiveAccess          // calculé serveur : le client ne compare aucune date
) {}
```

Le booléen `hasActiveAccess` compte : il évite que le client Swift ait à comparer `subscriptionExpiresAt` à `Date()` — c'est-à-dire à faire exactement ce que l'EPIC-07 cherche à empêcher. **Le serveur donne un verdict, pas une date à interpréter.**

**C'est la chose la plus urgente de tout cet audit** au vu de ton planning.

## HAUT-02 · Croissance illimitée de la table `refresh_tokens`

**Fichier** : `security/jwt/RefreshTokenService.java` — aucune suppression nulle part

Chaque `rotate()` marque l'ancien token révoqué (`setRevoked(true)`) et en insère un nouveau. **Aucune ligne n'est jamais supprimée**, et il n'existe aucun job de nettoyage.

Avec un access token de 15 minutes, un utilisateur actif déclenche ~96 rotations par jour :

```
500 utilisateurs × 96 rotations × 30 jours ≈ 1,44 million de lignes par mois, cumulatives
```

Deux conséquences :
- **Disque** : c'est le seul poste capable de saturer les 40 Go de ton VPS (cf. `LANCEMENT-BUDGET.md` §4).
- **Sécurité** : conserver indéfiniment des tokens révoqués et expirés est contraire à la minimisation des données (RGPD) et allonge inutilement la surface exposée par une fuite de base.

**Correction** :

```java
@Component
@RequiredArgsConstructor
public class RefreshTokenCleanupJob {

    private final RefreshTokenRepository repository;

    /**
     * Purge nocturne. On conserve 7 jours après expiration/révocation : assez pour
     * enquêter sur une réutilisation suspecte, pas assez pour que la table dérive.
     */
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void purge() {
        Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);
        long removed = repository.deleteExpiredOrRevokedBefore(cutoff);
        log.info("Purge des refresh tokens : {} lignes supprimees", removed);
    }
}
```

Ajouter `@EnableScheduling` sur une classe de configuration, et l'index correspondant :

```sql
-- V4__refresh_token_cleanup.sql
create index idx_refresh_tokens_expires_at on refresh_tokens (expires_at);
```

---

# 🟡 Moyen

## MOY-01 · La documentation complète de l'API reste publique en production

**Fichiers** : `application-prod.yml`, `config/SecurityConfig.java:168`

La production désactive bien l'interface Swagger :

```yaml
springdoc:
  swagger-ui:
    enabled: ${SWAGGER_UI_ENABLED:false}
```

Mais **`springdoc.api-docs` n'est pas désactivé**. Or c'est `/v3/api-docs` qui contient la spécification OpenAPI complète — tous les chemins, tous les schémas de DTO, toutes les contraintes de validation — et la chaîne de sécurité le laisse en `permitAll()`. Désactiver l'interface graphique sans désactiver la source revient à fermer la porte en laissant la fenêtre ouverte.

Ce n'est pas une faille en soi (une API bien protégée peut être documentée publiquement), mais c'est une cartographie offerte à un attaquant, et sûrement pas l'intention derrière `SWAGGER_UI_ENABLED: false`.

**Correction** :

```yaml
# application-prod.yml
springdoc:
  api-docs:
    enabled: ${API_DOCS_ENABLED:false}
  swagger-ui:
    enabled: ${SWAGGER_UI_ENABLED:false}
```

> 💡 Garde-le **activé en préproduction** : c'est de là que tu généreras ton client Swift.

## MOY-02 · Cookie de session admin sans `Secure` ni `SameSite`

**Fichier** : aucune configuration `server.servlet.session.cookie.*`

La chaîne admin utilise une session par cookie. Sans configuration explicite, `JSESSIONID` part sans l'attribut `Secure` (il peut donc être émis en clair sur HTTP) et avec le `SameSite` par défaut du conteneur.

**Correction** :

```yaml
# application-prod.yml
server:
  servlet:
    session:
      cookie:
        secure: true
        http-only: true
        same-site: strict
      timeout: 30m
```

`same-site: strict` ajoute une deuxième barrière derrière le CSRF déjà activé sur cette chaîne — défense en profondeur, cohérent avec le reste du code.

## MOY-03 · En-têtes de proxy non traités → HTTPS mal détecté

**Fichier** : aucune configuration `server.forward-headers-strategy`

Derrière un reverse proxy qui termine le TLS, Tomcat voit des requêtes HTTP. Conséquences : les redirections générées par Spring Security (`defaultSuccessUrl`, `failureUrl`, `logoutSuccessUrl`) partent en `http://`, et les URL absolues de Swagger sont fausses.

**Correction** : `server.forward-headers-strategy: framework` — la même ligne que celle qui répare BLOQ-02(a).

## MOY-04 · L'email Apple est écrit sans vérification, sur une colonne unique

**Fichiers** : `service/AuthService.java:55-60`, `V3__admin_backoffice.sql`

```java
private User touchLastLogin(User existing, AppleIdTokenClaims claims) {
    existing.setLastLoginAt(Instant.now());
    if (claims.email() != null && !claims.email().isBlank()) {
        existing.setEmail(claims.email());     // écrit à CHAQUE connexion
    }
    return userRepository.save(existing);
}
```

Trois remarques :

1. **`email_verified` n'est pas lu.** `AppleIdTokenClaims` ne contient que `subject` et `email`. Apple fournit un claim `email_verified` ; un email non vérifié ne devrait pas être conservé comme donnée de contact fiable. *(Cela dit, l'email n'est jamais utilisé pour authentifier un compte USER — l'identité repose sur `appleSub`, ce qui est correct. L'impact reste donc limité à la fiabilité de la donnée.)*
2. **`users.email` porte une contrainte d'unicité** (ajoutée en V3 pour la connexion admin). Si deux comptes présentent le même email, `save()` lève une `DataIntegrityViolationException` → **HTTP 409 sur une connexion parfaitement légitime**, sans message exploitable pour l'utilisateur. Le cas est rare mais réel (relais d'email privé Apple, compte recréé, email réattribué).
3. Écrire en base à chaque connexion alors que l'email change quasiment jamais génère un `UPDATE` inutile.

**Correction** :

```java
if (claims.email() != null && !claims.email().isBlank()
        && !claims.email().equals(existing.getEmail())) {
    userRepository.findByEmail(claims.email())
            .filter(other -> !other.getId().equals(existing.getId()))
            .ifPresentOrElse(
                    conflict -> log.warn("Email Apple deja rattache a un autre compte, non applique"),
                    () -> existing.setEmail(claims.email()));
}
```

Et ajouter `emailVerified` à `AppleIdTokenClaims`.

## MOY-05 · Taille de page non plafonnée

**Fichiers** : les quatre contrôleurs de synchronisation

`@PageableDefault(size = 50)` fixe la valeur *par défaut*, pas le *maximum*. Un client peut demander `?size=2000` (plafond global de Spring Boot) et forcer le chargement de 2 000 `app_block_rules` — dont le champ `selection_data` peut atteindre 200 000 caractères, soit potentiellement **400 Mo sérialisés en une seule réponse**. Un seul appel suffit à faire tomber la JVM.

**Correction** :

```yaml
# application.yml
spring:
  data:
    web:
      pageable:
        max-page-size: 100
```

## MOY-06 · Une panne du JWKS Apple bloque toutes les connexions

**Fichier** : `security/apple/AppleTokenVerifier.java`

```java
JWKSource<SecurityContext> keySource = new RemoteJWKSet<>(URI.create(properties.jwksUrl()).toURL());
```

`RemoteJWKSet` est déprécié dans les versions récentes de Nimbus, au profit de `JWKSourceBuilder`, qui apporte précisément ce qui manque ici : cache à durée maîtrisée, limitation du rafraîchissement, et **repli sur le cache expiré** en cas d'indisponibilité d'Apple.

En l'état, une indisponibilité du endpoint JWKS d'Apple au moment d'un rafraîchissement de cache fait échouer **toutes** les authentifications. Aucun `timeout` HTTP n'est configuré non plus : les threads Tomcat peuvent s'accumuler en attente.

**Correction** :

```java
JWKSource<SecurityContext> keySource = JWKSourceBuilder
        .create(new URL(properties.jwksUrl()))
        .retrying(true)
        .cache(24 * 60 * 60 * 1000L, 5 * 60 * 1000L)
        .rateLimited(30_000L)
        .outageTolerant(true)      // sert le cache expiré si Apple est indisponible
        .build();
```

---

# 🔵 Faible

## FAI-01 · `allowCredentials(true)` en CORS est inutile

**Fichier** : `config/SecurityConfig.java`

L'API est stateless et purement Bearer : aucun cookie n'est jamais envoyé. `allowCredentials(true)` n'a donc aucune utilité et resserre inutilement les contraintes de la liste blanche. Le passer à `false` retire un cran de risque sans rien casser. *(L'app iOS n'est pas soumise au CORS de toute façon — ce réglage ne sert qu'au back-office et à une éventuelle landing page.)*

## FAI-02 · Le webhook accepte le secret brut et n'est pas limité en débit

**Fichier** : `security/webhook/WebhookAuthenticator.java`

La comparaison en temps constant via `MessageDigest.isEqual` est **le bon choix**, et le commentaire l'explique correctement. Deux réserves mineures :

- `MessageDigest.isEqual` est constant-time à longueur égale, mais retourne immédiatement si les longueurs diffèrent : la **longueur** du secret reste observable. Impact négligeable pour un secret long et aléatoire.
- L'endpoint `/api/v1/webhooks/**` est en `permitAll()` et **hors du champ du rate limiter** (qui ne matche que `/api/v1/auth/`). Rien n'empêche de le marteler pour deviner le secret. Étendre le filtre à ce chemin coûte une ligne.

## FAI-03 · `assert` en logique métier

**Fichier** : `service/StreakRecordService.java:39`

```java
if (!shouldApply) {
    assert canonical != null;
    return mapper.toResponse(canonical);
}
```

L'invariant est vrai (`shouldApplyIncoming` renvoie `true` dès que `stored == null`, donc `!shouldApply` implique `canonical != null`), mais **les assertions Java sont désactivées par défaut à l'exécution** : cette ligne ne protège de rien en production et sert seulement à faire taire l'analyse statique. Restructure avec un `Optional` explicite plutôt que de documenter un invariant par une instruction inerte.

## FAI-04 · Le webhook renvoie 409 là où le contrat annonce 200

**Fichier** : `service/subscription/SubscriptionService.java`

La javadoc du contrôleur énonce un contrat clair : *« 401 si le secret ne correspond pas, 200 dans tous les autres cas »*. Mais `applyWebhookEvent` relance la `DataIntegrityViolationException` sur insertion concurrente, ce que `GlobalExceptionHandler` traduit en **409**. RevenueCat rejouera l'événement — sans dommage, puisque l'idempotence le rattrapera, mais cela contredit la raison même pour laquelle le contrat a été fixé. Attraper l'exception et retourner `false` alignerait le code sur sa propre documentation.

## FAI-05 · Incohérences mineures

| Point | Détail |
|---|---|
| `jakarta.transaction.Transactional` dans `RefreshTokenService` | Partout ailleurs c'est `org.springframework.transaction.annotation.Transactional`. Fonctionne, mais la sémantique de rollback diffère (Spring ne gère que les `RuntimeException` par défaut ; la variante Jakarta a ses propres règles). Uniformise sur Spring. |
| Index nommé `Idx_users_apple_sub` | Majuscule initiale, seul index du projet dans ce cas |
| Fautes dans les identifiants publics | `presentedTawToken`, `buildReponse`, `userdId`, `existngByDay`, `@Tag(name = "AUth")`, *« Buzy »*, *« apire »*, *« Echnage »*. Les trois derniers **apparaissent dans le Swagger que tu vas lire pendant tout le développement iOS.** |
| Plugin OWASP dependency-check commenté | `pom.xml` — à activer en CI (voir ci-dessous) |
| Aucun workflow CI | Pas de `.github/workflows/`. La story 00.6 (« CI backend : build, tests, migration Flyway ») n'est pas faite. |

---

# Ce qui manque, au-delà des correctifs

Quatre manques structurels que ni la sécurité ni la qualité ne couvrent, mais qui vont te coûter cher côté Swift.

## MANQUE-01 · Pas de synchronisation delta

Les endpoints de liste sont paginés mais ne proposent aucun filtre temporel. À chaque démarrage, l'app doit repaginer **tout l'historique** pour savoir ce qui a changé.

**À ajouter** : `GET /api/v1/focus-sessions?updatedSince={instant}`. Une ligne dans le repository, un paramètre dans le contrôleur — et le client Swift ne transfère plus que le delta. Fais-le **avant** d'écrire ton `SyncEngine`, pas après.

## MANQUE-02 · Les suppressions ne se propagent pas entre appareils

`DELETE /focus-sessions/{id}` supprime la ligne. Il n'en reste **aucune trace**. Sur un second appareil, la session existe encore en SwiftData ; à la prochaine synchronisation, il enverra un `PUT` qui **la recréera**.

C'est le classique problème des « tombstones ». Deux options :

1. **Suppression logique** : une colonne `deleted_at`, exclue des listes, exposée dans le delta pour que les clients sachent supprimer localement.
2. Une table `deletions (entity_type, entity_id, user_id, deleted_at)` interrogée par le delta.

L'option 1 est plus simple et suffit ici. **Ce choix conditionne ton modèle SwiftData** — tranche-le maintenant.

## MANQUE-03 · Portabilité RGPD

`DELETE /api/v1/account` couvre le droit à l'effacement (et l'App Store Guideline 5.1.1(v)) — c'est bien fait, avec la purge en cascade. Mais le **droit à la portabilité** (art. 20 RGPD) n'a pas d'équivalent : aucun endpoint d'export.

`GET /api/v1/account/export` renvoyant un JSON de toutes les données de l'utilisateur. C'est peu de travail, et c'est un argument marketing cohérent avec le positionnement « Byzi ne voit jamais quelles apps tu bloques » (story 23.2 du backlog).

## MANQUE-04 · Aucune chaîne CI/CD

Pas de `.github/workflows/`. Tu as pourtant tout ce qu'il faut pour l'automatiser : un seuil JaCoCo bloquant à 80 %, des tests d'intégration sur H2, un Dockerfile multi-étapes.

**Workflow minimal** :

```yaml
name: CI
on: [push, pull_request]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '21', distribution: 'temurin', cache: 'maven' }
      - run: ./mvnw -B verify          # tests + seuil de couverture 80 %
      - run: ./mvnw -B org.owasp:dependency-check-maven:check
```

Active **Dependabot** dans la foulée (`.github/dependabot.yml`) : c'est gratuit et c'est ta seule veille CVE.

---

# Plan de correction

## Avant de toucher au Swift (~1 journée)

| # | Correctif | Effort |
|---|---|---|
| 1 | **HAUT-01** — endpoint `GET /api/v1/me` | 1 h |
| 2 | **BLOQ-01** — garde-fou d'upsert + `updatable=false` sur `StreakRecord.user` + tests d'isolation | 2 h |
| 3 | **BLOQ-03** — corriger `com.byzi` → `com.buzi` dans les 3 YAML, et **trancher le nommage du package** | 30 min |
| 4 | **MANQUE-01 / MANQUE-02** — décider du contrat de sync (delta + tombstones) | 2 h |
| 5 | Exporter `/v3/api-docs` en JSON, le committer, générer le client Swift depuis ce fichier | 1 h |

> Les points 1, 4 et 5 sont ceux qui **déterminent le code Swift que tu vas écrire**. Les traiter après coup t'obligerait à réécrire ta couche de synchronisation.

## Avant la mise en production (~1 journée)

| # | Correctif | Effort |
|---|---|---|
| 6 | **BLOQ-02** — `forward-headers-strategy`, Caffeine, rate limit sur `/admin/login` | 2 h |
| 7 | **HAUT-02** — job de purge des refresh tokens + index | 1 h |
| 8 | **MOY-01 → MOY-03** — `api-docs` off, cookie de session, en-têtes de proxy | 30 min |
| 9 | **MANQUE-04** — CI GitHub Actions + Dependabot | 1 h |
| 10 | Déploiement VPS (Caddy + Compose + backup) — cf. `LANCEMENT-BUDGET.md` §3 | 3 h |

## Quand tu auras le temps

| # | Correctif |
|---|---|
| 11 | **MOY-04 / MOY-06** — email Apple, résilience JWKS |
| 12 | **MOY-05** — plafond de pagination |
| 13 | **FAI-01 → FAI-05** — CORS, webhook, qualité, fautes de frappe dans le Swagger |
| 14 | **MANQUE-03** — export RGPD |
| 15 | Clé composite `(id_client, user_id)` — la correction de fond de BLOQ-01 |

---

## Pour finir

Ce backend est **bien conçu**. Les erreurs relevées ne sont pas des erreurs de débutant : ce sont des angles morts d'exploitation — le comportement du rate limiter derrière un proxy, une faute de frappe dans un nom de package, un endpoint qu'on oublie parce que le back-office, lui, accédait déjà à la donnée. On les trouve dans des applications en production depuis des années.

Le vrai risque pour toi n'est aucune de ces failles : c'est **HAUT-01 et MANQUE-01/02**. Ce ne sont pas des bugs, ce sont des décisions de contrat d'API qui ne sont pas encore prises. Prends-les cette semaine, avant la première ligne de Swift — un contrat de synchronisation qu'on change après avoir écrit le client, c'est deux semaines perdues.
