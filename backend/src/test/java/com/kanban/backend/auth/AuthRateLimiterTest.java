package com.kanban.backend.auth;

import com.kanban.backend.auth.internal.application.AuthRateLimiter;
import com.kanban.backend.auth.internal.application.RateLimitExceededException;
import com.kanban.backend.auth.internal.config.AuthRateLimitProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

class AuthRateLimiterTest {
    private static final Instant NOW = Instant.parse("2026-10-28T10:00:00Z");
    private final Clock clock = mock(Clock.class);
    private final AuthRateLimitProperties properties = new AuthRateLimitProperties();
    private AuthRateLimiter limiter;

    @BeforeEach
    void setUp() {
        doReturn(NOW).when(clock).instant();
        properties.setRegisterAttempts(1);
        properties.setLoginIpAttempts(2);
        properties.setLoginEmailAttempts(1);
        properties.setRefreshAttempts(1);
        limiter = new AuthRateLimiter(properties, clock);
    }

    @Test
    void separatesOperationsAndClientAddresses() {
        limiter.checkRegister("client-one");
        assertLimited(() -> limiter.checkRegister("client-one"), 3600);
        assertThatCode(() -> limiter.checkRegister("client-two")).doesNotThrowAnyException();
        assertThatCode(() -> limiter.checkRefresh("client-one")).doesNotThrowAnyException();
        assertThatCode(() -> limiter.checkLogin("client-one", "one@example.com")).doesNotThrowAnyException();
    }

    @Test
    void limitsLoginByEmailAcrossAddressesAndByAddressAcrossEmails() {
        limiter.checkLogin("one", "first@example.com");
        assertLimited(() -> limiter.checkLogin("two", "first@example.com"), 900);
        limiter.checkLogin("one", "second@example.com");
        assertLimited(() -> limiter.checkLogin("one", "third@example.com"), 900);
        assertThatCode(() -> limiter.checkLogin("two", "third@example.com")).doesNotThrowAnyException();
    }

    @Test
    void rejectedLoginDoesNotConsumeTheOtherCounter() {
        properties.setLoginIpAttempts(1);
        limiter.checkLogin("one", "first@example.com");
        assertLimited(() -> limiter.checkLogin("two", "first@example.com"), 900);
        assertThatCode(() -> limiter.checkLogin("two", "second@example.com")).doesNotThrowAnyException();
        assertLimited(() -> limiter.checkLogin("two", "third@example.com"), 900);
        assertThatCode(() -> limiter.checkLogin("three", "third@example.com")).doesNotThrowAnyException();
    }

    @Test
    void rejectedRequestsDoNotExtendTheWindowAndRetryAfterRoundsUp() {
        limiter.checkRefresh("one");
        doReturn(NOW.plusMillis(59500)).when(clock).instant();
        assertLimited(() -> limiter.checkRefresh("one"), 1);
        doReturn(NOW.plusSeconds(60)).when(clock).instant();
        assertThatCode(() -> limiter.checkRefresh("one")).doesNotThrowAnyException();
        assertLimited(() -> limiter.checkRefresh("one"), 60);
    }

    @Test
    void usesIndependentConfiguredWindows() {
        properties.setRegisterWindow(Duration.ofSeconds(10));
        properties.setLoginWindow(Duration.ofSeconds(20));
        limiter.checkRegister("one");
        limiter.checkLogin("one", "first@example.com");
        doReturn(NOW.plusSeconds(10)).when(clock).instant();
        assertThatCode(() -> limiter.checkRegister("one")).doesNotThrowAnyException();
        assertLimited(() -> limiter.checkLogin("one", "first@example.com"), 10);
    }

    @Test
    void boundsMemoryWithoutEvictingActiveLimitsAndReclaimsExpiredEntries() {
        properties.setMaxEntries(2);
        limiter.checkRefresh("one");
        limiter.checkRefresh("two");
        assertLimited(() -> limiter.checkRefresh("three"), 60);
        assertLimited(() -> limiter.checkRefresh("one"), 60);
        doReturn(NOW.plusSeconds(60)).when(clock).instant();
        assertThatCode(() -> limiter.checkLogin("three", "first@example.com")).doesNotThrowAnyException();
    }

    @Test
    void doesNotPartiallyAllocateLoginCountersWhenMemoryIsFull() {
        properties.setMaxEntries(2);
        limiter.checkRefresh("one");
        assertLimited(() -> limiter.checkLogin("two", "first@example.com"), 60);
        assertThatCode(() -> limiter.checkRefresh("two")).doesNotThrowAnyException();
    }

    @Test
    void admitsExactlyTheConfiguredNumberOfConcurrentRequests() throws Exception {
        properties.setRegisterAttempts(5);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(12)) {
            var results = IntStream.range(0, 12).mapToObj(index -> executor.submit(() -> {
                start.await();
                try {
                    limiter.checkRegister("one");
                    return true;
                } catch (RateLimitExceededException exception) {
                    return false;
                }
            })).toList();
            start.countDown();
            int admitted = 0;
            for (var result : results) {
                if (result.get(5, TimeUnit.SECONDS)) {
                    admitted++;
                }
            }
            assertThat(admitted).isEqualTo(5);
        }
    }

    private void assertLimited(Runnable request, long retryAfter) {
        assertThatThrownBy(request::run).isInstanceOfSatisfying(RateLimitExceededException.class,
                exception -> assertThat(exception.getRetryAfter()).isEqualTo(retryAfter));
    }
}
