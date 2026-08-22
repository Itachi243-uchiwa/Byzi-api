package com.buzi.api.dto.auth;

import java.util.UUID;

public record AuthResponse(
        UUID userId,
        String accessToken,
        long accessTokenExpiresInSeconds,
        String refreshToken
) {
}
