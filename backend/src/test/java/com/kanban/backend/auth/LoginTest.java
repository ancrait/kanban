package com.kanban.backend.auth;

import com.kanban.backend.auth.internal.domain.User;
import com.kanban.backend.auth.internal.persistence.RefreshSessionRepository;
import com.kanban.backend.auth.internal.persistence.RefreshTokenRepository;
import com.kanban.backend.auth.internal.persistence.UserRepository;
import com.kanban.backend.auth.internal.web.dto.AuthResponse;
import com.kanban.backend.auth.internal.web.dto.CsrfResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
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
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"app.auth.cookie-secure=false", "app.auth.bcrypt-strength=4",
        "app.auth.rate-limit.login-email-attempts=100", "app.auth.rate-limit.login-ip-attempts=100"})
@AutoConfigureMockMvc
@Import(SecurityConfigTest.TestClock.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class LoginTest {
    private static final String EMAIL = "developer@example.com";
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
    @Autowired private JsonMapper json;
    @Autowired private UserRepository users;
    @Autowired private RefreshSessionRepository sessions;
    @Autowired private JwtDecoder decoder;
    @MockitoSpyBean private PasswordEncoder passwords;
    @MockitoSpyBean private RefreshTokenRepository tokens;

    @BeforeEach
    void cleanDatabase() {
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
    void logsInWithNormalizedEmailAndReturnsJwtProfileAndRefreshCookie() throws Exception {
        var user = createUser(EMAIL, PASSWORD);
        var response = mvc.perform(login(" Developer@Example.COM ", PASSWORD))
                .andExpect(status().isOk()).andReturn().getResponse();
        var body = json.readValue(response.getContentAsString(), AuthResponse.class);
        assertThat(body.user().id()).isEqualTo(user.getId());
        assertThat(body.user().email()).isEqualTo(EMAIL);
        assertThat(body.user().displayName()).isEqualTo(user.getDisplayName());
        assertThat(body.user().createdAt()).isEqualTo(user.getCreatedAt());
        assertThat(body.tokenType()).isEqualTo("Bearer");
        assertThat(body.expiresIn()).isEqualTo(900);
        var jwt = decoder.decode(body.accessToken());
        assertThat(jwt.getSubject()).isEqualTo(user.getId().toString());
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).toSeconds()).isEqualTo(body.expiresIn());
        assertThat(response.getHeader(HttpHeaders.LOCATION)).isNull();
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(response.getCookie("JSESSIONID")).isNull();
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).hasSize(2);
        var csrf = response.getCookie("kanban_csrf");
        assertThat(csrf.getMaxAge()).isZero();
        assertThat(csrf.getPath()).isEqualTo("/api/v1/auth");
        assertThat(csrf.isHttpOnly()).isTrue();

        var refresh = response.getCookie("kanban_refresh");
        assertThat(refresh.isHttpOnly()).isTrue();
        assertThat(refresh.getSecure()).isFalse();
        assertThat(refresh.getPath()).isEqualTo("/api/v1/auth");
        assertThat(refresh.getDomain()).isNull();
        assertThat(refresh.getAttribute("SameSite")).isEqualTo("Lax");
        assertThat(refresh.getMaxAge()).isEqualTo(604800);
        assertThat(refresh.getValue()).matches("[A-Za-z0-9_-]{43}");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(refresh.getValue().getBytes(StandardCharsets.UTF_8)));
        var token = tokens.findByTokenHash(hash).orElseThrow();
        var session = sessions.findById(token.getSessionId()).orElseThrow();
        assertThat(session.getUserId()).isEqualTo(user.getId());
        assertThat(session.getExpiresAt()).isEqualTo(session.getCreatedAt().plusSeconds(604800));
        assertThat(session.getLastRotatedAt()).isEqualTo(session.getCreatedAt());
        assertThat(session.getRevokedAt()).isNull();
        assertThat(token.getIssuedAt()).isEqualTo(session.getCreatedAt());
        assertThat(token.getExpiresAt()).isEqualTo(session.getExpiresAt());
        assertThat(token.getConsumedAt()).isNull();
        assertThat(response.getContentAsString()).doesNotContain(PASSWORD, user.getPasswordHash(),
                refresh.getValue(), hash, "refreshToken", "passwordHash");
        assertThat(json.readTree(response.getContentAsString()).size()).isEqualTo(4);
        assertThat(json.readTree(response.getContentAsString()).get("user").size()).isEqualTo(4);
        assertThat(users.findById(user.getId()).orElseThrow().getPasswordHash()).isEqualTo(user.getPasswordHash());
        assertCounts(1, 1);
    }

    @Test
    void returnsTheSameErrorForWrongPasswordAndUnknownEmailAndChecksBothHashes() throws Exception {
        var user = createUser(EMAIL, PASSWORD);
        var wrong = mvc.perform(login(EMAIL, "wrong-password"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse();
        var unknown = mvc.perform(login("unknown@example.com", PASSWORD))
                .andExpect(status().isUnauthorized()).andReturn().getResponse();
        assertError(wrong, "INVALID_CREDENTIALS");
        assertError(unknown, "INVALID_CREDENTIALS");
        assertThat(wrong.getCookie("kanban_csrf")).isNull();
        assertThat(unknown.getCookie("kanban_csrf")).isNull();
        var wrongBody = json.readTree(wrong.getContentAsString());
        var unknownBody = json.readTree(unknown.getContentAsString());
        for (String field : new String[]{"status", "error", "code", "message"}) {
            assertThat(unknownBody.get(field)).isEqualTo(wrongBody.get(field));
        }
        var hashes = ArgumentCaptor.forClass(String.class);
        verify(passwords, times(2)).matches(anyString(), hashes.capture());
        assertThat(hashes.getAllValues().getFirst()).isEqualTo(user.getPasswordHash());
        assertThat(hashes.getAllValues().getLast()).startsWith("$2a$04$").isNotEqualTo(user.getPasswordHash());
        assertCounts(1, 0);
    }

    @Test
    void createsIndependentSessionsForRepeatedLoginsAndOtherUsers() throws Exception {
        var user = createUser(EMAIL, PASSWORD);
        var other = createUser("other@example.com", PASSWORD);
        var first = mvc.perform(login(EMAIL, PASSWORD)).andExpect(status().isOk()).andReturn().getResponse();
        var second = mvc.perform(login(EMAIL, PASSWORD).cookie(first.getCookie("kanban_refresh")))
                .andExpect(status().isOk()).andReturn().getResponse();
        var third = mvc.perform(login(other.getEmail(), PASSWORD)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(second.getCookie("kanban_refresh").getValue()).isNotEqualTo(first.getCookie("kanban_refresh").getValue());
        var thirdBody = json.readValue(third.getContentAsString(), AuthResponse.class);
        assertThat(thirdBody.user().id()).isEqualTo(other.getId());
        assertThat(decoder.decode(thirdBody.accessToken()).getSubject()).isEqualTo(other.getId().toString());
        assertThat(sessions.findAll()).extracting(session -> session.getUserId())
                .containsExactlyInAnyOrder(user.getId(), user.getId(), other.getId());
        assertThat(sessions.findAll()).allSatisfy(session -> assertThat(session.getRevokedAt()).isNull());
        assertThat(tokens.findAll()).allSatisfy(token -> assertThat(token.getConsumedAt()).isNull());
        assertCounts(2, 3);
    }

    @Test
    void acceptsShortExistingPasswordsAndDoesNotTrimPasswords() throws Exception {
        createUser(EMAIL, "short");
        createUser("spaces@example.com", " " + PASSWORD + " ");
        mvc.perform(login(EMAIL, "short")).andExpect(status().isOk());
        mvc.perform(login("spaces@example.com", " " + PASSWORD + " ")).andExpect(status().isOk());
        var response = mvc.perform(login("spaces@example.com", PASSWORD))
                .andExpect(status().isUnauthorized()).andReturn().getResponse();
        assertError(response, "INVALID_CREDENTIALS");
        assertCounts(2, 2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"email\":\"bad\",\"password\":\"present\"}",
            "{\"email\":\"developer@example.com\",\"password\":\" \"}",
            "{\"email\":\"developer@example.com\",\"password\":null}"})
    void rejectsInvalidFieldsBeforeCheckingPasswords(String body) throws Exception {
        var response = mvc.perform(withCsrf(body)).andExpect(status().isBadRequest()).andReturn().getResponse();
        assertError(response, "VALIDATION_FAILED");
        verify(passwords, never()).matches(anyString(), anyString());
        assertCounts(0, 0);
    }

    @Test
    void rejectsPasswordsOverTheBcryptByteLimit() throws Exception {
        String body = json.writeValueAsString(Map.of("email", EMAIL, "password", "я".repeat(37)));
        var response = mvc.perform(withCsrf(body)).andExpect(status().isBadRequest()).andReturn().getResponse();
        assertError(response, "INVALID_REQUEST");
        verify(passwords, never()).matches(anyString(), anyString());
        assertCounts(0, 0);
    }

    @Test
    void requiresCsrfBeforeCheckingCredentials() throws Exception {
        createUser(EMAIL, PASSWORD);
        var response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", EMAIL, "password", PASSWORD))))
                .andExpect(status().isForbidden()).andReturn().getResponse();
        assertError(response, "CSRF_INVALID");
        verify(passwords, never()).matches(anyString(), anyString());
        assertCounts(1, 0);
    }

    @Test
    void acceptsNewCsrfAfterLoginEvenWithAnInvalidBearer() throws Exception {
        createUser(EMAIL, PASSWORD);
        var first = mvc.perform(login(EMAIL, PASSWORD)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(first.getCookie("kanban_csrf").getMaxAge()).isZero();
        mvc.perform(login(EMAIL, PASSWORD).header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
                .andExpect(status().isOk());
        assertCounts(1, 2);
    }

    @Test
    void rollsBackTheNewSessionWhenSavingTheTokenFails() throws Exception {
        createUser(EMAIL, PASSWORD);
        mvc.perform(login(EMAIL, PASSWORD)).andExpect(status().isOk());
        var originalSession = sessions.findAll().getFirst();
        doThrow(new DataIntegrityViolationException("test login token failure")).when(tokens).save(any());
        var response = mvc.perform(login(EMAIL, PASSWORD)).andExpect(status().isInternalServerError())
                .andReturn().getResponse();
        assertError(response, "INTERNAL_ERROR");
        assertThat(response.getCookie("kanban_csrf")).isNull();
        assertThat(response.getContentAsString()).doesNotContain("test login token failure");
        assertThat(sessions.findById(originalSession.getId()).orElseThrow().getRevokedAt()).isNull();
        assertCounts(1, 1);
    }

    private User createUser(String email, String password) {
        return users.saveAndFlush(new User(email, passwords.encode(password), "Олексій",
                Instant.parse("2026-10-25T09:00:00Z")));
    }

    private MockHttpServletRequestBuilder login(String email, String password) throws Exception {
        return withCsrf(json.writeValueAsString(Map.of("email", email, "password", password)));
    }

    private MockHttpServletRequestBuilder withCsrf(String body) throws Exception {
        var response = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        var csrf = json.readValue(response.getContentAsString(), CsrfResponse.class);
        return post("/api/v1/auth/login").cookie(response.getCookie("kanban_csrf"))
                .header(csrf.headerName(), csrf.token()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void assertError(MockHttpServletResponse response, String code) throws Exception {
        var body = json.readTree(response.getContentAsString());
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(body.get("status").asInt()).isEqualTo(response.getStatus());
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(response.getCookie("kanban_refresh")).isNull();
        assertThat(response.getContentAsString()).doesNotContain(PASSWORD, "passwordHash", "token_hash");
    }

    private void assertCounts(long userCount, long sessionCount) {
        assertThat(users.count()).isEqualTo(userCount);
        assertThat(sessions.count()).isEqualTo(sessionCount);
        assertThat(tokens.count()).isEqualTo(sessionCount);
    }
}
