package com.buzi.api.service;

import com.buzi.api.domain.Role;
import com.buzi.api.domain.User;
import com.buzi.api.dto.auth.AppleSignInRequest;
import com.buzi.api.dto.auth.AuthResponse;
import com.buzi.api.exception.InvalidAppleTokenException;
import com.buzi.api.repository.UserRepository;
import com.buzi.api.security.apple.AppleIdTokenClaims;
import com.buzi.api.security.apple.AppleTokenVerifier;
import com.buzi.api.security.jwt.JwtProperties;
import com.buzi.api.security.jwt.JwtService;
import com.buzi.api.security.jwt.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story 01.2 : echange d'un identity token Apple contre une paire de tokens Byzi.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private AppleTokenVerifier appleTokenVerifier;
    @Mock
    private UserRepository userRepository;
    @Mock
    private RefreshTokenService refreshTokenService;

    private final JwtProperties jwtProperties = new JwtProperties(
            "unit-test-secret-key-at-least-32-bytes-long", 900, 2592000, "byzi-api-test");
    private final JwtService jwtService = new JwtService(jwtProperties);

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(appleTokenVerifier, userRepository, jwtService,
                refreshTokenService, jwtProperties);
    }

    private User existingUser(String appleSub) {
        User user = User.builder()
                .id(UUID.randomUUID())
                .appleSub(appleSub)
                .role(Role.USER)
                .build();
        return user;
    }

    @Test
    void createsAccountOnFirstSignIn() {
        when(appleTokenVerifier.verify("token")).thenReturn(new AppleIdTokenClaims("apple-sub-1", "a@byzi.app"));
        when(userRepository.findByAppleSub("apple-sub-1")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(refreshTokenService.issue(any())).thenReturn("refresh-token");

        AuthResponse response = authService.signInWithApple(new AppleSignInRequest("token"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getAppleSub()).isEqualTo("apple-sub-1");
        assertThat(saved.getValue().getRole()).isEqualTo(Role.USER);
        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        assertThat(response.accessTokenExpiresInSeconds()).isEqualTo(900);
    }

    @Test
    void neverGrantsAdminRoleOnSelfSignUp() {
        // Un compte cree par l'app iOS ne doit jamais naitre ADMIN : la promotion est un acte
        // deliberе, pas un effet de bord de l'inscription.
        when(appleTokenVerifier.verify("token")).thenReturn(new AppleIdTokenClaims("apple-sub-2", null));
        when(userRepository.findByAppleSub("apple-sub-2")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(refreshTokenService.issue(any())).thenReturn("r");

        authService.signInWithApple(new AppleSignInRequest("token"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getRole()).isNotEqualTo(Role.ADMIN);
    }

    @Test
    void reusesExistingAccountAndTouchesLastLogin() {
        User existing = existingUser("apple-sub-3");
        when(appleTokenVerifier.verify("token")).thenReturn(new AppleIdTokenClaims("apple-sub-3", "b@byzi.app"));
        when(userRepository.findByAppleSub("apple-sub-3")).thenReturn(Optional.of(existing));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(refreshTokenService.issue(any())).thenReturn("r");

        authService.signInWithApple(new AppleSignInRequest("token"));

        assertThat(existing.getLastLoginAt()).isNotNull().isBeforeOrEqualTo(Instant.now());
        assertThat(existing.getEmail()).isEqualTo("b@byzi.app");
    }

    @Test
    void keepsPreviousEmailWhenAppleHidesIt() {
        // Apple ne transmet l'email qu'a la premiere connexion : les suivantes arrivent sans.
        // L'ecraser avec null effacerait une donnee de support deja acquise.
        User existing = existingUser("apple-sub-4");
        existing.setEmail("connu@byzi.app");
        when(appleTokenVerifier.verify("token")).thenReturn(new AppleIdTokenClaims("apple-sub-4", null));
        when(userRepository.findByAppleSub("apple-sub-4")).thenReturn(Optional.of(existing));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(refreshTokenService.issue(any())).thenReturn("r");

        authService.signInWithApple(new AppleSignInRequest("token"));

        assertThat(existing.getEmail()).isEqualTo("connu@byzi.app");
    }

    @Test
    void rejectsInvalidAppleTokenWithoutCreatingAccount() {
        when(appleTokenVerifier.verify("mauvais"))
                .thenThrow(new InvalidAppleTokenException("Identity token Apple invalide"));

        assertThatThrownBy(() -> authService.signInWithApple(new AppleSignInRequest("mauvais")))
                .isInstanceOf(InvalidAppleTokenException.class);

        // Le point critique : aucun compte ne doit naitre d'un token non verifie.
        verify(userRepository, never()).save(any());
    }

    @Test
    void refreshRotatesTokenAndReturnsNewPair() {
        User user = existingUser("apple-sub-5");
        when(refreshTokenService.rotate("ancien"))
                .thenReturn(new RefreshTokenService.RotationResult(user, "nouveau"));

        AuthResponse response = authService.refresh("ancien");

        assertThat(response.refreshToken()).isEqualTo("nouveau");
        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.userId()).isEqualTo(user.getId());
    }

    @Test
    void signOutRevokesEveryRefreshTokenOfTheUser() {
        UUID userId = UUID.randomUUID();

        authService.signOut(userId);

        verify(refreshTokenService).revokeAllForUser(userId);
    }
}
