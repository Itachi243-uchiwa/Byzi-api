package com.byzi.api.controller;

import com.byzi.api.dto.auth.AppleSignInRequest;
import com.byzi.api.dto.auth.AuthResponse;
import com.byzi.api.dto.auth.RefreshTokenRequest;
import com.byzi.api.security.SecurityUtils;
import com.byzi.api.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "AUth", description = "Authentification SIgn in with Apple et cycle de vie des tokens Buzy")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;

    @Operation(summary = "Echnage un identity token Apple core contre une apire access/refresh token Byzi")
    @PostMapping("/apple")
    public ResponseEntity<AuthResponse> signInWithApple(
            @Valid
            @RequestBody AppleSignInRequest request
    ) {
        return ResponseEntity.ok(authService.signInWithApple(request));
    }

    @Operation(summary = "Fait tourner un refresh token contre une nouvelle paire de tokens")
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok(authService.refresh(request.refreshToken()));
    }

    @Operation(summary = "Revoque tous les refresh tokens actifs de l'utilisateur courant (deconnexion globale)")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        authService.signOut(SecurityUtils.currentUserId());
        return ResponseEntity.noContent().build();
    }
}
