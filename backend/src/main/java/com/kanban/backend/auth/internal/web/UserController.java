package com.kanban.backend.auth.internal.web;

import com.kanban.backend.auth.internal.application.AuthService;
import com.kanban.backend.auth.internal.application.InvalidAccessTokenException;
import com.kanban.backend.auth.internal.web.dto.UserResponse;
import com.kanban.backend.infrastructure.web.ApiErrorResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
public class UserController {
    private final AuthService auth;

    public UserController(AuthService auth) {
        this.auth = auth;
    }

    @GetMapping("/api/v1/users/me")
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal Jwt jwt) {
        var user = auth.getCurrentUser(UUID.fromString(jwt.getSubject()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(user);
    }

    @ExceptionHandler(InvalidAccessTokenException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidAccessToken(InvalidAccessTokenException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .cacheControl(CacheControl.noStore()).body(
                        new ApiErrorResponse(401, "Unauthorized", "ACCESS_TOKEN_INVALID",
                                exception.getMessage(), Instant.now()));
    }
}
