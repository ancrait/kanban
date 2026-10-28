package com.kanban.backend.infrastructure.web;

import java.time.Instant;

public record ApiErrorResponse(
        int status,
        String error,
        String code,
        String message,
        Instant timestamp
) {
}
