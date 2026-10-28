package com.kanban.backend.infrastructure.web;

import java.time.Instant;
import java.util.Map;

public record ApiValidationErrorResponse(
        int status,
        String error,
        String code,
        Map<String, String> errors,
        Instant timestamp
) {
}
