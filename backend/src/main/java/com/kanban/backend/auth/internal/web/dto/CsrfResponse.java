package com.kanban.backend.auth.internal.web.dto;

public record CsrfResponse(
        String token,
        String headerName
) {
}
