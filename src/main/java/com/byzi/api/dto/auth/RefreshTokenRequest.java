package com.byzi.api.dto.auth;

import jakarta.validation.constraints.NotBlank;

public record RefreshTokenRequest(
        @NotBlank(message="refreshToken est requis")
        String refreshToken
) {
}
