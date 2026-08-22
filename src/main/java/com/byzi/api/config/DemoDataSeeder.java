package com.byzi.api.config;

import com.byzi.api.domain.AdminAuditLog;
import com.byzi.api.domain.AppBlockRule;
import com.byzi.api.domain.FocusSession;
import com.byzi.api.domain.Role;
import com.byzi.api.domain.SessionMode;
import com.byzi.api.domain.StreakRecord;
import com.byzi.api.domain.SubscriptionEvent;
import com.byzi.api.domain.SubscriptionStatus;
import com.byzi.api.domain.User;
import com.byzi.api.repository.AdminAuditLogRepository;
import com.byzi.api.repository.AppBlockRuleRepository;
import com.byzi.api.repository.FocusSessionRepository;
import com.byzi.api.repository.StreakRecordRepository;
import com.byzi.api.repository.SubscriptionEventRepository;
import com.byzi.api.repository.UserRepository;
import com.byzi.api.service.admin.AdminAuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Jeu de donnees fictif pour parcourir le back-office en local.
 * <p>
 * <b>@Profile("demo") uniquement.</b> Ce runner cree un compte administrateur dont le mot de
 * passe est ecrit en clair dans application-demo.yml : il ne doit jamais s'activer en dev
 * partage ni en production. Le profil est distinct de "dev" precisement pour qu'un
 * SPRING_PROFILES_ACTIVE=dev mal place ne puisse pas le declencher.
 * <p>
 * Le seeder est idempotent : il ne fait rien si le compte admin existe deja, ce qui permet de
 * relancer l'application sans empiler les jeux de donnees (la base demo est un fichier, donc
 * persistante entre deux demarrages).
 */
@Slf4j
@Component
@Profile("demo")
@RequiredArgsConstructor
public class DemoDataSeeder implements CommandLineRunner {

    /** Graine fixe : le jeu de donnees est identique d'une execution a l'autre, donc discutable en equipe. */
    private static final long SEED = 20_260_728L;

    private static final String OPAQUE_SELECTION =
            "eyJhcHBUb2tlbnMiOlsiQUFBQ0FnRUEiLCJBQUFDQWdFQiJdLCJjYXRlZ29yeVRva2VucyI6W119";

    private final UserRepository userRepository;
    private final FocusSessionRepository focusSessionRepository;
    private final StreakRecordRepository streakRecordRepository;
    private final AppBlockRuleRepository appBlockRuleRepository;
    private final SubscriptionEventRepository subscriptionEventRepository;
    private final AdminAuditLogRepository auditLogRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${byzi.demo.admin-email}")
    private String adminEmail;

    @Value("${byzi.demo.admin-password}")
    private String adminPassword;

    @Override
    @Transactional
    public void run(String... args) {
        if (userRepository.findByEmail(adminEmail).isPresent()) {
            log.info("Jeu de donnees de demonstration deja present, rien a faire");
            return;
        }

        User admin = createAdmin();
        List<User> users = createUsers();
        createActivity(users);
        createAuditTrail(admin, users);

        log.info("""

                =====================================================================
                  Back-office de demonstration pret : http://localhost:8080/admin
                  Identifiants : {} / {}
                  {} comptes fictifs crees.
                =====================================================================
                """, adminEmail, adminPassword, users.size());
    }

    private User createAdmin() {
        return userRepository.save(User.builder()
                .id(UUID.randomUUID())
                .appleSub("demo-admin-" + UUID.randomUUID())
                .email(adminEmail)
                .role(Role.ADMIN)
                .passwordHash(passwordEncoder.encode(adminPassword))
                .subscriptionStatus(SubscriptionStatus.ACTIVE)
                .lastLoginAt(Instant.now())
                .build());
    }

    /**
     * Couvre les quatre statuts d'abonnement, pour que chaque variante de badge et chaque
     * KPI du tableau de bord ait quelque chose a afficher - y compris le churn et les impayes,
     * qui resteraient invisibles avec un jeu de donnees uniquement "heureux".
     */
    private List<User> createUsers() {
        record Profile(String prenom, SubscriptionStatus status, int ancienneteJours, boolean emailMasque) {
        }

        List<Profile> profils = List.of(
                new Profile("lea", SubscriptionStatus.ACTIVE, 210, false),
                new Profile("yanis", SubscriptionStatus.ACTIVE, 145, false),
                new Profile("chloe", SubscriptionStatus.ACTIVE, 98, false),
                new Profile("noah", SubscriptionStatus.ACTIVE, 61, true),
                new Profile("ines", SubscriptionStatus.ACTIVE, 34, false),
                new Profile("adam", SubscriptionStatus.TRIAL, 2, false),
                new Profile("sarah", SubscriptionStatus.TRIAL, 1, false),
                new Profile("hugo", SubscriptionStatus.TRIAL, 3, true),
                new Profile("manon", SubscriptionStatus.GRACE_PERIOD, 120, false),
                new Profile("rayan", SubscriptionStatus.GRACE_PERIOD, 76, false),
                new Profile("jade", SubscriptionStatus.EXPIRED, 260, false),
                new Profile("tom", SubscriptionStatus.EXPIRED, 190, false),
                new Profile("nina", SubscriptionStatus.EXPIRED, 155, true));

        List<User> created = new ArrayList<>();
        for (Profile p : profils) {
            Instant inscription = Instant.now().minus(p.ancienneteJours(), ChronoUnit.DAYS);
            created.add(userRepository.save(User.builder()
                    .id(UUID.randomUUID())
                    .appleSub("demo-" + p.prenom() + "-" + UUID.randomUUID())
                    // Sign in with Apple permet de masquer son adresse : le back-office doit
                    // savoir afficher proprement un compte sans email.
                    .email(p.emailMasque() ? null : p.prenom() + "@exemple.be")
                    .role(Role.USER)
                    .subscriptionStatus(p.status())
                    .subscriptionExpiresAt(expiryFor(p.status()))
                    .lastLoginAt(inscription.plus(p.ancienneteJours() / 2, ChronoUnit.DAYS))
                    .build()));
        }
        return created;
    }

    private Instant expiryFor(SubscriptionStatus status) {
        return switch (status) {
            case ACTIVE -> Instant.now().plus(20, ChronoUnit.DAYS);
            case TRIAL -> Instant.now().plus(2, ChronoUnit.DAYS);
            case GRACE_PERIOD -> Instant.now().plus(5, ChronoUnit.DAYS);
            case EXPIRED -> Instant.now().minus(12, ChronoUnit.DAYS);
        };
    }

    private void createActivity(List<User> users) {
        Random random = new Random(SEED);

        for (int i = 0; i < users.size(); i++) {
            User user = users.get(i);

            // Streak decroissant selon la position : le premier compte a une longue serie
            // continue, les suivants des series de plus en plus courtes ou trouees.
            int longueurStreak = Math.max(0, 18 - i * 2);
            for (int jour = 0; jour < longueurStreak; jour++) {
                streakRecordRepository.save(StreakRecord.builder()
                        .id(UUID.randomUUID())
                        .user(user)
                        .day(LocalDate.now().minusDays(jour))
                        .goalReached(true)
                        .focusMinutes(45 + random.nextInt(120))
                        .build());
            }

            int nbSessions = 3 + random.nextInt(12);
            for (int s = 0; s < nbSessions; s++) {
                Instant debut = Instant.now()
                        .minus(random.nextInt(30), ChronoUnit.DAYS)
                        .minus(random.nextInt(10), ChronoUnit.HOURS);
                int duree = List.of(1500, 2700, 3600, 5400).get(random.nextInt(4));
                boolean terminee = random.nextInt(10) < 8;
                focusSessionRepository.save(FocusSession.builder()
                        .id(UUID.randomUUID())
                        .user(user)
                        .startedAt(debut)
                        .endedAt(terminee ? debut.plusSeconds(duree) : null)
                        .plannedDurationSeconds(duree)
                        .mode(random.nextInt(3) == 0 ? SessionMode.DEEP_FOCUS : SessionMode.STANDARD)
                        .completed(terminee)
                        .build());
            }

            appBlockRuleRepository.save(AppBlockRule.builder()
                    .id(UUID.randomUUID())
                    .user(user)
                    .selectionData(OPAQUE_SELECTION)
                    .dailyLimitMinutes(random.nextInt(3) == 0 ? null : 30 + random.nextInt(60))
                    .scheduleStart("09:00")
                    .scheduleEnd("18:00")
                    .isActive(random.nextInt(5) > 0)
                    .build());

            createSubscriptionHistory(user);
        }
    }

    /** Historique coherent avec le statut courant, tel que l'auraient produit les webhooks. */
    private void createSubscriptionHistory(User user) {
        Instant base = Instant.now().minus(40, ChronoUnit.DAYS);

        record Etape(String type, SubscriptionStatus statut, int joursApres) {
        }

        List<Etape> etapes = switch (user.getSubscriptionStatus()) {
            case ACTIVE -> List.of(
                    new Etape("INITIAL_PURCHASE", SubscriptionStatus.TRIAL, 0),
                    new Etape("RENEWAL", SubscriptionStatus.ACTIVE, 3),
                    new Etape("RENEWAL", SubscriptionStatus.ACTIVE, 33));
            case TRIAL -> List.of(
                    new Etape("INITIAL_PURCHASE", SubscriptionStatus.TRIAL, 38));
            case GRACE_PERIOD -> List.of(
                    new Etape("INITIAL_PURCHASE", SubscriptionStatus.TRIAL, 0),
                    new Etape("RENEWAL", SubscriptionStatus.ACTIVE, 3),
                    new Etape("BILLING_ISSUE", SubscriptionStatus.GRACE_PERIOD, 35));
            case EXPIRED -> List.of(
                    new Etape("INITIAL_PURCHASE", SubscriptionStatus.TRIAL, 0),
                    new Etape("RENEWAL", SubscriptionStatus.ACTIVE, 3),
                    new Etape("CANCELLATION", SubscriptionStatus.ACTIVE, 20),
                    new Etape("EXPIRATION", SubscriptionStatus.EXPIRED, 28));
        };

        for (Etape etape : etapes) {
            Instant quand = base.plus(etape.joursApres(), ChronoUnit.DAYS);
            subscriptionEventRepository.save(SubscriptionEvent.builder()
                    .id(UUID.randomUUID())
                    .user(user)
                    .eventId("demo-evt-" + UUID.randomUUID())
                    .eventType(etape.type())
                    .resultingStatus(etape.statut())
                    .expiresAt(quand.plus(30, ChronoUnit.DAYS))
                    .occurredAt(quand)
                    .receivedAt(quand.plusSeconds(2))
                    .build());
        }
    }

    /** Quelques interventions passees, pour que le journal d'audit ne soit pas vide. */
    private void createAuditTrail(User admin, List<User> users) {
        record Trace(String action, int cible, String detail, int ilYAJours) {
        }

        List<Trace> traces = List.of(
                new Trace(AdminAuditService.ACTION_EXTEND_TRIAL, 8, "Prolongation de 7 jour(s)", 6),
                new Trace(AdminAuditService.ACTION_EXTEND_TRIAL, 5, "Prolongation de 3 jour(s)", 4),
                new Trace(AdminAuditService.ACTION_MARK_REFUNDED, 11, "Remboursement App Store", 3),
                new Trace(AdminAuditService.ACTION_EXTEND_TRIAL, 9, "Prolongation de 14 jour(s)", 1));

        for (Trace t : traces) {
            auditLogRepository.save(AdminAuditLog.builder()
                    .id(UUID.randomUUID())
                    .adminId(admin.getId())
                    .adminLabel(admin.getEmail())
                    .action(t.action())
                    .targetUserId(users.get(t.cible()).getId())
                    .details(t.detail())
                    .occurredAt(Instant.now().minus(t.ilYAJours(), ChronoUnit.DAYS))
                    .build());
        }

        // Une trace dont la cible n'existe plus : c'est le cas qui prouve que l'audit survit a
        // la suppression du compte concerne (pas de cle etrangere en cascade).
        auditLogRepository.save(AdminAuditLog.builder()
                .id(UUID.randomUUID())
                .adminId(admin.getId())
                .adminLabel(admin.getEmail())
                .action(AdminAuditService.ACTION_DELETE_ACCOUNT)
                .targetUserId(UUID.randomUUID())
                .details("Suppression definitive du compte (RGPD / guideline 5.1.1(v))")
                .occurredAt(Instant.now().minus(9, ChronoUnit.DAYS))
                .build());
    }
}
