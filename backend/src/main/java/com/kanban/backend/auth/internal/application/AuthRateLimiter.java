package com.kanban.backend.auth.internal.application;

import com.kanban.backend.auth.internal.config.AuthRateLimitProperties;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Service
public class AuthRateLimiter {
    private final Map<Key, Window> counters = new HashMap<>();
    private final AuthRateLimitProperties properties;
    private final Clock clock;

    public AuthRateLimiter(AuthRateLimitProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public void checkRegister(String ip) {
        check(new Rule(new Key("register", ip), properties.getRegisterAttempts(), properties.getRegisterWindow()));
    }

    public void checkLogin(String ip, String normalizedEmail) {
        check(new Rule(new Key("login-ip", ip), properties.getLoginIpAttempts(), properties.getLoginWindow()),
                new Rule(new Key("login-email", normalizedEmail), properties.getLoginEmailAttempts(), properties.getLoginWindow()));
    }

    public void checkRefresh(String ip) {
        check(new Rule(new Key("refresh", ip), properties.getRefreshAttempts(), properties.getRefreshWindow()));
    }

    private synchronized void check(Rule... rules) {
        var now = clock.instant();
        counters.values().removeIf(window -> !window.expiresAt().isAfter(now));
        long retryAfter = 0;
        int newEntries = 0;
        for (var rule : rules) {
            var window = counters.get(rule.key());
            if (window == null) {
                newEntries++;
            } else if (window.attempts() >= rule.limit()) {
                retryAfter = Math.max(retryAfter, secondsUntil(now, window.expiresAt()));
            }
        }
        if (retryAfter > 0) {
            throw new RateLimitExceededException(retryAfter);
        }
        // Keep existing limits intact when memory is full instead of evicting active counters.
        if (counters.size() + newEntries > properties.getMaxEntries()) {
            var nextExpiry = counters.values().stream().map(Window::expiresAt).min(Instant::compareTo).orElseThrow();
            throw new RateLimitExceededException(secondsUntil(now, nextExpiry));
        }
        for (var rule : rules) {
            var window = counters.get(rule.key());
            counters.put(rule.key(), window == null ? new Window(1, now.plus(rule.duration()))
                    : new Window(window.attempts() + 1, window.expiresAt()));
        }
    }

    private long secondsUntil(Instant now, Instant expiry) {
        var remaining = Duration.between(now, expiry);
        return Math.max(1, remaining.getSeconds() + (remaining.getNano() > 0 ? 1 : 0));
    }

    private record Key(String operation, String value) { }
    private record Window(int attempts, Instant expiresAt) { }
    private record Rule(Key key, int limit, Duration duration) { }
}
