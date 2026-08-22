package com.byzi.api.security.jwt;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "byzi.security.jwt")
public record JwtProperties(
        @NotBlank
        String secret,

        @Positive
        long accessTokenTtlSeconds,

        @Positive
        long refreshTokenTtlSeconds,

        @NotBlank
        String issuer
) {
}
