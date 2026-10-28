package com.kanban.backend.auth;

import com.kanban.backend.auth.internal.persistence.RefreshSessionRepository;
import com.kanban.backend.auth.internal.persistence.RefreshTokenRepository;
import com.kanban.backend.auth.internal.persistence.UserRepository;
import com.kanban.backend.auth.internal.web.dto.AuthResponse;
import com.kanban.backend.auth.internal.web.dto.CsrfResponse;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"app.auth.cookie-secure=false", "app.auth.bcrypt-strength=4",
        "app.auth.rate-limit.register-attempts=100"})
@AutoConfigureMockMvc
@Import(SecurityConfigTest.TestClock.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class RegistrationTest {
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
    void registersUserWithHashedPasswordJwtAndHashedRefreshToken() throws Exception {
        var result = mvc.perform(register("  Developer@Example.COM  ", "  Олексій  "))
                .andExpect(status().isCreated()).andReturn();
        var response = result.getResponse();
        var body = json.readValue(response.getContentAsString(), AuthResponse.class);
        var user = users.findByEmail("developer@example.com").orElseThrow();
        assertThat(user.getDisplayName()).isEqualTo("Олексій");
        assertThat(user.getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(passwords.matches(PASSWORD, user.getPasswordHash())).isTrue();
        assertThat(body.user().id()).isEqualTo(user.getId());
        assertThat(body.user().email()).isEqualTo(user.getEmail());
        assertThat(body.user().displayName()).isEqualTo(user.getDisplayName());
        assertThat(body.user().createdAt()).isEqualTo(user.getCreatedAt());
        assertThat(body.tokenType()).isEqualTo("Bearer");
        assertThat(body.expiresIn()).isEqualTo(900);
        var jwt = decoder.decode(body.accessToken());
        assertThat(jwt.getSubject()).isEqualTo(user.getId().toString());
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).toSeconds()).isEqualTo(body.expiresIn());
        assertThat(response.getHeader(HttpHeaders.LOCATION)).isEqualTo("/api/v1/users/me");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(response.getCookie("JSESSIONID")).isNull();

        var cookie = response.getCookie("kanban_refresh");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isFalse();
        assertThat(cookie.getPath()).isEqualTo("/api/v1/auth");
        assertThat(cookie.getDomain()).isNull();
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
        assertThat(cookie.getMaxAge()).isEqualTo(604800);
        assertThat(cookie.getValue()).matches("[A-Za-z0-9_-]{43}");
        assertThat(Base64.getUrlDecoder().decode(cookie.getValue())).hasSize(32);
        String expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(cookie.getValue().getBytes(StandardCharsets.UTF_8)));
        var token = tokens.findByTokenHash(expectedHash).orElseThrow();
        var session = sessions.findById(token.getSessionId()).orElseThrow();
        assertThat(session.getUserId()).isEqualTo(user.getId());
        assertThat(session.getCreatedAt()).isEqualTo(user.getCreatedAt());
        assertThat(session.getLastRotatedAt()).isEqualTo(session.getCreatedAt());
        assertThat(session.getExpiresAt()).isEqualTo(session.getCreatedAt().plusSeconds(604800));
        assertThat(session.getRevokedAt()).isNull();
        assertThat(token.getIssuedAt()).isEqualTo(session.getCreatedAt());
        assertThat(token.getExpiresAt()).isEqualTo(session.getExpiresAt());
        assertThat(token.getConsumedAt()).isNull();
        assertThat(response.getContentAsString()).doesNotContain(PASSWORD, user.getPasswordHash(),
                cookie.getValue(), expectedHash, "refreshToken", "passwordHash");
        assertThat(json.readTree(response.getContentAsString()).size()).isEqualTo(4);
        assertThat(json.readTree(response.getContentAsString()).get("user").size()).isEqualTo(4);
        assertCounts(1);
    }

    @Test
    void resetsCsrfAfterRegistrationAndAcceptsANewToken() throws Exception {
        var csrfResponse = mvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk()).andReturn().getResponse();
        var csrf = json.readValue(csrfResponse.getContentAsString(), CsrfResponse.class);
        var response = mvc.perform(post("/api/v1/auth/register")
                        .cookie(csrfResponse.getCookie("kanban_csrf")).header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content(body("first@example.com", "Олексій")))
                .andExpect(status().isCreated()).andReturn().getResponse();
        var cleared = response.getCookie("kanban_csrf");
        assertThat(cleared).isNotNull();
        assertThat(cleared.getMaxAge()).isZero();
        assertThat(cleared.getPath()).isEqualTo("/api/v1/auth");
        assertThat(cleared.isHttpOnly()).isTrue();
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).hasSize(2);
        // The old header without its deleted matching cookie cannot authenticate another POST.
        mvc.perform(post("/api/v1/auth/register").header(csrf.headerName(), csrf.token())
                        .contentType(MediaType.APPLICATION_JSON).content(body("second@example.com", "Олексій")))
                .andExpect(status().isForbidden());
        var second = mvc.perform(register("second@example.com", "Олексій"))
                .andExpect(status().isCreated()).andReturn().getResponse();
        assertThat(second.getCookie("kanban_refresh").getValue())
                .isNotEqualTo(response.getCookie("kanban_refresh").getValue());
        assertCounts(2);
    }

    @Test
    void returnsConflictForAnExistingNormalizedEmailWithoutCreatingAnotherSession() throws Exception {
        mvc.perform(register("developer@example.com", "Олексій")).andExpect(status().isCreated());
        var response = mvc.perform(register(" Developer@Example.COM ", "Інший"))
                .andExpect(status().isConflict()).andReturn().getResponse();
        assertError(response, "EMAIL_ALREADY_EXISTS");
        assertThat(response.getCookie("kanban_refresh")).isNull();
        assertThat(response.getCookie("kanban_csrf")).isNull();
        assertCounts(1);
    }

    @Test
    void onlyOneConcurrentRegistrationWithTheSameNormalizedEmailSucceeds() throws Exception {
        // Both requests pass the pre-check before either INSERT; the database must decide the winner.
        var bothEncoding = new CountDownLatch(2);
        doAnswer(invocation -> {
            bothEncoding.countDown();
            assertThat(bothEncoding.await(10, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(passwords).encode(anyString());
        var first = register("Developer@Example.COM", "Перший");
        var second = register(" developer@example.com ", "Другий");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> mvc.perform(first).andReturn().getResponse());
            var b = executor.submit(() -> mvc.perform(second).andReturn().getResponse());
            var responses = List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
            assertThat(responses).extracting(MockHttpServletResponse::getStatus)
                    .containsExactlyInAnyOrder(201, 409);
            var conflict = responses.stream().filter(response -> response.getStatus() == 409).findFirst().orElseThrow();
            assertError(conflict, "EMAIL_ALREADY_EXISTS");
            assertThat(conflict.getCookie("kanban_refresh")).isNull();
        }
        assertCounts(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"email\":\"bad\",\"password\":\"correct-password-123\",\"displayName\":\"Олексій\"}",
            "{\"email\":\"valid@example.com\",\"password\":\"short\",\"displayName\":\"Олексій\"}",
            "{\"email\":\"valid@example.com\",\"password\":\"correct-password-123\",\"displayName\":\" \"}",
            "{}"
    })
    void rejectsInvalidFieldsBeforeWritingAnything(String body) throws Exception {
        var response = mvc.perform(withCsrf(body)).andExpect(status().isBadRequest()).andReturn().getResponse();
        assertError(response, "VALIDATION_FAILED");
        assertThat(response.getCookie("kanban_refresh")).isNull();
        assertCounts(0);
    }

    @Test
    void rejectsUnknownFieldsBeforeWritingAnything() throws Exception {
        var body = "{\"email\":\"valid@example.com\",\"password\":\"correct-password-123\","
                + "\"displayName\":\"Олексій\",\"role\":\"ADMIN\"}";
        var response = mvc.perform(withCsrf(body)).andExpect(status().isBadRequest()).andReturn().getResponse();
        assertError(response, "INVALID_REQUEST");
        assertCounts(0);
    }

    @Test
    void requiresCsrfBeforeWritingAnything() throws Exception {
        var response = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body("valid@example.com", "Олексій")))
                .andExpect(status().isForbidden()).andReturn().getResponse();
        assertError(response, "CSRF_INVALID");
        assertCounts(0);
    }

    @Test
    void rollsBackUserAndSessionIfSavingTheRefreshTokenFails() throws Exception {
        doThrow(new DataIntegrityViolationException("test refresh write failure"))
                .when(tokens).save(any());
        var response = mvc.perform(register("valid@example.com", "Олексій"))
                .andExpect(status().isInternalServerError()).andReturn().getResponse();
        assertError(response, "INTERNAL_ERROR");
        assertThat(response.getContentAsString()).doesNotContain("test refresh write failure");
        assertThat(response.getCookie("kanban_refresh")).isNull();
        assertThat(response.getCookie("kanban_csrf")).isNull();
        assertCounts(0);
    }

    @Test
    void doesNotReportOtherDatabaseConstraintsAsAnEmailConflict() throws Exception {
        doReturn(" ").when(passwords).encode(anyString());
        var response = mvc.perform(register("valid@example.com", "Олексій"))
                .andExpect(status().isInternalServerError()).andReturn().getResponse();
        assertError(response, "INTERNAL_ERROR");
        assertThat(response.getContentAsString()).doesNotContain("chk_users_password_hash_not_blank");
        assertThat(response.getCookie("kanban_refresh")).isNull();
        assertCounts(0);
    }

    private MockHttpServletRequestBuilder register(String email, String displayName) throws Exception {
        return withCsrf(body(email, displayName));
    }

    private String body(String email, String displayName) {
        return json.writeValueAsString(Map.of("email", email, "password", PASSWORD, "displayName", displayName));
    }

    private MockHttpServletRequestBuilder withCsrf(String body) throws Exception {
        var response = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        var csrf = json.readValue(response.getContentAsString(), CsrfResponse.class);
        Cookie cookie = response.getCookie("kanban_csrf");
        return post("/api/v1/auth/register").cookie(cookie).header(csrf.headerName(), csrf.token())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void assertError(MockHttpServletResponse response, String code) throws Exception {
        var body = json.readTree(response.getContentAsString());
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(body.get("status").asInt()).isEqualTo(response.getStatus());
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(response.getContentAsString()).doesNotContain(PASSWORD, "passwordHash", "token_hash");
    }

    private void assertCounts(long expected) {
        assertThat(users.count()).isEqualTo(expected);
        assertThat(sessions.count()).isEqualTo(expected);
        assertThat(tokens.count()).isEqualTo(expected);
    }
}
