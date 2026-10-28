package com.kanban.backend.auth;

import com.kanban.backend.auth.internal.application.AuthService;
import com.kanban.backend.auth.internal.domain.RefreshToken;
import com.kanban.backend.auth.internal.persistence.RefreshSessionRepository;
import com.kanban.backend.auth.internal.persistence.RefreshTokenRepository;
import com.kanban.backend.auth.internal.persistence.UserRepository;
import com.kanban.backend.auth.internal.security.RefreshTokenService;
import com.kanban.backend.auth.internal.web.dto.AuthResponse;
import com.kanban.backend.auth.internal.web.dto.CsrfResponse;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"app.auth.bcrypt-strength=4", "app.auth.rate-limit.register-attempts=100",
        "app.auth.rate-limit.login-email-attempts=100", "app.auth.rate-limit.login-ip-attempts=100"})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class RefreshTest {
    private static final Instant NOW = Instant.parse("2026-10-28T10:00:00Z");
    private static final String EMAIL = "refresh@example.com";
    private static final String PASSWORD = "correct-password-123";
    private static final Path KEY_DIRECTORY = SecurityConfigTest.createKeys();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.auth.private-key-location", () -> KEY_DIRECTORY.resolve("private.pem").toUri().toString());
        registry.add("app.auth.public-key-location", () -> KEY_DIRECTORY.resolve("public.pem").toUri().toString());
    }

    @Autowired private MockMvc mvc;
    @Autowired private AuthService auth;
    @Autowired private JsonMapper json;
    @Autowired private UserRepository users;
    @Autowired private RefreshSessionRepository sessions;
    @Autowired private JwtDecoder decoder;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private Clock clock;
    @MockitoSpyBean private RefreshTokenService secrets;
    @MockitoSpyBean private RefreshTokenRepository tokens;

    @BeforeEach
    void cleanDatabaseAndSetTime() {
        doReturn(NOW).when(clock).instant();
        tokens.deleteAllInBatch();
        sessions.deleteAllInBatch();
        users.deleteAllInBatch();
    }

    @AfterAll
    static void removeKeys() throws IOException {
        Files.deleteIfExists(KEY_DIRECTORY.resolve("private.pem"));
        Files.deleteIfExists(KEY_DIRECTORY.resolve("public.pem"));
        Files.deleteIfExists(KEY_DIRECTORY);
    }

    @Test
    void rotatesRefreshAndExtendsTheSameSessionEvenWithExpiredAccess() throws Exception {
        var registration = register();
        var original = json.readValue(registration.getContentAsString(), AuthResponse.class);
        var oldCookie = registration.getCookie("kanban_refresh");
        var oldToken = token(oldCookie);
        Instant later = NOW.plus(Duration.ofDays(1));
        doReturn(later).when(clock).instant();
        var response = mvc.perform(refresh(oldCookie)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + original.accessToken()))
                .andExpect(status().isOk()).andReturn().getResponse();
        var body = json.readValue(response.getContentAsString(), AuthResponse.class);
        assertThat(body.user()).isEqualTo(original.user());
        assertThat(body.tokenType()).isEqualTo("Bearer");
        assertThat(body.expiresIn()).isEqualTo(900);
        assertThat(body.accessToken()).isNotEqualTo(original.accessToken());
        var jwt = decoder.decode(body.accessToken());
        assertThat(jwt.getSubject()).isEqualTo(original.user().id().toString());
        assertThat(jwt.getIssuedAt()).isEqualTo(later);
        assertThat(jwt.getExpiresAt()).isEqualTo(later.plusSeconds(body.expiresIn()));
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(response.getHeader(HttpHeaders.LOCATION)).isNull();
        assertThat(response.getCookie("kanban_csrf")).isNull();
        assertThat(response.getCookie("JSESSIONID")).isNull();
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).hasSize(1);

        var nextCookie = response.getCookie("kanban_refresh");
        assertCookie(nextCookie, 604800);
        assertThat(nextCookie.getValue()).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(oldCookie.getValue());
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(nextCookie.getValue().getBytes(StandardCharsets.UTF_8)));
        var next = tokens.findByTokenHash(hash).orElseThrow();
        assertThat(next.getSessionId()).isEqualTo(oldToken.getSessionId());
        assertThat(next.getIssuedAt()).isEqualTo(later);
        assertThat(next.getExpiresAt()).isEqualTo(later.plus(Duration.ofDays(7)));
        assertThat(next.getConsumedAt()).isNull();
        assertThat(tokens.findById(oldToken.getId()).orElseThrow().getConsumedAt()).isEqualTo(later);
        var session = sessions.findById(next.getSessionId()).orElseThrow();
        assertThat(session.getCreatedAt()).isEqualTo(NOW);
        assertThat(session.getLastRotatedAt()).isEqualTo(later);
        assertThat(session.getExpiresAt()).isEqualTo(next.getExpiresAt());
        assertThat(session.getRevokedAt()).isNull();
        assertThat(users.count()).isEqualTo(1);
        assertThat(sessions.count()).isEqualTo(1);
        assertThat(tokens.count()).isEqualTo(2);
        assertThat(response.getContentAsString()).doesNotContain(PASSWORD, oldCookie.getValue(), nextCookie.getValue(), hash,
                "refreshToken", "passwordHash");
        mvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + body.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(original.user().id().toString()));
    }

    @Test
    void replayCommitsRevocationAndDoesNotRevokeAnotherLoginSession() throws Exception {
        var registration = register();
        var original = registration.getCookie("kanban_refresh");
        var originalToken = token(original);
        var other = mvc.perform(authPost("login", null).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", EMAIL, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("kanban_refresh");
        var next = mvc.perform(refresh(original)).andExpect(status().isOk()).andReturn().getResponse()
                .getCookie("kanban_refresh");

        assertInvalid(mvc.perform(refresh(original)).andReturn().getResponse());
        assertThat(sessions.findById(originalToken.getSessionId()).orElseThrow().getRevokedAt()).isEqualTo(NOW);
        assertInvalid(mvc.perform(refresh(next)).andReturn().getResponse());
        mvc.perform(refresh(other)).andExpect(status().isOk());
        var access = json.readValue(registration.getContentAsString(), AuthResponse.class).accessToken();
        mvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andExpect(status().isOk());
        assertThat(tokens.count()).isEqualTo(4);
    }

    @Test
    void expiredConsumedTokenStillRevokesAnActiveFamily() throws Exception {
        var original = register().getCookie("kanban_refresh");
        var sessionId = token(original).getSessionId();
        doReturn(NOW.plus(Duration.ofDays(6))).when(clock).instant();
        var next = mvc.perform(refresh(original)).andExpect(status().isOk()).andReturn().getResponse()
                .getCookie("kanban_refresh");
        Instant later = NOW.plus(Duration.ofDays(8));
        doReturn(later).when(clock).instant();
        assertInvalid(mvc.perform(refresh(original)).andReturn().getResponse());
        assertThat(sessions.findById(sessionId).orElseThrow().getRevokedAt()).isEqualTo(later);
        assertInvalid(mvc.perform(refresh(next)).andReturn().getResponse());
        assertThat(tokens.count()).isEqualTo(2);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "unknown-token"})
    void missingEmptyAndUnknownRefreshAreRejectedAndCookieIsCleared(String value) throws Exception {
        Cookie cookie = value == null ? null : new Cookie("kanban_refresh", value);
        assertInvalid(mvc.perform(refresh(cookie)).andReturn().getResponse());
        assertThat(users.count()).isZero();
        assertThat(sessions.count()).isZero();
        assertThat(tokens.count()).isZero();
    }

    @Test
    void sessionExpiryIsExclusiveAndDoesNotConsumeTheToken() throws Exception {
        var original = register().getCookie("kanban_refresh");
        doReturn(NOW.plus(Duration.ofDays(7))).when(clock).instant();
        assertInvalid(mvc.perform(refresh(original)).andReturn().getResponse());
        assertThat(token(original).getConsumedAt()).isNull();
        assertThat(tokens.count()).isEqualTo(1);
    }

    @Test
    void expiredUnconsumedTokenIsRejectedEvenIfTheSessionIsActive() throws Exception {
        var original = register().getCookie("kanban_refresh");
        var old = token(original);
        jdbc.update("update auth.refresh_sessions set expires_at = expires_at + interval '1 day' where id = ?",
                old.getSessionId());
        doReturn(NOW.plus(Duration.ofDays(7))).when(clock).instant();
        assertInvalid(mvc.perform(refresh(original)).andReturn().getResponse());
        assertThat(token(original).getConsumedAt()).isNull();
        assertThat(sessions.findById(old.getSessionId()).orElseThrow().getRevokedAt()).isNull();
    }

    @Test
    void revokedSessionCannotBeRefreshed() throws Exception {
        var original = register().getCookie("kanban_refresh");
        var session = sessions.findById(token(original).getSessionId()).orElseThrow();
        session.revoke(NOW);
        sessions.saveAndFlush(session);
        assertInvalid(mvc.perform(refresh(original)).andReturn().getResponse());
        assertThat(token(original).getConsumedAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingOrWrongCsrfDoesNotRotateOrRevokeEvenOnReplay(boolean wrongHeader) throws Exception {
        var original = register().getCookie("kanban_refresh");
        mvc.perform(refresh(original)).andExpect(status().isOk());
        var request = wrongHeader ? refresh(original).with(servletRequest -> {
            servletRequest.removeHeader("X-CSRF-TOKEN");
            servletRequest.addHeader("X-CSRF-TOKEN", "wrong-token");
            return servletRequest;
        })
                : post("/api/v1/auth/refresh").cookie(original);
        var response = mvc.perform(request).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID")).andReturn().getResponse();
        assertThat(response.getCookie("kanban_refresh")).isNull();
        assertThat(sessions.findById(token(original).getSessionId()).orElseThrow().getRevokedAt()).isNull();
        assertThat(tokens.count()).isEqualTo(2);
    }

    @Test
    void jsonAndQueryParametersCannotReplaceTheRefreshCookie() throws Exception {
        var original = register().getCookie("kanban_refresh");
        var response = mvc.perform(refresh(null).queryParam("refreshToken", original.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", original.getValue()))))
                .andReturn().getResponse();
        assertInvalid(response);
        assertThat(token(original).getConsumedAt()).isNull();
    }

    @Test
    void databaseFailureAtCommitRollsBackConsumptionAndSessionRotation() throws Exception {
        var original = register().getCookie("kanban_refresh");
        var old = token(original);
        doReturn(NOW.plusSeconds(3600)).when(clock).instant();
        // Force a real UNIQUE hash violation at commit, after the consumed_at UPDATE has been flushed.
        doReturn(original.getValue()).when(secrets).generate();
        var response = mvc.perform(refresh(original)).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR")).andReturn().getResponse();
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
        assertThat(token(original).getConsumedAt()).isNull();
        var session = sessions.findById(old.getSessionId()).orElseThrow();
        assertThat(session.getLastRotatedAt()).isEqualTo(NOW);
        assertThat(session.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(session.getRevokedAt()).isNull();
        assertThat(tokens.count()).isEqualTo(1);
        doCallRealMethod().when(secrets).generate();
        mvc.perform(refresh(original)).andExpect(status().isOk());
        assertThat(tokens.count()).isEqualTo(2);
    }

    @Test
    void concurrentRefreshesSerializeAndTheSecondRevokesTheFamily() throws Exception {
        var original = register().getCookie("kanban_refresh");
        var old = token(original);
        var bothLocated = new CountDownLatch(2);
        // Gate both requests before acquiring the real database lock on this persisted session.
        doAnswer(invocation -> {
            bothLocated.countDown();
            assertThat(bothLocated.await(10, TimeUnit.SECONDS)).isTrue();
            return Optional.of(old.getSessionId());
        }).when(tokens).findSessionIdByTokenHash(old.getTokenHash());
        var first = refresh(original);
        var second = refresh(original);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> mvc.perform(first).andReturn().getResponse());
            var b = executor.submit(() -> mvc.perform(second).andReturn().getResponse());
            var responses = List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
            assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(200, 401);
            var rejected = responses.stream().filter(response -> response.getStatus() == 401).findFirst().orElseThrow();
            assertInvalid(rejected);
            var accepted = responses.stream().filter(response -> response.getStatus() == 200).findFirst().orElseThrow();
            assertThat(sessions.findById(old.getSessionId()).orElseThrow().getRevokedAt()).isNotNull();
            assertThat(tokens.findById(old.getId()).orElseThrow().getConsumedAt()).isNotNull();
            assertThat(tokens.count()).isEqualTo(2);
            assertInvalid(mvc.perform(refresh(accepted.getCookie("kanban_refresh"))).andReturn().getResponse());
        }
    }

    @Test
    void deletedUserCannotRestoreTheirSession() throws Exception {
        var registration = register();
        var userId = json.readValue(registration.getContentAsString(), AuthResponse.class).user().id();
        users.deleteById(userId);
        assertInvalid(mvc.perform(refresh(registration.getCookie("kanban_refresh"))).andReturn().getResponse());
        assertThat(sessions.count()).isZero();
        assertThat(tokens.count()).isZero();
    }

    @Test
    void serviceLogoutRevokesOnlyTheCurrentSessionAndIsIdempotent() throws Exception {
        var registration = register();
        var original = registration.getCookie("kanban_refresh");
        var old = token(original);
        var other = mvc.perform(authPost("login", null).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", EMAIL, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("kanban_refresh");
        Instant loggedOutAt = NOW.plusSeconds(60);
        doReturn(loggedOutAt).when(clock).instant();
        auth.logout(original.getValue());
        doReturn(NOW.plusSeconds(120)).when(clock).instant();
        auth.logout(original.getValue());

        assertThat(sessions.findById(old.getSessionId()).orElseThrow().getRevokedAt()).isEqualTo(loggedOutAt);
        assertThat(token(original).getConsumedAt()).isNull();
        assertThat(tokens.count()).isEqualTo(2);
        assertThat(sessions.count()).isEqualTo(2);
        assertInvalid(mvc.perform(refresh(original)).andReturn().getResponse());
        mvc.perform(refresh(other)).andExpect(status().isOk());
        var access = json.readValue(registration.getContentAsString(), AuthResponse.class).accessToken();
        mvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andExpect(status().isOk());
        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void serviceLogoutWithAnExpiredConsumedTokenRevokesTheCurrentFamily() throws Exception {
        var original = register().getCookie("kanban_refresh");
        var old = token(original);
        doReturn(NOW.plus(Duration.ofDays(6))).when(clock).instant();
        var next = mvc.perform(refresh(original)).andExpect(status().isOk()).andReturn().getResponse()
                .getCookie("kanban_refresh");
        Instant later = NOW.plus(Duration.ofDays(8));
        doReturn(later).when(clock).instant();
        auth.logout(original.getValue());

        assertThat(sessions.findById(old.getSessionId()).orElseThrow().getRevokedAt()).isEqualTo(later);
        assertInvalid(mvc.perform(refresh(next)).andReturn().getResponse());
        assertThat(tokens.count()).isEqualTo(2);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "unknown-token"})
    void serviceLogoutWithoutAKnownTokenDoesNotChangeExistingSessions(String value) throws Exception {
        var original = register().getCookie("kanban_refresh");
        var old = token(original);
        auth.logout(value);

        assertThat(sessions.findById(old.getSessionId()).orElseThrow().getRevokedAt()).isNull();
        assertThat(token(original).getConsumedAt()).isNull();
        assertThat(tokens.count()).isEqualTo(1);
        assertThat(sessions.count()).isEqualTo(1);
    }

    @Test
    void concurrentServiceLogoutAndRefreshLeaveTheFamilyRevoked() throws Exception {
        var original = register().getCookie("kanban_refresh");
        var old = token(original);
        var bothLocated = new CountDownLatch(2);
        doAnswer(invocation -> {
            bothLocated.countDown();
            assertThat(bothLocated.await(10, TimeUnit.SECONDS)).isTrue();
            return Optional.of(old.getSessionId());
        }).when(tokens).findSessionIdByTokenHash(old.getTokenHash());
        var refreshRequest = refresh(original);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var logout = executor.submit(() -> auth.logout(original.getValue()));
            var refreshed = executor.submit(() -> mvc.perform(refreshRequest).andReturn().getResponse());
            logout.get(20, TimeUnit.SECONDS);
            var response = refreshed.get(20, TimeUnit.SECONDS);
            assertThat(response.getStatus()).isIn(200, 401);
            assertThat(sessions.findById(old.getSessionId()).orElseThrow().getRevokedAt()).isEqualTo(NOW);
            if (response.getStatus() == 200) {
                assertInvalid(mvc.perform(refresh(response.getCookie("kanban_refresh"))).andReturn().getResponse());
                assertThat(tokens.count()).isEqualTo(2);
            } else {
                assertInvalid(response);
                assertThat(tokens.count()).isEqualTo(1);
            }
        }
    }

    @Test
    void httpLogoutRevokesTheSessionClearsBothCookiesAndReturnsNoContent() throws Exception {
        var registration = register();
        var original = registration.getCookie("kanban_refresh");
        var old = token(original);
        var other = mvc.perform(authPost("login", null).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", EMAIL, "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("kanban_refresh");
        Instant later = NOW.plusSeconds(60);
        doReturn(later).when(clock).instant();
        var response = mvc.perform(authPost("logout", original)).andExpect(status().isNoContent())
                .andReturn().getResponse();
        assertLoggedOut(response);
        assertThat(sessions.findById(old.getSessionId()).orElseThrow().getRevokedAt()).isEqualTo(later);
        assertThat(tokens.count()).isEqualTo(2);
        assertInvalid(mvc.perform(refresh(original)).andReturn().getResponse());
        mvc.perform(refresh(other)).andExpect(status().isOk());
        var access = json.readValue(registration.getContentAsString(), AuthResponse.class).accessToken();
        mvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "unknown-token"})
    void httpLogoutWithMissingOrUnknownRefreshStillClearsCookies(String value) throws Exception {
        var original = register().getCookie("kanban_refresh");
        Cookie supplied = value == null ? null : new Cookie("kanban_refresh", value);
        var response = mvc.perform(authPost("logout", supplied)).andExpect(status().isNoContent())
                .andReturn().getResponse();
        assertLoggedOut(response);
        assertThat(sessions.findById(token(original).getSessionId()).orElseThrow().getRevokedAt()).isNull();
        assertThat(tokens.count()).isEqualTo(1);
    }

    @Test
    void httpLogoutIsRepeatableWithNewCsrfAndAllowsLoginAgain() throws Exception {
        var original = register().getCookie("kanban_refresh");
        var sessionId = token(original).getSessionId();
        mvc.perform(authPost("logout", original)).andExpect(status().isNoContent());
        doReturn(NOW.plusSeconds(60)).when(clock).instant();
        var response = mvc.perform(authPost("logout", original)).andExpect(status().isNoContent())
                .andReturn().getResponse();
        assertLoggedOut(response);
        assertThat(sessions.findById(sessionId).orElseThrow().getRevokedAt()).isEqualTo(NOW);
        mvc.perform(authPost("login", null).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", EMAIL, "password", PASSWORD))))
                .andExpect(status().isOk());
        assertThat(sessions.count()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void httpLogoutWithMissingOrWrongCsrfDoesNotRevokeOrClearRefresh(boolean wrongHeader) throws Exception {
        var original = register().getCookie("kanban_refresh");
        var request = wrongHeader ? authPost("logout", original).with(servletRequest -> {
            servletRequest.removeHeader("X-CSRF-TOKEN");
            servletRequest.addHeader("X-CSRF-TOKEN", "wrong-token");
            return servletRequest;
        }) : post("/api/v1/auth/logout").cookie(original);
        var response = mvc.perform(request).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID")).andReturn().getResponse();
        assertThat(response.getCookie("kanban_refresh")).isNull();
        assertThat(sessions.findById(token(original).getSessionId()).orElseThrow().getRevokedAt()).isNull();
        assertThat(tokens.count()).isEqualTo(1);
    }

    @Test
    void expiredBearerDoesNotPreventHttpLogout() throws Exception {
        var registration = register();
        var original = registration.getCookie("kanban_refresh");
        var access = json.readValue(registration.getContentAsString(), AuthResponse.class).accessToken();
        doReturn(NOW.plusSeconds(3600)).when(clock).instant();
        var response = mvc.perform(authPost("logout", original).header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andExpect(status().isNoContent()).andReturn().getResponse();
        assertLoggedOut(response);
        assertThat(sessions.findById(token(original).getSessionId()).orElseThrow().getRevokedAt())
                .isEqualTo(NOW.plusSeconds(3600));
    }

    private void assertLoggedOut(MockHttpServletResponse response) {
        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(response.getContentType()).isNull();
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).hasSize(2);
        assertThat(response.getCookie("JSESSIONID")).isNull();
        for (String name : new String[]{"kanban_refresh", "kanban_csrf"}) {
            var cookie = response.getCookie(name);
            assertCookie(cookie, 0);
            assertThat(cookie.getValue()).isEmpty();
        }
    }

    private MockHttpServletResponse register() throws Exception {
        return mvc.perform(authPost("register", null).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", EMAIL, "password", PASSWORD, "displayName", "Олексій"))))
                .andExpect(status().isCreated()).andReturn().getResponse();
    }

    private MockHttpServletRequestBuilder refresh(Cookie cookie) throws Exception {
        return authPost("refresh", cookie);
    }

    private MockHttpServletRequestBuilder authPost(String action, Cookie refresh) throws Exception {
        var response = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        var csrf = json.readValue(response.getContentAsString(), CsrfResponse.class);
        var request = post("/api/v1/auth/" + action).cookie(response.getCookie("kanban_csrf"))
                .header(csrf.headerName(), csrf.token());
        return refresh == null ? request : request.cookie(refresh);
    }

    private RefreshToken token(Cookie cookie) {
        return tokens.findByTokenHash(secrets.hash(cookie.getValue())).orElseThrow();
    }

    private void assertInvalid(MockHttpServletResponse response) throws Exception {
        assertThat(response.getStatus()).isEqualTo(401);
        var body = json.readTree(response.getContentAsString());
        assertThat(body.get("status").asInt()).isEqualTo(401);
        assertThat(body.get("code").asString()).isEqualTo("REFRESH_INVALID");
        assertThat(body.get("timestamp").asString()).isNotEmpty();
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
        assertThat(response.getCookie("kanban_csrf")).isNull();
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).hasSize(1);
        var cleared = response.getCookie("kanban_refresh");
        assertCookie(cleared, 0);
        assertThat(cleared.getValue()).isEmpty();
        assertThat(response.getContentAsString()).doesNotContain(PASSWORD, "passwordHash", "token_hash", "unknown-token");
    }

    private void assertCookie(Cookie cookie, int maxAge) {
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/api/v1/auth");
        assertThat(cookie.getDomain()).isNull();
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
        assertThat(cookie.getMaxAge()).isEqualTo(maxAge);
    }
}
