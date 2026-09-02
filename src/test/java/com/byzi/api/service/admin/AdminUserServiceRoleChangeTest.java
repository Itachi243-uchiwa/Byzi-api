package com.byzi.api.service.admin;

import com.byzi.api.domain.Role;
import com.byzi.api.domain.User;
import com.byzi.api.exception.ForbiddenOperationException;
import com.byzi.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Garde-fous de l'attribution de role (story 17.4). Ils sont testes hors contexte Spring :
 * l'autorisation elle-meme (seul un ADMIN complet peut appeler cette methode) est verifiee en
 * integration par AdminRoleSeparationIntegrationTest, alors que ce qui est en jeu ici est la
 * regle metier - deux facons de rendre le back-office inadministrable.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminUserServiceRoleChangeTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private AdminAuditService auditService;

    private AdminUserService service;

    private final UUID adminId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AdminUserService(userRepository, null, null, null, null, null, null, auditService);
    }

    private User account(UUID id, Role role, String passwordHash) {
        User user = User.builder()
                .id(id)
                .appleSub("apple-sub-" + id)
                .role(role)
                .passwordHash(passwordHash)
                .build();
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        return user;
    }

    @Test
    void refusesToChangeOnesOwnRole() {
        assertThatThrownBy(() -> service.changeRole(adminId, Role.USER, adminId, "admin@byzi.app"))
                .isInstanceOf(ForbiddenOperationException.class);

        // Ni escalade silencieuse, ni auto-exclusion : la demande n'atteint meme pas la base.
        verify(userRepository, never()).save(any());
    }

    @Test
    void refusesToRemoveTheLastFullAdmin() {
        UUID targetId = UUID.randomUUID();
        account(targetId, Role.ADMIN, "hash");
        when(userRepository.countByRole(Role.ADMIN)).thenReturn(1L);

        // Sinon plus personne ne peut nommer d'administrateur, et la seule issue est une
        // intervention manuelle en base.
        assertThatThrownBy(() -> service.changeRole(targetId, Role.ADMIN_SUPPORT, adminId, "admin@byzi.app"))
                .isInstanceOf(ForbiddenOperationException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void allowsDemotingAnAdminWhenAnotherOneRemains() {
        UUID targetId = UUID.randomUUID();
        account(targetId, Role.ADMIN, "hash");
        when(userRepository.countByRole(Role.ADMIN)).thenReturn(2L);

        assertThat(service.changeRole(targetId, Role.ADMIN_SUPPORT, adminId, "admin@byzi.app")).isTrue();
    }

    @Test
    void warnsWhenThePromotedAccountHasNoPassword() {
        UUID targetId = UUID.randomUUID();
        account(targetId, Role.USER, null);

        // Un compte Sign in with Apple n'a pas de mot de passe : la promotion est valide, mais
        // il ne pourra pas se connecter au back-office tant qu'on ne lui en aura pas pose un.
        assertThat(service.changeRole(targetId, Role.ADMIN_SUPPORT, adminId, "admin@byzi.app")).isFalse();
    }

    @Test
    void demotingToUserNeedsNoPassword() {
        UUID targetId = UUID.randomUUID();
        account(targetId, Role.ADMIN_SUPPORT, null);
        when(userRepository.countByRole(Role.ADMIN)).thenReturn(3L);

        assertThat(service.changeRole(targetId, Role.USER, adminId, "admin@byzi.app")).isTrue();
    }

    @Test
    void everyRoleChangeIsAudited() {
        UUID targetId = UUID.randomUUID();
        account(targetId, Role.USER, "hash");

        service.changeRole(targetId, Role.ADMIN_FINANCE, adminId, "admin@byzi.app");

        verify(auditService).record(adminId, "admin@byzi.app",
                AdminAuditService.ACTION_CHANGE_ROLE, targetId, "Role USER -> ADMIN_FINANCE");
    }
}
