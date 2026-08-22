package com.byzi.api.security;

import com.byzi.api.domain.Role;

import java.util.UUID;

public record JwtPrincipal(UUID userId, Role role) {
}
