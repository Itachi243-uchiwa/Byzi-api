package com.byzi.api.security.jwt;

import com.byzi.api.domain.Role;

import java.util.UUID;

public record AccessTokenClaims(UUID userId, Role role) {
}
