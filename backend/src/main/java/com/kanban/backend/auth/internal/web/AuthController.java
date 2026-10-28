package com.kanban.backend.auth.internal.web;

import com.kanban.backend.auth.internal.application.AuthService;
import com.kanban.backend.auth.internal.application.AuthRateLimiter;
import com.kanban.backend.auth.internal.application.EmailAlreadyExistsException;
import com.kanban.backend.auth.internal.application.InvalidCredentialsException;
import com.kanban.backend.auth.internal.application.InvalidRefreshTokenException;
import com.kanban.backend.auth.internal.application.RateLimitExceededException;
import com.kanban.backend.auth.internal.config.AuthProperties;
import com.kanban.backend.auth.internal.web.dto.AuthResponse;
import com.kanban.backend.auth.internal.web.dto.LoginRequest;
import com.kanban.backend.auth.internal.web.dto.RegisterRequest;
import com.kanban.backend.infrastructure.web.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.time.Duration;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService auth;
    private final AuthProperties properties;
    private final CookieCsrfTokenRepository csrf;
    private final AuthRateLimiter rateLimiter;

    public AuthController(AuthService auth, AuthProperties properties, CookieCsrfTokenRepository csrf,
                          AuthRateLimiter rateLimiter) {
        this.auth = auth;
        this.properties = properties;
        this.csrf = csrf;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest body,
                                                HttpServletRequest request, HttpServletResponse response) {
        rateLimiter.checkRegister(request.getRemoteAddr());
        var result = auth.register(body);
        setAuthCookies(result.refreshToken(), request, response);
        return ResponseEntity.created(URI.create("/api/v1/users/me"))
                .cacheControl(CacheControl.noStore())
                .body(result.response());
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest body,
                                             HttpServletRequest request, HttpServletResponse response) {
        rateLimiter.checkLogin(request.getRemoteAddr(), body.email());
        var result = auth.login(body);
        setAuthCookies(result.refreshToken(), request, response);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.response());
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@CookieValue(name = "kanban_refresh", required = false) String refreshToken,
                                               HttpServletRequest request, HttpServletResponse response) {
        rateLimiter.checkRefresh(request.getRemoteAddr());
        var result = auth.refresh(refreshToken).orElseThrow(InvalidRefreshTokenException::new);
        setRefreshCookie(result.refreshToken(), properties.getRefreshTtl(), response);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result.response());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = "kanban_refresh", required = false) String refreshToken,
                                       HttpServletRequest request, HttpServletResponse response) {
        auth.logout(refreshToken);
        setRefreshCookie("", Duration.ZERO, response);
        csrf.saveToken(null, request, response);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private void setAuthCookies(String refreshToken, HttpServletRequest request, HttpServletResponse response) {
        csrf.saveToken(null, request, response);
        setRefreshCookie(refreshToken, properties.getRefreshTtl(), response);
    }

    private void setRefreshCookie(String refreshToken, Duration maxAge, HttpServletResponse response) {
        var refreshCookie = ResponseCookie.from("kanban_refresh", refreshToken)
                .httpOnly(true).secure(properties.isCookieSecure()).sameSite("Lax")
                .path("/api/v1/auth").maxAge(maxAge).build();
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleRateLimit(RateLimitExceededException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.getRetryAfter())).body(
                        new ApiErrorResponse(429, "Too Many Requests", "RATE_LIMITED", exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ApiErrorResponse> handleEmailAlreadyExists(EmailAlreadyExistsException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore()).body(
                new ApiErrorResponse(409, "Conflict", "EMAIL_ALREADY_EXISTS", exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidCredentials(InvalidCredentialsException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).cacheControl(CacheControl.noStore()).body(
                new ApiErrorResponse(401, "Unauthorized", "INVALID_CREDENTIALS", exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidRefreshToken(InvalidRefreshTokenException exception,
                                                                    HttpServletResponse response) {
        setRefreshCookie("", Duration.ZERO, response);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).cacheControl(CacheControl.noStore()).body(
                new ApiErrorResponse(401, "Unauthorized", "REFRESH_INVALID", exception.getMessage(), Instant.now()));
    }
}
