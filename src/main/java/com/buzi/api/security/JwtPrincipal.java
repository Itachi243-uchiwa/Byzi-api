package com.buzi.api.security;

import com.buzi.api.domain.Role;

import java.util.UUID;

public record JwtPrincipal(UUID userId, Role role) {
}
