package com.kanban.backend.auth;

import com.kanban.backend.auth.internal.domain.RefreshSession;
import com.kanban.backend.auth.internal.domain.RefreshToken;
import com.kanban.backend.auth.internal.domain.User;
import com.kanban.backend.auth.internal.persistence.RefreshSessionRepository;
import com.kanban.backend.auth.internal.persistence.RefreshTokenRepository;
import com.kanban.backend.auth.internal.persistence.UserRepository;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(showSql = false, properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class AuthPersistenceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-24T12:00:00Z");
    private static final Instant EXPIRES_AT = CREATED_AT.plus(Duration.ofDays(7));
    private static final String PASSWORD_HASH = "$2a$10$" + "a".repeat(53);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshSessionRepository sessionRepository;

    @Autowired
    private RefreshTokenRepository tokenRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void findsUserByEmailAndReturnsEmptyForUnknownEmail() {
        User user = persistUser("developer@example.com", "Олексій");
        entityManager.clear();

        assertThat(userRepository.findByEmail("developer@example.com"))
                .map(User::getId).contains(user.getId());
        assertThat(userRepository.findByEmail("unknown@example.com")).isEmpty();
    }

    @Test
    void findsConsumedTokenHistoryAndCurrentTokenByHash() {
        RefreshSession session = persistSession(persistUser("developer@example.com", "Олексій").getId());
        RefreshToken previousToken = persistToken(session.getId(), "a".repeat(64), CREATED_AT, EXPIRES_AT);
        previousToken.consume(CREATED_AT.plusSeconds(1));
        tokenRepository.flush();
        RefreshToken currentToken = persistToken(session.getId(), "b".repeat(64), CREATED_AT, EXPIRES_AT);
        entityManager.clear();

        assertThat(tokenRepository.findByTokenHash("a".repeat(64)))
                .map(RefreshToken::getConsumedAt).contains(CREATED_AT.plusSeconds(1));
        assertThat(tokenRepository.findByTokenHash("b".repeat(64)))
                .map(RefreshToken::getId).contains(currentToken.getId());
        assertThat(tokenRepository.findByTokenHash("c".repeat(64))).isEmpty();
    }

    @Test
    void findsSessionForUpdateAndReturnsEmptyForUnknownId() {
        RefreshSession session = persistSession(persistUser("developer@example.com", "Олексій").getId());
        entityManager.clear();

        assertThat(sessionRepository.findByIdForUpdate(session.getId()))
                .map(RefreshSession::getId).contains(session.getId());
        assertThat(sessionRepository.findByIdForUpdate(UUID.randomUUID())).isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void locksSessionUntilCallerTransactionCompletes() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        User user = transaction.execute(status -> persistUser("locking@example.com", "Олексій"));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            RefreshSession session = transaction.execute(status -> persistSession(user.getId()));
            transaction.executeWithoutResult(status -> {
                sessionRepository.findByIdForUpdate(session.getId()).orElseThrow();
                Future<?> competingLock = executor.submit(() -> transaction.executeWithoutResult(other -> {
                    jdbcTemplate.execute("SET LOCAL lock_timeout = '500ms'");
                    sessionRepository.findByIdForUpdate(session.getId()).orElseThrow();
                }));

                assertThatThrownBy(() -> competingLock.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(PessimisticLockingFailureException.class);
            });

            transaction.executeWithoutResult(status -> {
                RefreshSession unlockedSession = sessionRepository.findByIdForUpdate(session.getId()).orElseThrow();
                unlockedSession.revoke(CREATED_AT.plusSeconds(1));
            });
            assertThat(sessionRepository.findById(session.getId()))
                    .map(RefreshSession::getRevokedAt).contains(CREATED_AT.plusSeconds(1));
        } finally {
            executor.shutdownNow();
            transaction.executeWithoutResult(status -> userRepository.deleteById(user.getId()));
        }
    }

    @Test
    void migratesEmptyDatabaseAndDoesNotRepeatMigration() {
        assertThat(flyway.info().applied()).hasSize(1);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void persistsAllEntitiesAndTheirUtcTimestamps() {
        User user = persistUser("developer@example.com", "Олексій");
        RefreshSession session = persistSession(user.getId());
        RefreshToken token = persistToken(session.getId(), "a".repeat(64), CREATED_AT, EXPIRES_AT);
        entityManager.clear();

        User storedUser = entityManager.find(User.class, user.getId());
        RefreshSession storedSession = entityManager.find(RefreshSession.class, session.getId());
        RefreshToken storedToken = entityManager.find(RefreshToken.class, token.getId());

        assertThat(storedUser.getId()).isNotNull();
        assertThat(storedUser.getEmail()).isEqualTo("developer@example.com");
        assertThat(storedUser.getPasswordHash()).isEqualTo(PASSWORD_HASH);
        assertThat(storedUser.getDisplayName()).isEqualTo("Олексій");
        assertThat(storedUser.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(storedSession.getUserId()).isEqualTo(user.getId());
        assertThat(storedSession.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(storedSession.getLastRotatedAt()).isEqualTo(CREATED_AT);
        assertThat(storedSession.getExpiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(storedSession.getRevokedAt()).isNull();
        assertThat(storedToken.getSessionId()).isEqualTo(session.getId());
        assertThat(storedToken.getTokenHash()).isEqualTo("a".repeat(64));
        assertThat(storedToken.getIssuedAt()).isEqualTo(CREATED_AT);
        assertThat(storedToken.getExpiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(storedToken.getConsumedAt()).isNull();
    }

    @Test
    void acceptsDisplayNameWithFiftyUnicodeCodePoints() {
        String displayName = "\uD83D\uDE00".repeat(50);
        User user = persistUser("developer@example.com", displayName);
        entityManager.clear();

        assertThat(entityManager.find(User.class, user.getId()).getDisplayName()).isEqualTo(displayName);
    }

    @Test
    void rejectsDuplicateEmail() {
        persistUser("developer@example.com", "Олексій");

        assertRejected("uk_users_email", """
                INSERT INTO auth.users (id, email, password_hash, display_name, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), "developer@example.com", PASSWORD_HASH, "Ірина", CREATED_AT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Developer@example.com", " developer@example.com ", ""})
    void rejectsUnnormalizedOrEmptyEmail(String email) {
        assertRejected("chk_users_email_normalized", """
                INSERT INTO auth.users (id, email, password_hash, display_name, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), email, PASSWORD_HASH, "Олексій", CREATED_AT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"A", "  ", " Team "})
    void rejectsInvalidDisplayName(String displayName) {
        assertRejected("chk_users_display_name", """
                INSERT INTO auth.users (id, email, password_hash, display_name, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), "developer@example.com", PASSWORD_HASH, displayName, CREATED_AT);
    }

    @Test
    void rejectsSessionWithoutExistingUser() {
        assertRejected("fk_refresh_sessions_user", """
                INSERT INTO auth.refresh_sessions (id, user_id, created_at, last_rotated_at, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), UUID.randomUUID(), CREATED_AT, CREATED_AT, EXPIRES_AT);
    }

    @Test
    void rejectsTokenWithoutExistingSession() {
        assertRejected("fk_refresh_tokens_session", """
                INSERT INTO auth.refresh_tokens (id, session_id, token_hash, issued_at, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64), CREATED_AT, EXPIRES_AT);
    }

    @Test
    void rejectsDuplicateTokenHashAcrossSessions() {
        User user = persistUser("developer@example.com", "Олексій");
        RefreshSession firstSession = persistSession(user.getId());
        RefreshSession secondSession = persistSession(user.getId());
        persistToken(firstSession.getId(), "a".repeat(64), CREATED_AT, EXPIRES_AT);

        assertRejected("uk_refresh_tokens_hash", """
                INSERT INTO auth.refresh_tokens (id, session_id, token_hash, issued_at, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), secondSession.getId(), "a".repeat(64), CREATED_AT, EXPIRES_AT);
    }

    @Test
    void rejectsTwoUnconsumedTokensInOneSession() {
        RefreshSession session = persistSession(persistUser("developer@example.com", "Олексій").getId());
        persistToken(session.getId(), "a".repeat(64), CREATED_AT, EXPIRES_AT);

        assertRejected("uk_refresh_tokens_unconsumed_session", """
                INSERT INTO auth.refresh_tokens (id, session_id, token_hash, issued_at, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), session.getId(), "b".repeat(64), CREATED_AT, EXPIRES_AT);
    }

    @Test
    void keepsConsumedTokenHistoryWhenSessionIsRotatedAndRevoked() {
        RefreshSession session = persistSession(persistUser("developer@example.com", "Олексій").getId());
        RefreshToken previousToken = persistToken(session.getId(), "a".repeat(64), CREATED_AT, EXPIRES_AT);
        Instant rotatedAt = CREATED_AT.plus(Duration.ofHours(1));
        Instant nextExpiry = rotatedAt.plus(Duration.ofDays(7));
        previousToken.consume(rotatedAt);
        session.rotate(rotatedAt, nextExpiry);
        // Release the partial unique index entry before inserting the successor.
        entityManager.flush();
        RefreshToken nextToken = persistToken(session.getId(), "b".repeat(64), rotatedAt, nextExpiry);
        Instant revokedAt = rotatedAt.plusSeconds(1);
        session.revoke(revokedAt);
        session.revoke(revokedAt.plusSeconds(1));
        entityManager.flush();
        entityManager.clear();

        RefreshSession storedSession = entityManager.find(RefreshSession.class, session.getId());
        assertThat(storedSession.getLastRotatedAt()).isEqualTo(rotatedAt);
        assertThat(storedSession.getExpiresAt()).isEqualTo(nextExpiry);
        assertThat(storedSession.getRevokedAt()).isEqualTo(revokedAt);
        assertThat(entityManager.find(RefreshToken.class, previousToken.getId()).getConsumedAt())
                .isEqualTo(rotatedAt);
        assertThat(entityManager.find(RefreshToken.class, nextToken.getId()).getConsumedAt()).isNull();
        assertThatThrownBy(() -> storedSession.rotate(revokedAt, nextExpiry))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsRawRefreshSecretInsteadOfSha256Hash() {
        RefreshSession session = persistSession(persistUser("developer@example.com", "Олексій").getId());

        assertRejected("chk_refresh_tokens_hash", """
                INSERT INTO auth.refresh_tokens (id, session_id, token_hash, issued_at, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), session.getId(), "opaque-refresh-secret", CREATED_AT, EXPIRES_AT);
    }

    @Test
    void rejectsSessionThatExpiresBeforeItsLastRotation() {
        User user = persistUser("developer@example.com", "Олексій");

        assertRejected("chk_refresh_sessions_expiry", """
                INSERT INTO auth.refresh_sessions (id, user_id, created_at, last_rotated_at, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), user.getId(), CREATED_AT, CREATED_AT, CREATED_AT.minusSeconds(1));
    }

    @Test
    void rejectsConsumptionBeforeTokenWasIssued() {
        RefreshSession session = persistSession(persistUser("developer@example.com", "Олексій").getId());

        assertRejected("chk_refresh_tokens_consumption_time", """
                INSERT INTO auth.refresh_tokens (id, session_id, token_hash, issued_at, expires_at, consumed_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), session.getId(), "a".repeat(64), CREATED_AT, EXPIRES_AT,
                CREATED_AT.minusSeconds(1));
    }

    @Test
    void deletesTokenHistoryWhenCompletedSessionIsDeleted() {
        RefreshSession session = persistSession(persistUser("developer@example.com", "Олексій").getId());
        RefreshToken token = persistToken(session.getId(), "a".repeat(64), CREATED_AT, EXPIRES_AT);
        session.revoke(CREATED_AT.plusSeconds(1));
        entityManager.flush();

        jdbcTemplate.update("DELETE FROM auth.refresh_sessions WHERE id = ?", session.getId());
        entityManager.clear();

        assertThat(entityManager.find(RefreshToken.class, token.getId())).isNull();
    }

    private User persistUser(String email, String displayName) {
        User user = new User(email, PASSWORD_HASH, displayName, CREATED_AT);
        return userRepository.saveAndFlush(user);
    }

    private RefreshSession persistSession(UUID userId) {
        RefreshSession session = new RefreshSession(userId, CREATED_AT, EXPIRES_AT);
        return sessionRepository.saveAndFlush(session);
    }

    private RefreshToken persistToken(UUID sessionId, String hash, Instant issuedAt, Instant expiresAt) {
        RefreshToken token = new RefreshToken(sessionId, hash, issuedAt, expiresAt);
        return tokenRepository.saveAndFlush(token);
    }

    private void assertRejected(String constraint, String sql, Object... arguments) {
        Object[] jdbcArguments = new Object[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            jdbcArguments[i] = arguments[i] instanceof Instant instant
                    ? instant.atOffset(java.time.ZoneOffset.UTC) : arguments[i];
        }
        assertThatThrownBy(() -> jdbcTemplate.update(sql, jdbcArguments))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }
}
