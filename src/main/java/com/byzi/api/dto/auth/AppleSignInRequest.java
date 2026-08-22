package com.byzi.api.dto.auth;

import jakarta.validation.constraints.NotBlank;

public record AppleSignInRequest(
        @NotBlank(message="IdentityToken est requis")
        String identityToken
) {
}
