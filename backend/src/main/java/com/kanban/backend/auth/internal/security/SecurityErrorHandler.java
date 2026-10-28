package com.kanban.backend.auth.internal.security;

import com.kanban.backend.infrastructure.web.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;

@Component
public class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public SecurityErrorHandler(JsonMapper jsonMapper, Clock clock) {
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        String code = exception instanceof OAuth2AuthenticationException
                ? "ACCESS_TOKEN_INVALID" : "ACCESS_TOKEN_MISSING";
        if (exception.getCause() instanceof JwtValidationException validation
                && validation.getErrors().stream().anyMatch(error -> error.getErrorCode().equals("ACCESS_TOKEN_EXPIRED"))) {
            code = "ACCESS_TOKEN_EXPIRED";
        }
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        write(response, HttpStatus.UNAUTHORIZED, code, "Потрібен чинний access-токен.");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        boolean csrf = exception instanceof CsrfException;
        write(response, HttpStatus.FORBIDDEN, csrf ? "CSRF_INVALID" : "ACCESS_DENIED",
                csrf ? "Некоректний CSRF-токен." : "Недостатньо прав доступу.");
    }

    private void write(HttpServletResponse response, HttpStatus status, String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        jsonMapper.writeValue(response.getOutputStream(), new ApiErrorResponse(
                status.value(), status.getReasonPhrase(), code, message, clock.instant()));
    }
}
