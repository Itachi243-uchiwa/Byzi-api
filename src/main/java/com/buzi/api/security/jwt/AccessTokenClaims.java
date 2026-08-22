package com.buzi.api.security.jwt;

import com.buzi.api.domain.Role;

import java.util.UUID;

public record AccessTokenClaims(UUID userId, Role role) {
}
