package com.kanban.backend.auth;

import com.kanban.backend.auth.internal.application.RefreshSessionCleanup;
import com.kanban.backend.auth.internal.config.AuthProperties;
import com.kanban.backend.auth.internal.domain.RefreshSession;
import com.kanban.backend.auth.internal.domain.RefreshToken;
import com.kanban.backend.auth.internal.domain.User;
import com.kanban.backend.auth.internal.persistence.RefreshSessionRepository;
import com.kanban.backend.auth.internal.persistence.RefreshTokenRepository;
import com.kanban.backend.auth.internal.persistence.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(showSql = false, properties = {
        "app.auth.private-key-location=classpath:unused-private.pem",
        "app.auth.public-key-location=classpath:unused-public.pem",
        "app.auth.cleanup-batch-size=2"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({RefreshSessionCleanup.class, RefreshSessionCleanupTest.Config.class})
@Testcontainers
class RefreshSessionCleanupTest {
    private static final Instant NOW = Instant.parse("2026-10-28T10:00:00Z");
    private static final Instant CUTOFF = NOW.minus(Duration.ofDays(7));

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private RefreshSessionCleanup cleanup;
    @Autowired private UserRepository users;
    @Autowired private RefreshSessionRepository sessions;
    @Autowired private RefreshTokenRepository tokens;
    @Autowired private PlatformTransactionManager transactions;
    private User user;

    @BeforeEach
    void cleanDatabase() {
        tokens.deleteAllInBatch();
        sessions.deleteAllInBatch();
        users.deleteAllInBatch();
        user = users.saveAndFlush(new User("cleanup@example.com", "password-hash", "Олексій",
                NOW.minus(Duration.ofDays(40))));
    }

    @Test
    void deletesExpiredAndRevokedSessionsWithAllTokensAndKeepsTheUser() {
        var expired = session(CUTOFF.minusSeconds(1), NOW.minusSeconds(60));
        var revoked = session(NOW.plus(Duration.ofDays(1)), CUTOFF.minusSeconds(1));
        assertThat(tokens.count()).isEqualTo(4);

        assertThat(cleanup.deleteCompletedSessions()).isEqualTo(2);

        assertThat(sessions.findById(expired.getId())).isEmpty();
        assertThat(sessions.findById(revoked.getId())).isEmpty();
        assertThat(tokens.count()).isZero();
        assertThat(users.findById(user.getId())).isPresent();
    }

    @Test
    void keepsTheEntireHistoryOfAnActiveFamilyIncludingExpiredConsumedTokens() {
        var active = session(NOW.plus(Duration.ofDays(1)), null);
        var before = tokens.findAll();
        assertThat(before).anySatisfy(token -> {
            assertThat(token.getExpiresAt()).isBefore(NOW);
            assertThat(token.getConsumedAt()).isNotNull();
        });

        assertThat(cleanup.deleteCompletedSessions()).isZero();

        assertThat(sessions.findById(active.getId())).isPresent();
        assertThat(tokens.findAll()).extracting(RefreshToken::getId)
                .containsExactlyInAnyOrderElementsOf(before.stream().map(RefreshToken::getId).toList());
    }

    @Test
    void deletesSessionsExactlyAtTheRetentionBoundary() {
        session(CUTOFF, null);
        session(NOW.plusSeconds(3600), CUTOFF);

        assertThat(cleanup.deleteCompletedSessions()).isEqualTo(2);
        assertThat(sessions.count()).isZero();
        assertThat(tokens.count()).isZero();
    }

    @Test
    void keepsRecentlyExpiredOrRevokedSessionsUntilTheRetentionHasElapsed() {
        session(CUTOFF.plusNanos(1000), null);
        session(NOW.plusSeconds(3600), CUTOFF.plusNanos(1000));

        assertThat(cleanup.deleteCompletedSessions()).isZero();
        assertThat(sessions.count()).isEqualTo(2);
        assertThat(tokens.count()).isEqualTo(4);
    }

    @Test
    void deletesOnlyOneBoundedBatchPerRunAndCanRunRepeatedly() {
        for (int i = 0; i < 5; i++) {
            session(CUTOFF.minusSeconds(1), null);
        }
        var active = session(NOW.plusSeconds(3600), null);

        assertThat(cleanup.deleteCompletedSessions()).isEqualTo(2);
        assertThat(cleanup.deleteCompletedSessions()).isEqualTo(2);
        assertThat(cleanup.deleteCompletedSessions()).isEqualTo(1);
        assertThat(cleanup.deleteCompletedSessions()).isZero();
        assertThat(sessions.findAll()).extracting(RefreshSession::getId).containsExactly(active.getId());
        assertThat(tokens.count()).isEqualTo(2);
    }

    @Test
    void skipsSessionsLockedByAnotherTransactionAndDeletesThemOnTheNextRun() throws Exception {
        var completed = session(CUTOFF.minusSeconds(1), null);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var transaction = transactions.getTransaction(new DefaultTransactionDefinition());
            try {
                sessions.findByIdForUpdate(completed.getId()).orElseThrow();
                var deleted = executor.submit(cleanup::deleteCompletedSessions);
                assertThat(deleted.get(5, TimeUnit.SECONDS)).isZero();
                assertThat(sessions.findById(completed.getId())).isPresent();
            } finally {
                transactions.rollback(transaction);
            }
        }

        assertThat(cleanup.deleteCompletedSessions()).isEqualTo(1);
        assertThat(tokens.count()).isZero();
    }

    private RefreshSession session(Instant expiresAt, Instant revokedAt) {
        var created = NOW.minus(Duration.ofDays(40));
        var rotated = NOW.minus(Duration.ofDays(10));
        var session = new RefreshSession(user.getId(), created, expiresAt);
        session.rotate(rotated, expiresAt);
        if (revokedAt != null) {
            session.revoke(revokedAt);
        }
        sessions.saveAndFlush(session);
        var old = new RefreshToken(session.getId(), hash(), created, created.plus(Duration.ofDays(7)));
        old.consume(created.plusSeconds(3600));
        tokens.saveAndFlush(old);
        tokens.saveAndFlush(new RefreshToken(session.getId(), hash(), rotated, expiresAt));
        return session;
    }

    private String hash() {
        return UUID.randomUUID().toString().replace("-", "").repeat(2);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        AuthProperties properties() {
            return new AuthProperties();
        }
    }
}
