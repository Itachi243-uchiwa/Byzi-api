# Byzi — Budget de lancement

**Hypothèses** : tu codes seul (backend Spring Boot déjà écrit + frontend Swift à venir), objectif **500 abonnés sur les 3 premiers mois**, priorité au coût minimum réel.

> ⚠️ **Les prix des hébergeurs et d'Apple bougent.** Tous les montants ci-dessous sont des ordres de grandeur à revérifier sur les sites officiels au moment de payer. Ils sont exprimés TTC en euros, contexte Belgique.

---

## 1. Résumé en trois chiffres

| | Montant |
|---|---|
| **Coût récurrent d'exploitation** | **≈ 6 à 8 €/mois** |
| **Coût total de la 1re année** (infra + Apple + domaine) | **≈ 190 à 230 €** |
| **Revenu net estimé à 500 abonnés** | **≈ 1 750 €/mois** |

**La conclusion importante : l'infrastructure ne sera jamais ton problème.** À 500 abonnés, le serveur coûte moins de 0,5 % du chiffre d'affaires. Les vrais coûts du lancement sont (1) ton temps, (2) les frais administratifs non-IT. Ne perds pas d'énergie à optimiser 3 € de serveur.

---

## 2. Coûts obligatoires

Ce à quoi tu ne peux pas échapper pour publier sur l'App Store.

| Poste | Coût | Fréquence | Commentaire |
|---|---|---|---|
| **Apple Developer Program** | ~99 € | par an | Obligatoire. À payer **maintenant** : l'entitlement `com.apple.developer.family-controls` (story 00.1) demande une validation Apple de ~15 jours et bloque tout l'EPIC-03. |
| **Nom de domaine** | ~15 € | par an | Un `.app` force le HTTPS (préchargé HSTS) — cohérent avec ta config de sécurité. Un `.com`/`.be` coûte ~10 €. |
| **Serveur (VPS)** | ~4 à 5 € | par mois | Voir §3. |
| **Sauvegardes** | 0 à 1 € | par mois | `pg_dump` chiffré vers un stockage objet = quasi gratuit. Option backup de l'hébergeur = +20 % du prix du VPS. |
| **Certificat TLS** | 0 € | — | Let's Encrypt via Caddy, automatique. |

**Sous-total obligatoire année 1 : ~190 €** (99 + 15 + 60 + 12).

---

## 3. Choix de l'hébergeur

### Le point technique qui décide

Ton backend est une **JVM Spring Boot**. Ça change tout par rapport à du PHP ou du Node :

- **RAM** : compte **400 à 700 Mo** en fonctionnement réel (heap + metaspace + threads + pool Hikari). Ton `Dockerfile` fixe déjà `-XX:MaxRAMPercentage=75.0`, donc la JVM s'adapte à ce que tu lui donnes — mais en dessous de **1 Go alloué**, tu vas au-devant d'OOM kills sous charge.
- **Démarrage** : 15 à 40 secondes. Toute plateforme qui met l'app en veille (« scale to zero ») inflige ce délai au premier utilisateur qui ouvre l'app. Rédhibitoire.
- **Processus persistant** : il faut un process qui tourne 24/7, pas un modèle requête/réponse.

### Comparatif

| Hébergeur | Offre | Prix ~ | Verdict |
|---|---|---|---|
| **Hetzner Cloud** | **CAX11** — ARM, 2 vCPU, 4 Go RAM, 40 Go SSD (Allemagne/Finlande) | **~4 €/mois** | ✅ **Recommandé.** Meilleur rapport RAM/prix du marché, datacenters UE (RGPD). Ton image `eclipse-temurin:21-jre-alpine` existe en ARM64. |
| Hetzner Cloud | CX22 — x86, 2 vCPU, 4 Go | ~4,5 €/mois | ✅ Si tu préfères rester en x86 (zéro surprise). |
| **AlwaysData** | Offre partagée (10-20 Go) | ~5 à 9 €/mois | ⚠️ **À éviter pour ce projet.** Excellent hébergeur, mais l'hébergement mutualisé alloue une RAM contrainte, mal adaptée à une JVM persistante. Leur offre VPS revient plus cher que Hetzner. Tu paierais plus pour moins. |
| OVH | VPS entrée de gamme (2 vCPU, 4 Go) | ~6 à 8 €/mois | ✅ Alternative correcte si tu veux du FR. |
| Scaleway | DEV1-S / Stardust | ~4 à 9 €/mois | ✅ Alternative FR. |
| Infomaniak | VPS Lite | ~6 €/mois | ✅ Suisse, positionnement vie privée fort. Argument marketing pour Byzi. |
| Railway / Render / Fly.io | Hobby | 5 à 20 €/mois | ⚠️ Très pratique (git push = déploiement), mais la veille automatique + le prix qui grimpe vite en font un mauvais choix à 500 abonnés payants. Bien pour un prototype. |
| Neon / Supabase (Postgres managé) | Free tier | 0 € | ⚠️ **Inutile ici.** Ton `docker-compose.yml` fait déjà tourner Postgres à côté de l'API. À 500 utilisateurs, un Postgres managé séparé n'apporte rien et ajoute de la latence réseau. |

### Architecture recommandée : un seul VPS

```
                 Internet (HTTPS)
                        │
                   ┌────▼─────┐
                   │  Caddy   │  TLS auto (Let's Encrypt)
                   │  :443    │  reverse proxy
                   └────┬─────┘
                        │  http://api:8080
              ┌─────────▼──────────┐
              │  byzi-api (JVM)    │  ton Dockerfile actuel
              │  ~600 Mo RAM       │
              └─────────┬──────────┘
                        │
              ┌─────────▼──────────┐
              │  postgres:17       │  volume nommé
              │  ~200 Mo RAM       │
              └────────────────────┘
                        │
                  cron nocturne
                  pg_dump chiffré → Backblaze B2 (~0,05 €/mois)
```

C'est **exactement ton `docker-compose.yml` actuel**, plus un service Caddy et un cron de backup. Le travail de déploiement se compte en heures, pas en jours.

---

## 4. Est-ce que 4 Go tiennent 500 abonnés ?

Largement. Le calcul :

**Charge réseau.** Une app de focus synchronise peu : disons 20 requêtes/utilisateur/jour (login, sessions, streaks, règles).
`500 × 20 = 10 000 requêtes/jour ≈ 0,12 requête/seconde`.
Un Spring Boot sur 2 vCPU encaisse **plusieurs centaines** de requêtes/seconde sur ce type d'endpoints. Tu utilises ~0,1 % de la capacité.

**Stockage.** Le poste dominant est `app_block_rules.selection_data` — un blob base64 plafonné à **200 000 caractères** par ta validation.

| Table | Volume à 500 utilisateurs | Taille |
|---|---|---|
| `users` | 500 lignes | négligeable |
| `focus_sessions` | 500 × ~150 sessions/3 mois | ~15 Mo |
| `streak_records` | 500 × 90 jours | ~5 Mo |
| `app_block_rules` | 500 × 3 règles × blob réel (~2-5 Ko) | ~7 Mo |
| `refresh_tokens` | ⚠️ **voir ci-dessous** | ~1,5 Go/mois si non purgé |

⚠️ **Le seul vrai risque de volumétrie, c'est `refresh_tokens`.** Ton access token dure 15 minutes et chaque rafraîchissement crée une nouvelle ligne sans jamais supprimer l'ancienne (TTL 30 jours, aucune purge). Un utilisateur actif produit ~96 rotations/jour :
`500 × 96 × 30 = 1,44 million de lignes par mois`, qui s'accumulent indéfiniment.
**C'est le seul poste qui peut faire exploser ton disque de 40 Go.** Le correctif (un job de purge nocturne) est détaillé dans `AUDIT-BACKEND.md` — c'est une trentaine de lignes.

Avec la purge en place : **moins de 1 Go de base après 3 mois.** Les 40 Go du CAX11 te tiennent des années.

**Seuils de bascule** (à quel moment payer plus) :

| Signal | Action | Coût |
|---|---|---|
| > 5 000 abonnés, ou CPU > 60 % soutenu | VPS 8 Go (Hetzner CAX21) | ~7 €/mois |
| Tu passes à 2 instances (haute dispo) | Redis pour le rate limiting *(ton filtre actuel est en mémoire locale, il ne fonctionnera plus)* | +5 €/mois |
| Base > 20 Go, ou besoin de PITR | Postgres managé | 15 à 25 €/mois |

Aucun de ces seuils n'est atteignable avec 500 abonnés.

---

## 5. Outils gratuits (à mettre en place dès le départ)

Tous ont un palier gratuit qui couvre ton volume. **Zéro euro.**

| Besoin | Outil | Palier gratuit |
|---|---|---|
| CI/CD (build, tests, déploiement) | **GitHub Actions** | 2 000 min/mois sur dépôt privé — tu en consommeras ~200 |
| Suivi des erreurs | **Sentry** | 5 000 erreurs/mois |
| Surveillance de disponibilité | **UptimeRobot** ou Better Stack | check toutes les 5 min sur `/actuator/health` (déjà exposé) |
| Alerte CVE sur les dépendances | **Dependabot** (GitHub) | gratuit — à activer, le pom a le plugin OWASP en commentaire |
| Page de politique de confidentialité | **GitHub Pages** | gratuit — obligatoire pour l'App Store |
| Notifications push (V1) | **APNs** | gratuit, inclus dans le compte développeur |
| Emails transactionnels | Aucun besoin au MVP | Sign in with Apple ne demande aucun envoi d'email |

---

## 6. RevenueCat : le seul coût qui suit ta croissance

RevenueCat est gratuit jusqu'à **2 500 $ de MTR** (revenu mensuel suivi), puis facture **~1 % du MTR**.

À 500 abonnés à 4,99 €/mois : `2 495 € ≈ 2 700 $` → **tu franchis le seuil précisément au moment où tu atteins ton objectif.**

**Budget à prévoir : ~25 €/mois à partir du 3ᵉ mois.** À comparer à un développement StoreKit 2 en direct : RevenueCat gère les webhooks, la restauration d'achat, les changements de plan et les remboursements — plusieurs semaines de travail pour toi seul. Ça les vaut largement.

---

## 7. Scénario 500 abonnés : ce que tu touches réellement

Hypothèse de prix : **4,99 €/mois** (aucun prix n'est fixé dans le backlog — à trancher).

Le calcul que la plupart des gens ratent : **Apple est vendeur officiel en Europe.** Il encaisse la TVA (21 % en Belgique), la reverse, puis prend sa commission sur le montant **hors taxes**.

```
Prix affiché (TTC)                      4,99 €
− TVA belge 21 %                       −0,87 €
─────────────────────────────────────────────
Prix hors taxes                         4,12 €
− Commission Apple 15 %                −0,62 €    ← Small Business Program
─────────────────────────────────────────────
Net par abonné                          3,50 €
```

> Les **15 %** (au lieu de 30 %) supposent ton inscription au **Apple Small Business Program** — ouvert sous 1 M$ de revenus annuels. **L'inscription n'est pas automatique, il faut la demander.** Ne l'oublie pas : c'est 100 % de marge en plus.

| | Mensuel |
|---|---|
| Revenu brut (500 × 4,99 €) | 2 495 € |
| **Net encaissé (500 × 3,50 €)** | **1 750 €** |
| − Serveur + sauvegardes | −6 € |
| − RevenueCat (~1 % MTR) | −25 € |
| **Marge nette avant impôts et cotisations** | **≈ 1 719 €** |

**Ratio : les coûts techniques représentent 1,8 % du revenu net.**

Point de rentabilité de l'infrastructure : **2 abonnés** couvrent le serveur. **30 abonnés** couvrent l'année entière (Apple + domaine + serveur).

---

## 8. Coûts non-IT — Belgique

> ⚠️ **Je ne suis pas comptable et ces règles changent. À confirmer impérativement auprès d'un guichet d'entreprises et d'un comptable avant ton premier versement Apple.** Je les mentionne parce qu'ils sont plus élevés que ton budget serveur et que beaucoup de développeurs les découvrent trop tard.

Pour qu'Apple puisse te verser des revenus, il te faut un statut légal et un numéro d'entreprise.

| Poste | Ordre de grandeur | Note |
|---|---|---|
| Inscription à la BCE (guichet d'entreprises) | ~105 € une fois | Obligatoire pour obtenir un n° d'entreprise |
| Statut **étudiant-indépendant** | Cotisations sociales réduites, voire nulles sous un seuil de revenu net annuel | Le statut est fait pour ton cas. Le seuil et les taux se vérifient chaque année. |
| Comptable | 0 à 800 €/an | Faisable seul au début en régime simplifié |
| Activation TVA | variable | Apple gère la TVA sur les ventes App Store en UE, mais ton statut peut quand même exiger une immatriculation |

**À faire avant la soumission App Store, pas après.** Apple demande tes informations bancaires et fiscales pour libérer les paiements — sans numéro d'entreprise, l'argent reste bloqué chez eux.

---

## 9. Récapitulatif chiffré — année 1

**Hypothèse : 0 abonné les 2 premiers mois, montée progressive à 500 au mois 3, stable ensuite.**

| Poste | Mois 1-2 | Mois 3-12 | Total an 1 |
|---|---|---|---|
| Apple Developer Program | 99 € | — | **99 €** |
| Domaine | 15 € | — | **15 €** |
| VPS Hetzner CAX11 | 8 € | 40 € | **48 €** |
| Sauvegardes (B2) | 0 € | 1 € | **1 €** |
| RevenueCat | 0 € | ~250 € | **250 €** |
| CI, Sentry, monitoring | 0 € | 0 € | **0 €** |
| **Total technique** | **122 €** | **291 €** | **≈ 413 €** |
| *Administratif (BCE, à vérifier)* | *~105 €* | — | *~105 €* |

**Sortie de trésorerie technique avant le premier euro de revenu : ~122 €.**
**Coût technique total de la première année : ~413 €**, dont 250 € de RevenueCat qui n'existent que parce que tu gagnes de l'argent.

---

## 10. Plan d'action, dans l'ordre

**Cette semaine — avant de toucher au Swift**
1. Payer le compte Apple Developer et **soumettre l'entitlement FamilyControls** (délai de 15 jours, il bloque l'EPIC-03).
2. Corriger les 3 anomalies bloquantes de `AUDIT-BACKEND.md` (§ Bloquants).
3. Ajouter l'endpoint `GET /api/v1/me` — **sans lui, ton app iOS ne peut pas savoir si l'utilisateur est abonné.** C'est le point le plus urgent : tout ton écran de paywall en dépend.

**Semaines 2-3 — pendant que tu démarres le frontend**
4. Louer le VPS, déployer avec Caddy + Docker Compose.
5. Mettre en place la CI GitHub Actions (build + tests + déploiement).
6. Geler le contrat d'API : exporter `/v3/api-docs` en JSON, le committer, générer le client Swift à partir de ce fichier.

**Avant la beta TestFlight**
7. Job de purge des refresh tokens (sinon ta base gonfle sans fin).
8. Sentry + UptimeRobot.
9. Politique de confidentialité sur GitHub Pages.
10. Statut d'indépendant + numéro d'entreprise.

---

## 11. Le vrai budget

| Ressource | Coût |
|---|---|
| Infrastructure année 1 | ~413 € |
| **Ton temps** | **~5 à 6 mois de développement à temps plein** (estimation du backlog : 305 points MVP, 11-12 sprints en solo) |

Le backlog le dit déjà noir sur blanc : *« avec un seul développeur, ce planning correspond plutôt à 11-12 sprints »*. **C'est ça, ton coût de lancement.** Les 413 € d'infrastructure sont du bruit. La seule décision budgétaire qui compte vraiment est : est-ce que tu réduis le périmètre du MVP (reporter l'EPIC-10 en V1, comme le backlog le suggère) pour lancer plus tôt ?
