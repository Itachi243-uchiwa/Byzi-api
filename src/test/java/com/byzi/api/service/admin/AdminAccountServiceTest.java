package com.byzi.api.service.admin;

import com.byzi.api.domain.Role;
import com.byzi.api.domain.User;
import com.byzi.api.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Changement de mot de passe administrateur (2026-09-09).
 * <p>
 * Sans cet ecran, le mot de passe pose par {@code AdminBootstrap} devait rester dans la
 * configuration du serveur pour toujours - ce qui vide de son sens l'idee meme d'un secret de
 * "bootstrap".
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminAccountServiceTest {

    private static final String EMAIL = "kevin@exemple.com";
    private static final String CURRENT = "mot-de-passe-actuel";

    @Mock
    private UserRepository userRepository;
    @Mock
    private AdminAuditService auditService;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private AdminAccountService service;
    private User admin;

    @BeforeEach
    void setUp() {
        service = new AdminAccountService(userRepository, passwordEncoder, auditService);
        admin = User.builder()
                .id(UUID.randomUUID())
                .appleSub("apple-sub-admin")
                .email(EMAIL)
                .role(Role.ADMIN)
                .passwordHash(passwordEncoder.encode(CURRENT))
                .build();
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(admin));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void changesThePasswordAndLeavesAnAuditTrail() {
        service.changePassword(EMAIL, CURRENT, "un-nouveau-mot-de-passe", "un-nouveau-mot-de-passe");

        assertThat(passwordEncoder.matches("un-nouveau-mot-de-passe", admin.getPasswordHash())).isTrue();
        verify(auditService).record(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsAWrongCurrentPassword() {
        assertThatThrownBy(() -> service.changePassword(EMAIL, "pas-le-bon", "un-nouveau-mot-de-passe", "un-nouveau-mot-de-passe"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(userRepository, never()).save(any());
    }

    /**
     * Un compte inconnu et un mot de passe faux renvoient le MEME message : distinguer les deux
     * dirait a un attaquant quelles adresses existent.
     */
    @Test
    void doesNotRevealWhetherTheAccountExists() {
        when(userRepository.findByEmail("inconnu@exemple.com")).thenReturn(Optional.empty());

        String unknownAccount = catchMessage(() ->
                service.changePassword("inconnu@exemple.com", CURRENT, "un-nouveau-mot-de-passe", "un-nouveau-mot-de-passe"));
        String wrongPassword = catchMessage(() ->
                service.changePassword(EMAIL, "pas-le-bon", "un-nouveau-mot-de-passe", "un-nouveau-mot-de-passe"));

        assertThat(unknownAccount).isEqualTo(wrongPassword);
    }

    @Test
    void rejectsAShortPassword() {
        assertThatThrownBy(() -> service.changePassword(EMAIL, CURRENT, "court", "court"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void rejectsMismatchedConfirmation() {
        assertThatThrownBy(() -> service.changePassword(EMAIL, CURRENT, "un-nouveau-mot-de-passe", "un-autre-mot-de-passe"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(userRepository, never()).save(any());
    }

    /** Reconduire le meme mot de passe n'est pas un changement. */
    @Test
    void rejectsReusingTheCurrentPassword() {
        assertThatThrownBy(() -> service.changePassword(EMAIL, CURRENT, CURRENT, CURRENT))
                .isInstanceOf(IllegalArgumentException.class);
        verify(userRepository, never()).save(any());
    }

    private String catchMessage(Runnable action) {
        try {
            action.run();
            return null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }
}
