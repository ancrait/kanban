package com.kanban.backend.auth;

import com.kanban.backend.auth.internal.application.AuthService;
import com.kanban.backend.auth.internal.persistence.RefreshSessionRepository;
import com.kanban.backend.auth.internal.persistence.RefreshTokenRepository;
import com.kanban.backend.auth.internal.persistence.UserRepository;
import com.kanban.backend.auth.internal.web.dto.CsrfResponse;
import com.kanban.backend.auth.internal.web.dto.RegisterRequest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"app.auth.bcrypt-strength=4", "app.auth.rate-limit.register-attempts=1",
        "app.auth.rate-limit.register-window=60", "app.auth.rate-limit.login-ip-attempts=2",
        "app.auth.rate-limit.login-email-attempts=1", "app.auth.rate-limit.login-window=60",
        "app.auth.rate-limit.refresh-attempts=1", "app.auth.rate-limit.refresh-window=60"})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class AuthRateLimitTest {
    private static final String EMAIL = "limited@example.com";
    private static final String PASSWORD = "correct-password-123";
    private static final Path KEY_DIRECTORY = SecurityConfigTest.createKeys();
    private static final AtomicInteger TEST_NUMBER = new AtomicInteger();

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
    @Autowired private RefreshTokenRepository tokens;
    @MockitoSpyBean private AuthService auth;
    @MockitoSpyBean private PasswordEncoder passwords;
    @MockitoSpyBean private Clock clock;
    private Instant now;

    @BeforeEach
    void resetDataAndAdvancePastPreviousWindows() {
        now = Instant.parse("2026-10-28T10:00:00Z").plusSeconds(TEST_NUMBER.incrementAndGet() * 7200L);
        doReturn(now).when(clock).instant();
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
    void rejectsRegistrationBeforeCreatingAnotherUserOrSession() throws Exception {
        mvc.perform(authPost("register").content(registration(EMAIL))).andExpect(status().isCreated());
        assertLimited(mvc.perform(authPost("register").content(registration("another@example.com"))));
        verify(auth, times(1)).register(any());
        assertCounts(1, 1, 1);
        // A separate client has its own registration quota.
        mvc.perform(authPost("register").with(request -> {
            request.setRemoteAddr("192.0.2.2");
            return request;
        }).content(registration("another@example.com"))).andExpect(status().isCreated());
        assertCounts(2, 2, 2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void limitsNormalizedEmailEquallyForKnownAndUnknownUsersAcrossAddresses(boolean known) throws Exception {
        if (known) {
            auth.register(new RegisterRequest(EMAIL, PASSWORD, "Developer"));
        }
        mvc.perform(login(" Limited@Example.COM ", "wrong-password")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        assertLimited(mvc.perform(login(EMAIL, PASSWORD).with(request -> {
            request.setRemoteAddr("192.0.2.2");
            return request;
        })));
        verify(auth, times(1)).login(any());
        verify(passwords, times(1)).matches(anyString(), anyString());
        assertCounts(known ? 1 : 0, known ? 1 : 0, known ? 1 : 0);
    }

    @Test
    void limitsLoginAddressEvenWithDifferentEmailsAndForgedForwardedHeaders() throws Exception {
        for (int index = 0; index < 2; index++) {
            mvc.perform(login("unknown" + index + "@example.com", PASSWORD)
                    .header("X-Forwarded-For", "198.51.100." + index)
                    .header("Forwarded", "for=198.51.100." + index)).andExpect(status().isUnauthorized());
        }
        assertLimited(mvc.perform(login("third@example.com", PASSWORD)
                .header("X-Forwarded-For", "198.51.100.99").header("Forwarded", "for=198.51.100.99")));
        verify(auth, times(2)).login(any());
        assertCounts(0, 0, 0);
    }

    @Test
    void throttledRefreshKeepsCookiesAndSessionAndWorksAfterTheWindow() throws Exception {
        var initial = auth.register(new RegisterRequest(EMAIL, PASSWORD, "Developer"));
        var rotated = mvc.perform(authPost("refresh").cookie(new Cookie("kanban_refresh", initial.refreshToken())))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("kanban_refresh");
        var session = sessions.findAll().getFirst();
        assertLimited(mvc.perform(authPost("refresh").cookie(new Cookie("kanban_refresh", initial.refreshToken()))));
        verify(auth, times(1)).refresh(anyString());
        assertThat(sessions.findById(session.getId()).orElseThrow().getRevokedAt()).isNull();
        assertCounts(1, 1, 2);

        doReturn(now.plusSeconds(60)).when(clock).instant();
        mvc.perform(authPost("refresh").cookie(rotated)).andExpect(status().isOk());
        assertCounts(1, 1, 3);
    }

    @Test
    void csrfAndValidationErrorsDoNotConsumeLoginQuota() throws Exception {
        for (int index = 0; index < 3; index++) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("email", EMAIL, "password", PASSWORD))))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        }
        mvc.perform(authPost("login").content("{broken-json")).andExpect(status().isBadRequest());
        mvc.perform(authPost("login").content("{\"email\":\"invalid\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(login(EMAIL, PASSWORD)).andExpect(status().isUnauthorized());
        assertLimited(mvc.perform(login(EMAIL, PASSWORD)));
        verify(auth, times(1)).login(any());
        assertCounts(0, 0, 0);
    }

    @Test
    void logoutRemainsAvailableWhileRefreshIsLimited() throws Exception {
        var initial = auth.register(new RegisterRequest(EMAIL, PASSWORD, "Developer"));
        mvc.perform(authPost("refresh").cookie(new Cookie("kanban_refresh", initial.refreshToken())))
                .andExpect(status().isOk());
        assertLimited(mvc.perform(authPost("refresh")));
        var response = mvc.perform(authPost("logout").cookie(new Cookie("kanban_refresh", initial.refreshToken())))
                .andExpect(status().isNoContent()).andReturn().getResponse();
        assertThat(response.getCookie("kanban_refresh").getMaxAge()).isZero();
        assertThat(response.getCookie("kanban_csrf").getMaxAge()).isZero();
        assertThat(sessions.findAll().getFirst().getRevokedAt()).isNotNull();
    }

    private MockHttpServletRequestBuilder authPost(String path) throws Exception {
        var response = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        var csrf = json.readValue(response.getContentAsString(), CsrfResponse.class);
        return post("/api/v1/auth/" + path).contentType(MediaType.APPLICATION_JSON)
                .cookie(response.getCookie("kanban_csrf")).header(csrf.headerName(), csrf.token());
    }

    private MockHttpServletRequestBuilder login(String email, String password) throws Exception {
        return authPost("login").content(json.writeValueAsString(Map.of("email", email, "password", password)));
    }

    private String registration(String email) {
        return json.writeValueAsString(Map.of("email", email, "password", PASSWORD, "displayName", "Developer"));
    }

    private void assertLimited(ResultActions request) throws Exception {
        var result = request.andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "60"))
                .andExpect(jsonPath("$.status").value(429)).andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty()).andReturn();
        MockHttpServletResponse response = result.getResponse();
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
        assertThat(response.getContentAsString()).doesNotContain(EMAIL, PASSWORD, "refreshToken", "passwordHash");
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    private void assertCounts(long userCount, long sessionCount, long tokenCount) {
        assertThat(users.count()).isEqualTo(userCount);
        assertThat(sessions.count()).isEqualTo(sessionCount);
        assertThat(tokens.count()).isEqualTo(tokenCount);
    }
}
