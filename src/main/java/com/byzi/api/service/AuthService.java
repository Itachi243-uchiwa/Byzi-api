package com.byzi.api.service;

import com.byzi.api.domain.Role;
import com.byzi.api.domain.User;
import com.byzi.api.dto.auth.AppleSignInRequest;
import com.byzi.api.dto.auth.AuthResponse;
import com.byzi.api.repository.UserRepository;
import com.byzi.api.exception.InvalidAppleTokenException;
import com.byzi.api.security.apple.AppleIdTokenClaims;
import com.byzi.api.security.apple.AppleNonce;
import com.byzi.api.security.apple.AppleTokenVerifier;
import com.byzi.api.security.jwt.JwtProperties;
import com.byzi.api.security.jwt.JwtService;
import com.byzi.api.security.jwt.RefreshTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AppleTokenVerifier appleTokenVerifier;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final JwtProperties jwtProperties;

    @Transactional
    public AuthResponse signInWithApple(AppleSignInRequest request) {
        AppleIdTokenClaims claims = appleTokenVerifier.verify(request.identityToken());
        verifyNonce(request, claims);

        User user = userRepository.findByAppleSub(claims.subject())
                .map(existing -> touchLastLogin(existing, claims))
                .orElseGet(() -> createUser(claims));

        return issueTokens(user);
    }

    @Transactional
    public AuthResponse refresh(String refreshToken) {
        RefreshTokenService.RotationResult rotationResult = refreshTokenService.rotate(refreshToken);

        return buildResponse(rotationResult.user(), rotationResult.newToken());
    }

    @Transactional
    public void signOut(UUID userId) {
        refreshTokenService.revokeAllForUser(userId);
    }

    /**
     * Anti-rejeu (story 01.2). Des lors que le token verifie porte un claim {@code nonce} -
     * ce qui est le cas de tout token emis pour l'app Dopamyn, qui passe toujours un nonce a
     * Apple -, le client DOIT fournir le nonce brut correspondant. Un token intercepte et
     * rejoue sans ce nonce brut est refuse. Les tokens sans claim {@code nonce} (aucun client
     * reel, uniquement des mocks de test) restent acceptes tels quels.
     */
    private void verifyNonce(AppleSignInRequest request, AppleIdTokenClaims claims) {
        if (claims.nonce() == null) {
            return;
        }
        if (!AppleNonce.matches(request.nonce(), claims.nonce())) {
            log.warn("Connexion Apple refusee : nonce absent ou non concordant");
            throw new InvalidAppleTokenException("Nonce Apple invalide");
        }
    }

    private User touchLastLogin(User existing, AppleIdTokenClaims claims) {
        existing.setLastLoginAt(Instant.now());
        applyVerifiedEmailIfSafe(existing, claims);
        return userRepository.save(existing);
    }

    /**
     * L'identite d'un compte USER repose sur appleSub, jamais sur l'email : le mettre a jour
     * n'est qu'un service rendu au support, pas une necessite d'authentification. On ne
     * l'ecrit donc que si (1) il a reellement change - eviter un UPDATE a chaque connexion
     * pour une donnee qui ne bouge quasiment jamais -, (2) Apple le declare verifie - un email
     * non confirme n'est pas une donnee de contact fiable -, et (3) aucun AUTRE compte ne le
     * porte deja : users.email est unique (V3, connexion back-office), et laisser save()
     * echouer sur cette contrainte transformerait une connexion Apple parfaitement legitime en
     * 409 pour l'utilisateur.
     */
    private void applyVerifiedEmailIfSafe(User existing, AppleIdTokenClaims claims) {
        String newEmail = claims.email();
        if (newEmail == null || newEmail.isBlank()
                || !claims.emailVerified()
                || newEmail.equals(existing.getEmail())) {
            return;
        }
        userRepository.findByEmail(newEmail)
                .filter(other -> !other.getId().equals(existing.getId()))
                .ifPresentOrElse(
                        conflict -> log.warn(
                                "Email Apple deja rattache a un autre compte (userId={}), mise a jour ignoree",
                                existing.getId()),
                        () -> existing.setEmail(newEmail));
    }

    private User createUser(AppleIdTokenClaims claims) {
        User user = User.builder()
                .id(UUID.randomUUID())
                .appleSub(claims.subject())
                .email(claims.email())
                .role(Role.USER)
                .lastLoginAt(Instant.now())
                .build();

        User saved = userRepository.save(user);
        log.info("Nouveau compte Byzi cree (userId={})", saved.getId());
        return saved;
    }

    private AuthResponse issueTokens(User user) {
        String refreshToken = refreshTokenService.issue(user);
        return buildResponse(user, refreshToken);
    }

    private AuthResponse buildResponse(User user, String refreshToken) {
        String accessToken = jwtService.generateAccessToken(user.getId(), user.getRole());
        return new AuthResponse(
                user.getId(),
                accessToken,
                jwtProperties.accessTokenTtlSeconds(),
                refreshToken
        );
    }


}
