package com.byzi.api.config;

import com.byzi.api.domain.Role;
import com.byzi.api.domain.SubscriptionStatus;
import com.byzi.api.domain.User;
import com.byzi.api.repository.UserRepository;
import com.byzi.api.service.admin.AdminAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Demarrage a froid du back-office (2026-09-09).
 * <p>
 * Ce que ces tests protegent : jusqu'ici, le seul code capable de fabriquer un compte ADMIN
 * etait {@code DemoDataSeeder}, annote {@code @Profile("demo")}. En production, il n'existait
 * <b>aucun moyen d'entrer dans le back-office</b> - la promotion passe par une route qui exige
 * d'etre deja administrateur.
 * <p>
 * Les deux garde-fous a ne jamais perdre : ne rien creer sans configuration explicite (un
 * identifiant par defaut connu de tous serait une porte ouverte sur la suppression de comptes),
 * et ne jamais reecrire un mot de passe deja en place.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminBootstrapTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private AdminAuditService auditService;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private AdminBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        bootstrap = new AdminBootstrap(userRepository, passwordEncoder, auditService);
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
    }

    private void configure(String email, String password) {
        ReflectionTestUtils.setField(bootstrap, "bootstrapEmail", email);
        ReflectionTestUtils.setField(bootstrap, "bootstrapPassword", password);
    }

    /** Sans configuration, le runner ne doit RIEN faire. Aucun compte par defaut, jamais. */
    @Test
    void doesNothingWhenUnconfigured() {
        configure("", "");
        bootstrap.run(null);
        verify(userRepository, never()).save(any());
    }

    /** Un demi-reglage ne cree pas un compte a moitie : les deux valeurs ou rien. */
    @Test
    void doesNothingWhenOnlyOneHalfIsConfigured() {
        configure("kevin@exemple.com", "");
        bootstrap.run(null);
        verify(userRepository, never()).save(any());
    }

    /**
     * Ce compte ouvre la suppression definitive de comptes utilisateurs : on refuse plutot que
     * de creer un acces fragile, et on ne fait pas tomber l'API pour autant.
     */
    @Test
    void refusesAShortPassword() {
        configure("kevin@exemple.com", "court");
        bootstrap.run(null);
        verify(userRepository, never()).save(any());
    }

    @Test
    void createsTheFirstAdminWhenNoAccountMatches() {
        configure("kevin@exemple.com", "un-mot-de-passe-assez-long");
        when(userRepository.findByEmail("kevin@exemple.com")).thenReturn(Optional.empty());

        bootstrap.run(null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        User admin = saved.getValue();
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.getEmail()).isEqualTo("kevin@exemple.com");
        assertThat(passwordEncoder.matches("un-mot-de-passe-assez-long", admin.getPasswordHash())).isTrue();
        assertThat(admin.getPasswordHash())
                .as("le mot de passe ne doit jamais etre stocke en clair")
                .isNotEqualTo("un-mot-de-passe-assez-long");
        assertThat(admin.getSubscriptionStatus())
                .as("un administrateur n'est pas un abonne : le back-office n'ouvre pas l'app")
                .isEqualTo(SubscriptionStatus.EXPIRED);
    }

    /**
     * Le cas normal du fondateur : son compte existe deja, cree par Sign in with Apple depuis
     * l'app. On le promeut plutot que de creer un doublon - que la contrainte d'unicite sur
     * l'email interdirait de toute facon.
     */
    @Test
    void promotesAnExistingAccountInsteadOfDuplicatingIt() {
        configure("kevin@exemple.com", "un-mot-de-passe-assez-long");
        User existing = User.builder()
                .id(UUID.randomUUID())
                .appleSub("apple-sub-existant")
                .email("kevin@exemple.com")
                .role(Role.USER)
                .subscriptionStatus(SubscriptionStatus.ACTIVE)
                .build();
        when(userRepository.findByEmail("kevin@exemple.com")).thenReturn(Optional.of(existing));

        bootstrap.run(null);

        assertThat(existing.getRole()).isEqualTo(Role.ADMIN);
        assertThat(passwordEncoder.matches("un-mot-de-passe-assez-long", existing.getPasswordHash())).isTrue();
        assertThat(existing.getAppleSub())
                .as("la promotion ne doit pas casser la connexion iOS du compte")
                .isEqualTo("apple-sub-existant");
    }

    /**
     * Le garde-fou le plus important : laisser les variables d'environnement en place ne doit
     * pas reinitialiser le mot de passe a chaque redemarrage, y compris apres que l'admin
     * l'ait change.
     */
    @Test
    void neverOverwritesAnExistingPassword() {
        configure("kevin@exemple.com", "le-mot-de-passe-du-fichier");
        String chosenByTheAdmin = passwordEncoder.encode("celui-que-l-admin-a-choisi");
        User existing = User.builder()
                .id(UUID.randomUUID())
                .appleSub("apple-sub-admin")
                .email("kevin@exemple.com")
                .role(Role.ADMIN)
                .passwordHash(chosenByTheAdmin)
                .subscriptionStatus(SubscriptionStatus.EXPIRED)
                .build();
        when(userRepository.findByEmail("kevin@exemple.com")).thenReturn(Optional.of(existing));

        bootstrap.run(null);

        assertThat(existing.getPasswordHash()).isEqualTo(chosenByTheAdmin);
        verify(userRepository, never()).save(any());
    }
}
