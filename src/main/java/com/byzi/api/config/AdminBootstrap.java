package com.byzi.api.config;

import com.byzi.api.domain.Role;
import com.byzi.api.domain.SubscriptionStatus;
import com.byzi.api.domain.User;
import com.byzi.api.repository.UserRepository;
import com.byzi.api.service.admin.AdminAuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Cree ou promeut le <b>premier administrateur</b> du back-office, a partir de la configuration.
 * <p>
 * <b>Le trou que ceci ferme (2026-09-09).</b> Le back-office etait complet - connexion,
 * tableau de bord, fiches comptes, journal d'audit, trois roles - mais le seul code capable de
 * fabriquer un compte ADMIN etait {@code DemoDataSeeder}, annote {@code @Profile("demo")}. En
 * production il n'existait donc <b>aucun moyen d'entrer</b> : la promotion d'un compte passe par
 * {@code POST /admin/users/{id}/role}, qui exige d'etre deja administrateur. Un demarrage a
 * froid etait impossible sans requete SQL manuelle sur la base de production - exactement le
 * genre de geste qu'un journal d'audit est cense rendre inutile.
 *
 * <h2>Ce que ce runner fait, et ne fait pas</h2>
 * <ul>
 *   <li><b>Inerte par defaut.</b> Sans les deux proprietes, il ne touche a rien et le dit une
 *       fois au demarrage. Aucun compte n'est cree "au cas ou", aucun mot de passe par defaut
 *       n'existe nulle part - un identifiant par defaut connu de tous est une porte ouverte.</li>
 *   <li><b>Idempotent.</b> Relance apres relance, un compte deja administrateur avec un mot de
 *       passe est laisse tel quel. Le runner ne reecrit <b>jamais</b> un hash existant : sinon,
 *       laisser la variable d'environnement en place reinitialiserait le mot de passe a chaque
 *       redemarrage, y compris apres que l'admin l'ait change.</li>
 *   <li><b>Promeut un compte existant.</b> Si l'email correspond deja a un compte iOS - le cas
 *       normal pour un fondateur qui utilise sa propre app - on lui ajoute le role et le hash
 *       plutot que de creer un doublon, ce que la contrainte d'unicite sur l'email interdirait
 *       de toute facon.</li>
 *   <li><b>Refuse un mot de passe faible</b> plutot que de creer un acces fragile vers la
 *       suppression de comptes. Il journalise et s'abstient : faire echouer le demarrage de
 *       toute l'API pour une variable d'environnement mal remplie rendrait l'app indisponible
 *       pour ses utilisateurs, ce qui est une punition sans rapport avec la faute.</li>
 *   <li><b>Ne journalise jamais le mot de passe</b>, ni en clair ni tronque.</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>
 * BYZI_ADMIN_BOOTSTRAP_EMAIL=kevin@exemple.com
 * BYZI_ADMIN_BOOTSTRAP_PASSWORD=&lt;mot de passe long, genere&gt;
 * </pre>
 * Demarrer une fois, se connecter sur {@code /admin}, <b>changer le mot de passe</b>, puis
 * retirer les deux variables. Les administrateurs suivants se creent depuis le back-office
 * ({@code POST /admin/users/{id}/role}), ou chaque promotion laisse une trace d'audit.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrap implements ApplicationRunner {

    /**
     * Longueur minimale. 12 caracteres parce que ce compte ouvre la suppression definitive de
     * comptes utilisateurs : c'est le secret le plus sensible du systeme, et le seul du projet
     * qui soit un mot de passe plutot qu'une cle generee.
     */
    private static final int MINIMUM_PASSWORD_LENGTH = 12;

    private static final String ACTION_BOOTSTRAP = "ADMIN_BOOTSTRAP";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AdminAuditService auditService;

    @Value("${byzi.admin.bootstrap.email:}")
    private String bootstrapEmail;

    @Value("${byzi.admin.bootstrap.password:}")
    private String bootstrapPassword;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String email = bootstrapEmail == null ? "" : bootstrapEmail.trim();
        String password = bootstrapPassword == null ? "" : bootstrapPassword;

        if (email.isEmpty() && password.isEmpty()) {
            log.debug("Bootstrap admin non configure, aucun compte cree");
            return;
        }
        if (email.isEmpty() || password.isEmpty()) {
            log.error("Bootstrap admin incomplet : il faut byzi.admin.bootstrap.email ET "
                    + "byzi.admin.bootstrap.password. Aucun compte cree.");
            return;
        }
        if (password.length() < MINIMUM_PASSWORD_LENGTH) {
            log.error("Bootstrap admin refuse : le mot de passe fait moins de {} caracteres. "
                    + "Ce compte ouvre la suppression definitive de comptes. Aucun compte cree.",
                    MINIMUM_PASSWORD_LENGTH);
            return;
        }

        Optional<User> existing = userRepository.findByEmail(email);
        if (existing.isPresent()) {
            promote(existing.get(), password);
        } else {
            create(email, password);
        }
    }

    /**
     * Le compte existe deja - typiquement celui du fondateur, cree par Sign in with Apple depuis
     * l'app iOS. On lui ajoute ce qui manque, sans jamais ecraser ce qui est deja la.
     */
    private void promote(User user, String password) {
        boolean hadPassword = user.getPasswordHash() != null && !user.getPasswordHash().isBlank();
        boolean wasAdmin = user.getRole() != null && user.getRole().isAdmin();

        if (hadPassword && wasAdmin) {
            log.info("Bootstrap admin : le compte est deja administrateur avec un mot de passe, rien a faire");
            return;
        }

        if (!wasAdmin) {
            user.setRole(Role.ADMIN);
        }
        if (!hadPassword) {
            user.setPasswordHash(passwordEncoder.encode(password));
        }
        userRepository.save(user);

        auditService.record(user.getId(), user.getEmail(), ACTION_BOOTSTRAP, user.getId(),
                "Promotion en ADMIN au demarrage (bootstrap de configuration)");
        log.warn("Bootstrap admin : compte existant promu ADMIN. Se connecter sur /admin, changer "
                + "le mot de passe, puis retirer byzi.admin.bootstrap.*");
    }

    private void create(String email, String password) {
        User admin = userRepository.save(User.builder()
                .id(UUID.randomUUID())
                // `appleSub` est requis et unique, mais ce compte ne se connecte jamais par
                // Sign in with Apple : il n'a que le back-office. Une valeur reservee, jamais
                // emise par Apple, evite toute collision avec un vrai identifiant.
                .appleSub("bootstrap-admin-" + UUID.randomUUID())
                .email(email)
                .role(Role.ADMIN)
                .passwordHash(passwordEncoder.encode(password))
                // Un administrateur n'est pas un abonne : aucun acces applicatif ne lui est
                // accorde au passage. Le back-office et l'app sont deux mondes separes.
                .subscriptionStatus(SubscriptionStatus.EXPIRED)
                .lastLoginAt(Instant.now())
                .build());

        auditService.record(admin.getId(), admin.getEmail(), ACTION_BOOTSTRAP, admin.getId(),
                "Creation du compte administrateur initial (bootstrap de configuration)");
        log.warn("Bootstrap admin : compte administrateur cree. Se connecter sur /admin, changer "
                + "le mot de passe, puis retirer byzi.admin.bootstrap.*");
    }
}
