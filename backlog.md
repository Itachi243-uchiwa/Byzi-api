# Byzi — Backlog Produit Complet
### MVP · V1 · V2 — incluant le backend Spring Boot et des fonctionnalités additionnelles proposées

Document interne — usage équipe Byzi (Product / Dev iOS / Dev Backend)
Basé sur le Playbook de marque et la Fiche technique développeur existants.

---

## 0. Principe d'architecture retenu

- **On-device (iOS natif)** : tout ce qu'Apple impose en local reste en local — blocage (ManagedSettings), monitoring (DeviceActivity), sessions de focus, calcul des streaks. Le backend ne peut techniquement pas s'y substituer.
- **Backend Spring Boot** : source de vérité pour tout ce qui dépasse le device — comptes, synchronisation multi-appareils, abonnements (webhooks RevenueCat), back-office admin, et (V2) logique de contrôle parental.
- Le backend **ne bloque jamais** une fonctionnalité MVP : l'app doit fonctionner hors-ligne, le backend n'est qu'un miroir de sauvegarde/consultation.

⚠️ **Point d'attention ressources** : la Fiche technique initiale prévoyait 1 développeur iOS (CTO) sur 10 sprints. L'ajout d'un vrai backend Spring Boot (auth, sync, webhooks, back-office) est un scope supplémentaire réel — soit on allonge le planning MVP (~+3 à 4 sprints), soit il faut un second développeur (backend) en parallèle. Je le signale explicitement dans le planning ci-dessous plutôt que de le cacher dans l'estimation.

**Échelle de points** : Fibonacci (1, 2, 3, 5, 8, 13). 1 pt ≈ quelques heures, 13 pts ≈ un développeur à temps plein toute la durée d'un sprint de 2 semaines sur cette seule story.

---

## 1. Vue d'ensemble des épopées

| Épopée | Version | Domaine | Points |
|---|---|---|---|
| EPIC-00 | MVP | Setup & infrastructure (iOS + backend) | 21 |
| EPIC-01 | MVP | Backend Spring Boot — cœur (auth, entités, sync) | 34 |
| EPIC-02 | MVP | Onboarding & autorisation FamilyControls | 21 |
| EPIC-03 | MVP | Sélection & moteur de blocage | 26 |
| EPIC-04 | MVP | Monitoring & limites de temps | 18 |
| EPIC-05 | MVP | Sessions de focus & Deep Focus | 24 |
| EPIC-06 | MVP | Streaks & temps de focus réel | 16 |
| EPIC-07 | MVP | Paiement (StoreKit 2 + RevenueCat + webhook) | 21 |
| EPIC-08 | MVP | Design system & UI | 13 |
| EPIC-08bis | MVP | **Internationalisation FR / EN / NL** (déplacée depuis V2) | 25 |
| EPIC-09 | MVP | Back-office admin (Spring Boot) | 26 |
| EPIC-10 | MVP+ | **Fonctionnalités additionnelles proposées** | 39 |
| EPIC-11 | MVP | QA & beta TestFlight | 13 |
| EPIC-12 | MVP | Soumission App Store | 8 |
| **Sous-total MVP (avec additions)** | | | **305 pts** |
| EPIC-13 | V1 | Compte obligatoire & sync continue | 18 |
| EPIC-14 | V1 | Historique étendu & statistiques | 21 |
| EPIC-15 | V1 | Design system finalisé | 13 |
| EPIC-16 | V1 | Backend V1 — notifications push, agrégations, monitoring | 24 |
| EPIC-17 | V1 | Back-office V1 — support & actions manuelles | 18 |
| **Sous-total V1** | | | **94 pts** |
| EPIC-18 | V2 | Contrôle parental | 29 |
| EPIC-19 | V2 | Mode vacances | 8 |
| EPIC-20 | V2 | Réduction étudiante | 13 |
| EPIC-21 | V2 (optionnel) | Langues supplémentaires au-delà de FR/EN/NL (FR/EN/NL déplacés en MVP, voir EPIC-08bis) — non comptée dans le total, à activer selon traction | 0* |
| EPIC-22 | V2 | Backend V2 — anti-fraude & conformité | 16 |
| **Sous-total V2** | | | **66 pts** |
| **TOTAL GÉNÉRAL** | | | **465 pts** |

---

## 2. MVP

### EPIC-00 — Setup & infrastructure
**Objectif** : poser les fondations iOS (multi-cibles) et backend avant tout développement fonctionnel.

| # | Story | Pts |
|---|---|---|
| 00.1 | Soumission de l'entitlement `com.apple.developer.family-controls` à Apple (délai ~15j+, à lancer immédiatement) | 2 |
| 00.2 | Création du projet Xcode multi-cibles (App + 3 extensions) avec App Group `group.com.byzi.app` | 5 |
| 00.3 | CI iOS de base (build des 4 cibles, lint) | 3 |
| 00.4 | Initialisation du projet Spring Boot (Spring Boot 3, PostgreSQL, Flyway) | 3 |
| 00.5 | Dockerisation backend + déploiement infra (Railway/Render/Fly.io) | 5 |
| 00.6 | CI backend (build, tests, migration Flyway automatique) | 3 |

**AC clés** : les 4 cibles iOS compilent sans l'entitlement approuvé (mock local prévu, cf. risques) ; le backend répond sur un endpoint `/health` déployé.

---

### EPIC-01 — Backend Spring Boot : cœur
**Objectif** : mettre en place l'API qui remplace le rôle initialement prévu pour Supabase, avec Spring Security + JWT.

| # | Story | Pts |
|---|---|---|
| 01.1 | Modélisation JPA des entités `User`, `FocusSession`, `StreakRecord`, `AppBlockRule` (miroir du schéma SQL existant) | 5 |
| 01.2 | Endpoint `POST /auth/apple` — validation du token Sign in with Apple côté serveur, émission d'un JWT applicatif | 8 |
| 01.3 | Filtre Spring Security JWT (extraction `userId`, protection des routes) | 5 |
| 01.4 | Endpoints CRUD `focus-sessions`, `streak-records`, `app-block-rules` (scopés par `userId` du JWT) | 8 |
| 01.5 | Stratégie de résolution de conflit last-write-wins (`updatedAt`) sur les endpoints de sync | 3 |
| 01.6 | Endpoint `DELETE /account` (suppression RGPD, cf. EPIC-09) | 3 |
| 01.7 | Documentation API (OpenAPI/Swagger) | 2 |

**AC clés** : un utilisateur ne peut jamais lire/écrire les données d'un autre `userId` (test d'intrusion basique) ; `selectionData` (blob des apps bloquées) est stocké tel quel côté serveur, jamais désérialisé/interprété.

---

### EPIC-02 — Onboarding & autorisation
| # | Story | Pts |
|---|---|---|
| 02.1 | Écran de bienvenue (positionnement, sans permission) | 2 |
| 02.2 | Écrans explicatifs pre-permission priming | 3 |
| 02.3 | `AuthorizationService` — demande FamilyControls + gestion `.denied` | 5 |
| 02.4 | Écran de récupération si autorisation refusée (lien vers Réglages) | 2 |
| 02.5 | Observation de la révocation d'autorisation en cours de session (foreground) | 3 |
| 02.6 | Demande de notifications après démonstration de valeur (pas au lancement) | 2 |
| 02.7 | Création de compte optionnelle en fin d'onboarding (Sign in with Apple → `/auth/apple`) | 4 |

---

### EPIC-03 — Sélection & moteur de blocage
| # | Story | Pts |
|---|---|---|
| 03.1 | `AppSelectionView` avec `FamilyActivityPicker` | 3 |
| 03.2 | Persistance `AppBlockRule` en SwiftData (encodage `FamilyActivitySelection`) | 3 |
| 03.3 | `BlockingEngine` — deux `ManagedSettingsStore` nommés (focus / limite) | 5 |
| 03.4 | `ShieldConfigurationExtension` — écran de blocage aux couleurs Byzi | 5 |
| 03.5 | `ShieldActionExtension` — bouton "Retour à Byzi" + "Demander 1 minute" | 5 |
| 03.6 | `OneMinuteGrantService` (friction temporelle, mode standard uniquement) | 5 |

**AC clés** : deux règles de blocage actives simultanément sur la même app ne se marchent pas dessus (fusion native des shields, vérifiée par test device réel).

---

### EPIC-04 — Monitoring & limites de temps
| # | Story | Pts |
|---|---|---|
| 04.1 | `MonitoringService` — `DeviceActivityCenter`, schedule quotidien | 5 |
| 04.2 | `DeviceActivityMonitorExtension` — `eventDidReachThreshold` → application du shield | 5 |
| 04.3 | Notification locale "Limite atteinte" | 2 |
| 04.4 | Reset implicite à `intervalDidStart` (nouvelle journée) | 2 |
| 04.5 | Tests device réel des seuils (non testables en simulateur) | 4 |

---

### EPIC-05 — Sessions de focus & Deep Focus
| # | Story | Pts |
|---|---|---|
| 05.1 | `FocusSessionEngine` — machine à états (`idle` / `running` / `completed`) | 5 |
| 05.2 | Mode standard vs Deep Focus (sortie anticipée refusée en Deep Focus) | 5 |
| 05.3 | Persistance de l'état courant dans l'App Group | 3 |
| 05.4 | Reprise de session après redémarrage de l'app/device | 5 |
| 05.5 | Chips de durée rapide (45/60/90 min + personnalisé) | 3 |
| 05.6 | Notification de fin de session | 1 |
| 05.7 | Tests unitaires machine à états (y compris refus Deep Focus) | 2 |

---

### EPIC-06 — Streaks & temps de focus réel
| # | Story | Pts |
|---|---|---|
| 06.1 | `StreakCalculator` — calcul idempotent, fuseau horaire figé par jour | 5 |
| 06.2 | Agrégation du temps de focus réel par jour (`recordedFocusMinutes`) | 3 |
| 06.3 | Dashboard — affichage streak + temps de focus du jour | 3 |
| 06.4 | Rappel quotidien de streak (notification programmable, ex. 20h) | 2 |
| 06.5 | Tests unitaires : rupture de streak, changement de fuseau horaire | 3 |

---

### EPIC-07 — Paiement
| # | Story | Pts |
|---|---|---|
| 07.1 | Configuration des produits StoreKit 2 (mensuel/annuel, essai 3 jours) | 3 |
| 07.2 | `PurchasesService` (RevenueCat) — achat, restauration, entitlements | 5 |
| 07.3 | Machine à états d'abonnement (`trial`/`active`/`grace_period`/`expired`) | 3 |
| 07.4 | Paywall (StoreKit sheet native ou custom RevenueCat) | 5 |
| 07.5 | **Backend** : webhook RevenueCat → mise à jour `subscription_status` en base | 5 |

**AC clés** : l'état d'abonnement n'est jamais déduit d'une date locale sur l'appareil (anti-contournement horloge système).

---

### EPIC-08 — Design system & UI
| # | Story | Pts |
|---|---|---|
| 08.1 | Palette de couleurs Byzi (`Color+Byzi.swift`) | 1 |
| 08.2 | Intégration police Gilroy (Bold/Regular) | 2 |
| 08.3 | Composants de base (boutons, cartes, badge streak) | 5 |
| 08.4 | Accessibilité — Dynamic Type + contraste WCAG AA sur fond `byziNearBlack` | 5 |

---

### EPIC-08bis — Internationalisation FR / EN / NL
**Objectif** : livrer l'app nativement en français, anglais et néerlandais dès le MVP, plutôt que d'introduire l'anglais plus tard comme prévu initialement dans le Playbook.

⚠️ **Changement de décision à noter** : le Playbook indiquait explicitement *"Documents et communications internes en français pour le moment ; l'anglais sera introduit sur les supports publics (pages produit, store) le moment venu."* Cette story remplace ce choix — il vaut la peine d'en informer la chargée de communication et le graphiste, puisque ça impacte aussi les supports marketing (captures d'écran store, landing page) et pas seulement le code.

Le néerlandais est un choix pertinent vu le marché belge (clients institutionnels côté Wallonie-Bruxelles, mais audience grand public potentiellement bilingue voire flamande) — à confirmer si la cible prioritaire (entrepreneurs/étudiants 18-27 ans) est plutôt FR/NL Belgique ou si l'app vise aussi les Pays-Bas.

| # | Story | Pts |
|---|---|---|
| 08bis.1 | Extraction de tous les textes UI en fichiers de localisation (String Catalog Xcode 15+), zéro string en dur dans le code dès le départ | 5 |
| 08bis.2 | Traduction FR — langue de référence, formalisée en fichier de traduction (déjà rédigée dans le Playbook/onboarding) | 1 |
| 08bis.3 | Traduction EN — UI complète (onboarding, dashboard, paywall, réglages) | 3 |
| 08bis.4 | Traduction NL — UI complète, relecture par un locuteur natif | 3 |
| 08bis.5 | Détection de la langue système au premier lancement + fallback EN si langue non supportée | 2 |
| 08bis.6 | Vérification des formats de date/heure/nombre par locale (`DateFormatter`/`Calendar` déjà locale-aware, à valider explicitement pour les 3 langues, notamment le calcul des streaks qui ne doit jamais dépendre du texte affiché) | 2 |
| 08bis.7 | Traduction des messages de l'écran de blocage (Shield) et des notifications locales dans les 3 langues | 3 |
| 08bis.8 | Traduction des metadata App Store (titre, sous-titre, description, mots-clés, captures d'écran) en FR/EN/NL | 3 |
| 08bis.9 | QA linguistique — relecture native indépendante pour chaque langue (éviter la traduction mot-à-mot, notamment pour le NL) | 3 |

**AC clés** : aucun texte visible par l'utilisateur n'est codé en dur en français dans les vues SwiftUI ; changer la langue du système iOS change immédiatement toute l'app (UI + shield + notifications) sans redémarrage nécessaire.

**Impact planning** : ces 25 points s'ajoutent au MVP dès le sprint du design system (voir section 5) — combinés au backend et aux additions déjà signalées, ça renforce encore la recommandation d'un second développeur ou d'un délai supplémentaire plutôt que de compresser le scope sur un seul développeur.

---

### EPIC-09 — Back-office admin (Spring Boot)
**Objectif** : justification principale du choix Spring Boot — un vrai panneau d'administration pour piloter le lancement.

| # | Story | Pts |
|---|---|---|
| 09.1 | Module `/admin` sécurisé (Spring Security, rôle `ADMIN` distinct des users) | 5 |
| 09.2 | Vue liste des utilisateurs (email, statut abonnement, date d'inscription) | 3 |
| 09.3 | Vue détail utilisateur (sessions, streak courant, historique abonnement) | 3 |
| 09.4 | Dashboard KPI — nombre d'inscrits, taux conversion essai→payant, churn | 5 |
| 09.5 | Action manuelle : prolonger un essai / marquer un remboursement (support) | 3 |
| 09.6 | Endpoint + UI de suppression de compte conforme RGPD (guideline 5.1.1(v)) | 4 |
| 09.7 | Logs d'audit des actions admin (qui a fait quoi, quand) | 3 |

---

### EPIC-10 — Fonctionnalités additionnelles proposées (MVP)

Ces fonctionnalités ne sont **pas** dans le périmètre MVP actuel du Playbook, mais s'appuient sur des mécanismes déjà décrits dans la Fiche technique (Partie XII, section 38) qui ne sont aujourd'hui rattachés à aucune version. Elles renforcent la différenciation face à Opal/One Sec/Freedom sans réintroduire de gamification (le système de récompenses/gemmes reste exclu, conformément au choix déjà acté).

| # | Story | Pourquoi c'est pertinent pour le MVP | Pts |
|---|---|---|---|
| 10.1 | **Widget Home Screen** (streak + temps restant si session active), lecture seule via App Group | Rappel visuel constant sans ouvrir l'app = rétention day-1/day-7 nettement meilleure, quasi gratuit techniquement (le code existe déjà en Partie XII) | 5 |
| 10.2 | **Live Activity** pendant une session (écran verrouillé + Dynamic Island) | Différenciateur direct vs Opal (ne le propose pas selon la fiche technique) ; renforce l'engagement pendant la session elle-même | 5 |
| 10.3 | **App Intent / raccourci Siri** pour démarrer une session standard (bouton d'action iPhone 15 Pro+) | Réduit la friction de lancement d'une session à zéro tap ; le Deep Focus reste volontairement exclu de ce raccourci (sécurité déjà actée) | 3 |
| 10.4 | **Rapport d'activité** via `DeviceActivityReport` (extension Apple dédiée aux insights d'usage, distincte de `DeviceActivityMonitor`) — graphique simple "temps passé sur apps bloquées avant Byzi vs après" | Argument de conversion essai→payant très fort ("regarde ce que tu as gagné") sans être un système de points ; s'appuie sur un framework Apple non encore exploité dans la fiche technique | 8 |
| 10.5 | **Carte de partage du streak** (image générée, style Byzi, à partager sur réseaux sociaux) | Boucle de croissance organique gratuite ; ce n'est pas une récompense in-app mais un export — ne contredit pas l'exclusion de la gamification | 3 |
| 10.6 | **Onboarding personnalisé** — 1 question sur l'objectif (études / entreprenariat / carrière) pour pré-suggérer une sélection d'apps à bloquer par défaut | Réduit la friction du premier `FamilyActivityPicker` (souvent perçu comme fastidieux) et renforce le sentiment "Byzi me comprend" | 5 |
| 10.7 | **Blocage récurrent programmé** (jours de semaine + plage horaire, ex. "bloquer Instagram du lundi au vendredi 9h-18h") | Le modèle `AppBlockRule` a déjà les champs `scheduleStart`/`scheduleEnd` mais aucune story MVP ne les exploite aujourd'hui — cas d'usage très demandé par la cible entrepreneurs/étudiants | 5 |
| 10.8 | **Parrainage** (code de parrainage, +jours d'essai offerts au parrain et au filleul) — géré côté backend (génération de code, validation, extension de l'essai via RevenueCat) | Acquisition à coût quasi nul, cohérent avec la stratégie "acquisition portée par le contenu" déjà définie dans le business plan | 5 |

**Recommandation** : si le calendrier MVP est déjà serré (backend + additions), prioriser dans cet ordre : **10.7 (schedules) > 10.1 (widget) > 10.6 (onboarding personnalisé) > 10.5 (partage) > le reste**, et basculer 10.2/10.3/10.4/10.8 en tout début de V1 si besoin — ce sont les moins critiques au lancement.

---

### EPIC-11 — QA & beta TestFlight
| # | Story | Pts |
|---|---|---|
| 11.1 | Suite de tests unitaires (StreakCalculator, BlockingEngine, FocusSessionEngine, PurchasesService) | 5 |
| 11.2 | Tests unitaires backend (auth, endpoints sync, résolution de conflit) | 3 |
| 11.3 | Tests manuels device réel (blocage, monitoring — non simulables) | 3 |
| 11.4 | Beta fermée interne (5-10 testeurs), checklist de sortie | 2 |

---

### EPIC-12 — Soumission App Store
| # | Story | Pts |
|---|---|---|
| 12.1 | Metadata, captures d'écran, description store | 3 |
| 12.2 | Revue de conformité guidelines Screen Time (2.5.1, 5.1.1, 3.1.1, 4.0, 2.1) | 3 |
| 12.3 | Soumission et suivi de la review | 2 |

---

## 3. V1

### EPIC-13 — Compte obligatoire & sync continue
| # | Story | Pts |
|---|---|---|
| 13.1 | Bascule création de compte optionnelle → obligatoire | 3 |
| 13.2 | `SyncWorker` continu (déclenché au foreground + timer background) | 5 |
| 13.3 | Résolution de conflit testée sur 2 devices réels | 3 |
| 13.4 | Gestion des erreurs réseau / retry avec backoff | 3 |
| 13.5 | Suppression de compte in-app (double confirmation, cf. RGPD) | 4 |

### EPIC-14 — Historique étendu & statistiques
| # | Story | Pts |
|---|---|---|
| 14.1 | Vue historique de sessions (Swift Charts) | 5 |
| 14.2 | Moyenne mobile 7 jours du temps de focus | 3 |
| 14.3 | Comparaison semaine/semaine | 3 |
| 14.4 | **Backend** : endpoints d'agrégation (évite de recalculer côté client à chaque fois) | 5 |
| 14.5 | Export des données personnelles (RGPD, portabilité) | 5 |

### EPIC-15 — Design system finalisé
| # | Story | Pts |
|---|---|---|
| 15.1 | Librairie de composants versionnée avec Previews Xcode | 8 |
| 15.2 | Badges de streak (variantes visuelles selon la longueur du streak) | 5 |

### EPIC-16 — Backend V1
| # | Story | Pts |
|---|---|---|
| 16.1 | Notifications push (remplace/complète les notifications locales pour les rappels pilotés serveur) | 8 |
| 16.2 | Rate limiting sur l'API (protection abus) | 3 |
| 16.3 | Monitoring/observabilité (logs structurés, alerting, ex. Sentry ou équivalent) | 5 |
| 16.4 | Infrastructure A/B testing paywall (variantes de prix/messages) | 8 |

### EPIC-17 — Back-office V1
| # | Story | Pts |
|---|---|---|
| 17.1 | File de tickets support liée aux comptes utilisateurs | 5 |
| 17.2 | Recherche utilisateur multi-critères (email, statut, date) | 3 |
| 17.3 | Export CSV des métriques pour reporting externe | 3 |
| 17.4 | Gestion fine des rôles admin (support vs finance vs full-admin) | 5 |
| 17.5 | Vue "codes de parrainage" (si 10.8 activé) — suivi conversions | 2 |

---

## 4. V2

### EPIC-18 — Contrôle parental
| # | Story | Pts |
|---|---|---|
| 18.1 | **Backend** : table `parental_links` (compte parent ↔ compte(s) enfant) | 5 |
| 18.2 | Flux d'invitation parent → enfant (lien/code) | 5 |
| 18.3 | Validation des modifications de `AppBlockRule` par le parent (notification push) | 8 |
| 18.4 | Intégration provider push (hors périmètre MVP/V1) | 5 |
| 18.5 | Écran parent : vue consolidée des enfants liés | 3 |
| 18.6 | Restrictions UI côté app enfant (règles verrouillées) | 3 |

### EPIC-19 — Mode vacances
| # | Story | Pts |
|---|---|---|
| 19.1 | Modèle `VacationMode` (SwiftData) + UI de configuration | 3 |
| 19.2 | Suspension de tous les `ManagedSettingsStore` sur la période | 3 |
| 19.3 | Réactivation automatique via `DeviceActivitySchedule` dédié à `endDate` | 2 |

### EPIC-20 — Réduction étudiante
| # | Story | Pts |
|---|---|---|
| 20.1 | Choix d'un provider de vérification étudiante (ex. SheerID ou équivalent) | 3 |
| 20.2 | **Backend** : endpoint de vérification + statut `student_verified` | 5 |
| 20.3 | Prix réduit conditionné dans RevenueCat (offer codes) | 5 |

### EPIC-21 — Internationalisation : langues supplémentaires (optionnel)
FR/EN/NL sont désormais couverts dès le MVP (voir EPIC-08bis). Cette épopée V2 ne garde que l'option d'étendre à d'autres marchés si la traction le justifie après lancement.

| # | Story | Pts |
|---|---|---|
| 21.1 | Étude d'opportunité — quelle(s) langue(s) supplémentaire(s) selon les données d'acquisition réelles (ex. DE, ES) | 2 |
| 21.2 | Traduction UI + metadata store dans la/les langue(s) retenue(s) | 5 |
| 21.3 | QA linguistique native | 3 |

### EPIC-22 — Backend V2 : anti-fraude & conformité
| # | Story | Pts |
|---|---|---|
| 22.1 | Détection d'abus sur le parrainage (multi-comptes, faux emails) | 5 |
| 22.2 | Détection d'abus sur les essais gratuits répétés (device fingerprinting léger) | 5 |
| 22.3 | Revue de conformité RGPD complète (registre de traitement, DPA sous-traitants) | 3 |
| 22.4 | Audit de sécurité de l'API (pentest léger avant scale) | 3 |

---

## 5. Roadmap sprint indicative (sprints de 2 semaines)

| Sprint | Contenu principal |
|---|---|
| 0-1 | EPIC-00, EPIC-01 (backend cœur) — **en parallèle du dev iOS si 2 devs, sinon en amont** |
| 2 | EPIC-02 (onboarding/autorisation) |
| 3 | EPIC-03 (blocage core) |
| 4 | EPIC-04 (monitoring) + début EPIC-10 (10.7 schedules) |
| 5 | EPIC-05 (focus engine) |
| 6 | EPIC-06 (streaks) + EPIC-10 (10.1 widget, 10.6 onboarding perso) |
| 7 | EPIC-07 (paiement + webhook backend) |
| 8 | EPIC-08 (design system) + EPIC-09 (back-office) |
| 9 | EPIC-10 (reste : 10.2/10.3/10.4/10.5/10.8 selon arbitrage) |
| 10 | EPIC-11 (QA/beta) |
| 11 | EPIC-12 (soumission App Store) |

Avec un seul développeur (toi en CTO), ce planning correspond plutôt à **11-12 sprints (~5-6 mois)** plutôt que les 10 sprints initiaux du Playbook, du fait de l'ajout du backend + des fonctionnalités additionnelles. Si l'objectif "lancement fin 2026" reste ferme, l'option la plus réaliste est soit un second développeur sur le backend en parallèle, soit reporter EPIC-10 (additions) intégralement en tout début de V1.

---

## 6. Fonctionnalités différenciantes complémentaires (proposées)

Deuxième vague de propositions, non encore intégrée aux totaux de points ni à la roadmap ci-dessus — à arbitrer et à replacer dans MVP/V1/V2 selon la charge disponible. Toutes respectent les principes déjà actés : pas de gamification/récompenses, ton "discipline sans culpabilisation", confidentialité on-device.

### EPIC-23 — Confiance & sécurité (candidat MVP, effort faible à moyen)

| # | Story | Pourquoi c'est différenciant | Pts |
|---|---|---|---|
| 23.1 | **Intégration native avec les Focus iOS** — démarrer un Deep Focus Byzi active aussi un Focus system iOS (Do Not Disturb dédié). Byzi hérite ainsi gratuitement du filet de sécurité natif d'Apple (appels répétés, contacts favoris qui passent quand même) sans avoir à le réinventer | Répond à l'objection n°1 des apps de blocage strict ("et si on a besoin de me joindre en urgence ?") en s'appuyant sur une API Apple existante — techniquement solide et peu coûteux, aucun concurrent cité (Opal, One Sec, Freedom) ne le fait de façon native à ma connaissance | 5 |
| 23.2 | **Écran de transparence "Byzi ne voit jamais quelles apps tu bloques"** — explique dans l'onboarding le fonctionnement des tokens opaques FamilyControls (déjà vrai techniquement, cf. Fiche technique section 16) | Argument de confiance concret et vérifiable, distingue Byzi des apps qui demandent un accès complet aux données d'usage | 2 |

### EPIC-24 — Contextualisation intelligente (candidat V1/V2, effort moyen à élevé)

| # | Story | Pourquoi c'est différenciant | Pts |
|---|---|---|---|
| 24.1 | **Blocage géolocalisé** (CoreLocation + geofencing) — activer automatiquement une règle de blocage en arrivant à un lieu défini (bibliothèque, bureau) | Automatise la discipline au lieu de compter sur la mémoire de l'utilisateur ; use case fort pour la cible étudiants/entrepreneurs | 8 |
| 24.2 | **Blocage lié au calendrier** (EventKit) — bloquer automatiquement pendant les créneaux marqués "Cours"/"Réunion" | Élimine le besoin de démarrer une session manuellement avant chaque cours/réunion | 8 |
| 24.3 | **Suggestions adaptatives de limites** — coaching progressif ("tu es systématiquement sous ta limite depuis 2 semaines, la resserrer ?"), jamais imposé, toujours proposé | Renforce la perception d'un produit intelligent sans tomber dans la surveillance ou la punition | 5 |

### EPIC-25 — Rituel de session enrichi (candidat MVP/V1, effort faible)

| # | Story | Pourquoi c'est différenciant | Pts |
|---|---|---|---|
| 25.1 | **Intention de session** — courte question avant le lancement ("Sur quoi tu te concentres ?"), note libre optionnelle | Ancre la session dans un objectif concret plutôt qu'un simple minuteur ; alimente aussi l'historique V1 | 3 |
| 25.2 | **Réflexion post-session** — note courte "Qu'as-tu accompli ?" archivée avec la session | Boucle de sens qui renforce la valeur perçue de chaque session, sans notation ni score | 3 |
| 25.3 | **Message d'écran de blocage personnalisé** — reprend le "why" saisi par l'utilisateur à l'onboarding au lieu d'un message générique | Rend l'écran de blocage (point de contact le plus fréquent avec la marque, déjà identifié comme tel dans la Fiche technique section 7) réellement personnel | 3 |
| 25.4 | **Rappel de pause** sur les sessions longues (>60 min) — simple notification bien-être, pas de mécanique de jeu | Différenciation douce, cohérente avec le positionnement "discipline, pas contrainte" | 2 |

### EPIC-26 — Écosystème Apple étendu (candidat V1/V2, effort moyen à élevé)

| # | Story | Pourquoi c'est différenciant | Pts |
|---|---|---|---|
| 26.1 | **App Apple Watch** — démarrer/arrêter une session et complication streak au poignet | Renforce la rétention et la visibilité quotidienne du streak sans dépendre de l'iPhone | 8 |
| 26.2 | **Version iPad** (universal app) — FamilyControls fonctionne aussi sur iPadOS | Élargit l'audience à la cible étudiants qui travaillent souvent sur iPad, coût marginal si l'app est déjà en SwiftUI | 5 |
| 26.3 | **Automations Shortcuts avancées** — exposer plus de déclencheurs/actions (ex. "à l'arrivée au bureau, démarre une session focus") au-delà de l'App Intent simple prévu au MVP | Permet à l'utilisateur de construire ses propres automatisations sans attendre une feature native équivalente | 3 |

### EPIC-27 — Boucle d'engagement respectueuse (candidat MVP+, effort faible)

| # | Story | Pourquoi c'est différenciant | Pts |
|---|---|---|---|
| 27.1 | **Résumé hebdomadaire avec un insight actionnable unique** (pas une liste de chiffres) — ex. "Ton créneau le plus distrait est 22h-23h, veux-tu une règle dessus ?" | Transforme la donnée déjà collectée en recommandation concrète, renforce la perception d'un produit qui "coache" plutôt qu'un simple traqueur | 3 |
| 27.2 | **Nudge de retour intelligent** — notification de réengagement uniquement après un vrai décrochage (plusieurs jours sans session), copy non culpabilisante alignée sur le ton de marque | Évite le piège des notifications agressives qui desservent la marque "discipline sans culpabilisation" tout en limitant le churn silencieux | 2 |

**Total de cette seconde vague : 57 pts** (non inclus dans le total général de la section 1 — à arbitrer avant intégration officielle au backlog).

**Recommandation de priorisation si tu ne devais en retenir que 3 pour le MVP** : **23.1** (Focus iOS natif — fort, techniquement sobre), **25.3** (message de blocage personnalisé — quasi gratuit, renforce le point de contact le plus important déjà identifié dans la Fiche technique), **25.1** (intention de session — très faible effort, forte valeur perçue).

---

## 7. Touches "fantaisistes" / personnalité de marque (proposées)

Troisième vague — des idées plus légères et ludiques, pensées pour donner du caractère à l'app sans transformer Byzi en jeu. ⚠️ Le Playbook pose deux règles strictes qu'il faut garder en tête : **aucun système de récompenses/gemmes**, et **un accent bleu unique, jamais mélangé** avec d'autres couleurs. J'ai marqué chaque idée en conséquence :
- ✅ **Aligné** : compatible tel quel avec la charte actuelle
- ⚠️ **À valider** : séduisant mais frôle une des deux règles ci-dessus, à trancher consciemment avant de l'ajouter au backlog officiel

| # | Story | Statut | Description | Pts |
|---|---|---|---|---|
| 28.1 | Messages d'écran de blocage à l'humour rotatif | ✅ | Un pool de textes taquins (pas culpabilisants) qui remplacent aléatoirement le sous-titre du shield, ex. "Cette app espérait un peu plus de constance de ta part." Pas de mécanique, juste du contenu éditorial | 3 |
| 28.2 | Nom automatique et fun pour chaque session | ✅ | Au lieu de "Session #4", l'app propose un nom généré selon l'heure/le jour ("Le Sprint du mercredi soir", "Opération Deadline") — purement cosmétique, aucune notion de score | 2 |
| 28.3 | "Journal des tentatives" | ✅ | Compteur privé et taquin du nombre de fois où l'utilisateur a rouvert une app bloquée dans la journée ("Instagram a toqué 14 fois à la porte de ton focus aujourd'hui.") — la donnée existe déjà techniquement (callbacks du shield), c'est juste un habillage narratif à ton non-culpabilisant | 3 |
| 28.4 | Identité sonore Byzi | ✅ | Un mini son signature au démarrage et à la fin d'une session (pas de jingle de "victoire", plutôt un son sobre et satisfaisant, cohérent avec le ton "discipline maîtrisée" du mark) | 3 |
| 28.5 | Capsule temporelle | ✅ | Note libre écrite à soi-même, livrée automatiquement 30 ou 90 jours plus tard — ancre directement le "Why" de la marque ("construire ce qui compte") dans une fonctionnalité concrète et émouvante, sans mécanique répétitive | 5 |
| 28.6 | Micro-animation de "déverrouillage" en fin de Deep Focus | ✅ | Petite transition visuelle (le mark du logo, qui évoque déjà un flux/trait continu, s'anime brièvement) au lieu d'un simple fondu — un seul accent bleu utilisé, donc compatible avec la charte | 3 |
| 28.7 | Citation du jour sur le thème de la discipline | ✅ | Contenu 100% original écrit par l'équipe (volontairement **non attribué** à des personnes réelles, pour éviter tout usage de citations dont l'exactitude ou les droits ne peuvent être garantis), affiché sur le widget Home Screen | 3 |
| 28.8 | Easter egg d'anniversaire d'usage (1 an) | ⚠️ | Message caché unique et non répétable au bout d'un an d'utilisation. Défendable car ce n'est pas un système cumulatif à mécaniques (pas de "niveaux", pas de "gemmes"), mais reste à valider en équipe car ça s'approche d'une logique de récompense | 2 |
| 28.9 | Thème visuel saisonnier discret (ex. un jour par an) | ⚠️ | Idée séduisante pour un "moment" marketing (ex. 1er janvier), mais **contredit directement** la règle du Playbook "accent bleu unique, jamais mélangé avec d'autres couleurs" — à ne pas implémenter sans révision explicite de la charte graphique | 3 |

**Total de cette vague : 27 pts** (non incluse dans le total général — idem section 6, à arbitrer avant intégration officielle).

**Recommandation** : 28.4 (son signature) et 28.6 (micro-animation) sont les moins chers et les plus sûrs pour donner immédiatement une sensation de produit soigné dès le MVP. 28.5 (capsule temporelle) est la plus forte en termes d'attachement émotionnel à la marque mais mérite d'être testée en V1 plutôt que bousculer le MVP. Je déconseille 28.9 tel quel sans validation explicite de la charte — et je laisserais 28.8 de côté tant que la ligne "pas de gamification" n'est pas rediscutée en équipe.