package com.buzi.api.service;

import com.buzi.api.domain.Role;
import com.buzi.api.domain.User;
import com.buzi.api.dto.auth.AppleSignInRequest;
import com.buzi.api.dto.auth.AuthResponse;
import com.buzi.api.repository.UserRepository;
import com.buzi.api.security.apple.AppleIdTokenClaims;
import com.buzi.api.security.apple.AppleTokenVerifier;
import com.buzi.api.security.jwt.JwtProperties;
import com.buzi.api.security.jwt.JwtService;
import com.buzi.api.security.jwt.RefreshTokenService;
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

        User user = userRepository.findByAppleSub(claims.subject())
                .map(existing -> touchLastLogin(existing, claims))
                .orElseGet(() -> createUser(claims));

        return issueTokens(user);
    }

    @Transactional
    public AuthResponse refresh(String refreshToken) {
        RefreshTokenService.RotationResult rotationResult = refreshTokenService.rotate(refreshToken);

        return buildReponse(rotationResult.user(), rotationResult.newToken());
    }

    @Transactional
    public void signOut(UUID userId) {
        refreshTokenService.revokeAllForUser(userId);
    }

    private User touchLastLogin(User existing, AppleIdTokenClaims claims) {
        existing.setLastLoginAt(Instant.now());
        if (claims.email() != null && !claims.email().isBlank()) {
            existing.setEmail(claims.email());
        }
        return userRepository.save(existing);
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
        log.info("Nouveau compte Byzi cree (userdId={})", saved.getId());
        return saved;
    }

    private AuthResponse issueTokens(User user) {
        String refreshToken = refreshTokenService.issue(user);
        return buildReponse(user, refreshToken);
    }

    private AuthResponse buildReponse(User user, String refreshToken) {
        String accessToken = jwtService.generateAccessToken(user.getId(), user.getRole());
        return new AuthResponse(
                user.getId(),
                accessToken,
                jwtProperties.accessTokenTtlSeconds(),
                refreshToken
        );
    }


}
