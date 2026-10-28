package com.kanban.backend.auth.internal.web.dto;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String displayName,
        Instant createdAt
) {
}
