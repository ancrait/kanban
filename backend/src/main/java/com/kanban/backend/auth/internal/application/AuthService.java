package com.kanban.backend.auth.internal.application;

import com.kanban.backend.auth.internal.config.AuthProperties;
import com.kanban.backend.auth.internal.domain.RefreshSession;
import com.kanban.backend.auth.internal.domain.RefreshToken;
import com.kanban.backend.auth.internal.domain.User;
import com.kanban.backend.auth.internal.persistence.RefreshSessionRepository;
import com.kanban.backend.auth.internal.persistence.RefreshTokenRepository;
import com.kanban.backend.auth.internal.persistence.UserRepository;
import com.kanban.backend.auth.internal.security.JwtService;
import com.kanban.backend.auth.internal.security.RefreshTokenService;
import com.kanban.backend.auth.internal.web.dto.AuthResponse;
import com.kanban.backend.auth.internal.web.dto.LoginRequest;
import com.kanban.backend.auth.internal.web.dto.RegisterRequest;
import com.kanban.backend.auth.internal.web.dto.UserResponse;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {
    private final UserRepository users;
    private final RefreshSessionRepository sessions;
    private final RefreshTokenRepository tokens;
    private final PasswordEncoder passwords;
    private final JwtService jwt;
    private final RefreshTokenService refreshTokens;
    private final AuthProperties properties;
    private final Clock clock;
    private final String dummyPasswordHash;

    public AuthService(UserRepository users, RefreshSessionRepository sessions, RefreshTokenRepository tokens,
                       PasswordEncoder passwords, JwtService jwt, RefreshTokenService refreshTokens,
                       AuthProperties properties, Clock clock) {
        this.users = users;
        this.sessions = sessions;
        this.tokens = tokens;
        this.passwords = passwords;
        this.jwt = jwt;
        this.refreshTokens = refreshTokens;
        this.properties = properties;
        this.clock = clock;
        this.dummyPasswordHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public AuthResult register(RegisterRequest request) {
        if (users.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException();
        }

        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var user = new User(request.email(), passwords.encode(request.password()), request.displayName(), now);
        try {
            // Flush here so a concurrent email conflict is translated before the transaction returns.
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            if (exception.getCause() instanceof ConstraintViolationException violation
                    && "uk_users_email".equals(violation.getConstraintName())) {
                throw new EmailAlreadyExistsException();
            }
            throw exception;
        }

        return createSession(user);
    }

    @Transactional
    public AuthResult login(LoginRequest request) {
        var user = users.findByEmail(request.email()).orElse(null);
        // Check BCrypt even for an unknown email to avoid a quick account-existence check.
        String hash = user == null ? dummyPasswordHash : user.getPasswordHash();
        boolean passwordMatches = passwords.matches(request.password(), hash);
        if (user == null || !passwordMatches) {
            throw new InvalidCredentialsException();
        }
        return createSession(user);
    }

    @Transactional
    public Optional<AuthResult> refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return Optional.empty();
        }
        String hash = refreshTokens.hash(refreshToken);
        var sessionId = tokens.findSessionIdByTokenHash(hash).orElse(null);
        if (sessionId == null) {
            return Optional.empty();
        }
        var session = sessions.findByIdForUpdate(sessionId).orElse(null);
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (session == null || session.getRevokedAt() != null || !session.getExpiresAt().isAfter(now)) {
            return Optional.empty();
        }

        // Read the token after acquiring the session lock, including consumed and expired history.
        var token = tokens.findByTokenHash(hash).orElse(null);
        if (token == null) {
            return Optional.empty();
        }
        if (token.getConsumedAt() != null) {
            session.revoke(now);
            // Return normally so the revocation commits before the controller sends 401.
            return Optional.empty();
        }
        if (!token.getExpiresAt().isAfter(now)) {
            return Optional.empty();
        }
        var user = users.findById(session.getUserId()).orElse(null);
        if (user == null) {
            return Optional.empty();
        }

        token.consume(now);
        tokens.flush(); // Release the partial UNIQUE slot before inserting the next unconsumed token.
        var expiresAt = now.plus(properties.getRefreshTtl());
        session.rotate(now, expiresAt);
        String nextToken = refreshTokens.generate();
        tokens.save(new RefreshToken(session.getId(), refreshTokens.hash(nextToken), now, expiresAt));
        return Optional.of(createAuthResult(user, nextToken));
    }

    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        var sessionId = tokens.findSessionIdByTokenHash(refreshTokens.hash(refreshToken)).orElse(null);
        if (sessionId == null) {
            return;
        }
        sessions.findByIdForUpdate(sessionId)
                .ifPresent(session -> session.revoke(clock.instant().truncatedTo(ChronoUnit.MICROS)));
    }

    @Transactional(readOnly = true)
    public UserResponse getCurrentUser(UUID userId) {
        var user = users.findById(userId).orElseThrow(InvalidAccessTokenException::new);
        return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getCreatedAt());
    }

    private AuthResult createSession(User user) {
        var now = clock.instant();
        var expiresAt = now.plus(properties.getRefreshTtl());
        var session = sessions.save(new RefreshSession(user.getId(), now, expiresAt));
        String refreshToken = refreshTokens.generate();
        tokens.save(new RefreshToken(session.getId(), refreshTokens.hash(refreshToken), now, expiresAt));

        return createAuthResult(user, refreshToken);
    }

    private AuthResult createAuthResult(User user, String refreshToken) {
        var profile = new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(), user.getCreatedAt());
        var response = new AuthResponse(jwt.createAccessToken(user.getId()), "Bearer",
                properties.getAccessTtl().toSeconds(), profile);
        return new AuthResult(response, refreshToken);
    }

    public record AuthResult(AuthResponse response, String refreshToken) {
    }
}
