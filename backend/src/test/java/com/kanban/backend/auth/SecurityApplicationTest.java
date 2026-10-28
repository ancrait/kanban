package com.kanban.backend.auth;

import com.kanban.backend.auth.internal.application.RefreshSessionCleanup;
import com.kanban.backend.auth.internal.persistence.UserRepository;
import com.kanban.backend.auth.internal.security.JwtService;
import com.kanban.backend.auth.internal.web.dto.AuthResponse;
import com.kanban.backend.auth.internal.web.dto.CsrfResponse;
import com.kanban.backend.auth.internal.web.dto.UserResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"app.auth.bcrypt-strength=4", "app.auth.cleanup-interval=3600"})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class SecurityApplicationTest {
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

    @Autowired
    private MockMvc mvc;

    @Autowired private JsonMapper json;
    @Autowired private UserRepository users;
    @Autowired private JwtService jwt;
    @Autowired private ScheduledAnnotationBeanPostProcessor scheduling;

    @AfterAll
    static void removeKeys() throws IOException {
        Files.deleteIfExists(KEY_DIRECTORY.resolve("private.pem"));
        Files.deleteIfExists(KEY_DIRECTORY.resolve("public.pem"));
        Files.deleteIfExists(KEY_DIRECTORY);
    }

    @Test
    void startsTheRealApplicationWithDatabaseKeysAndSecurity() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        var response = mvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn().getResponse();
        assertThat(response.getCookie("kanban_csrf").getSecure()).isTrue();
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCESS_TOKEN_MISSING"));
    }

    @Test
    void registersHourlyCleanupWithNumericIntervalInSeconds() {
        assertThat(scheduling.getScheduledTasks())
                .filteredOn(task -> task.toString().equals(RefreshSessionCleanup.class.getName() + ".deleteCompletedSessions"))
                .singleElement().satisfies(task ->
                assertThat(task.getTask()).isInstanceOfSatisfying(FixedDelayTask.class, delay -> {
                    assertThat(delay.getIntervalDuration()).isEqualTo(Duration.ofHours(1));
                    assertThat(delay.getInitialDelayDuration()).isEqualTo(Duration.ofHours(1));
                }));
    }

    @Test
    void registrationAndLoginTokensReturnOnlyTheirOwnersProfile() throws Exception {
        var first = json.readValue(register("first-profile@example.com").getContentAsString(), AuthResponse.class);
        var second = json.readValue(register("second-profile@example.com").getContentAsString(), AuthResponse.class);
        assertProfile(first);
        assertProfile(second);
        var login = authPost("login", Map.of("email", first.user().email(), "password", "correct-password-123"))
                .andExpect(status().isOk()).andReturn().getResponse();
        var loggedIn = json.readValue(login.getContentAsString(), AuthResponse.class);
        assertThat(loggedIn.user()).isEqualTo(first.user());
        assertProfile(loggedIn);
    }

    @Test
    void rejectsAValidTokenAfterItsUserHasBeenDeleted() throws Exception {
        var auth = json.readValue(register("deleted-profile@example.com").getContentAsString(), AuthResponse.class);
        users.deleteById(auth.user().id());
        assertUnauthorized(mvc.perform(get("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + auth.accessToken())), "ACCESS_TOKEN_INVALID");
        assertThat(users.findById(auth.user().id())).isEmpty();
    }

    @Test
    void rejectsAValidTokenForAnUnknownUser() throws Exception {
        String token = jwt.createAccessToken(UUID.randomUUID());
        assertUnauthorized(mvc.perform(get("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)), "ACCESS_TOKEN_INVALID");
    }

    @Test
    void refreshCookieAloneDoesNotAuthenticateTheProfileEndpoint() throws Exception {
        var registration = register("cookie-profile@example.com");
        assertUnauthorized(mvc.perform(get("/api/v1/users/me")
                .cookie(registration.getCookie("kanban_refresh"))), "ACCESS_TOKEN_MISSING");
    }

    @Test
    void rejectsAMalformedTokenBeforeReadingAProfile() throws Exception {
        assertUnauthorized(mvc.perform(get("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer broken-token")), "ACCESS_TOKEN_INVALID");
    }

    private MockHttpServletResponse register(String email) throws Exception {
        return authPost("register", Map.of("email", email, "password", "correct-password-123", "displayName", "Олексій"))
                .andExpect(status().isCreated()).andReturn().getResponse();
    }

    private ResultActions authPost(String action, Map<String, String> body) throws Exception {
        var response = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn().getResponse();
        var csrf = json.readValue(response.getContentAsString(), CsrfResponse.class);
        return mvc.perform(post("/api/v1/auth/" + action).cookie(response.getCookie("kanban_csrf"))
                .header(csrf.headerName(), csrf.token()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private void assertProfile(AuthResponse auth) throws Exception {
        var result = mvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + auth.accessToken()))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store")).andReturn();
        var response = result.getResponse();
        assertThat(json.readValue(response.getContentAsString(), UserResponse.class)).isEqualTo(auth.user());
        assertThat(json.readTree(response.getContentAsString()).size()).isEqualTo(4);
        assertThat(response.getContentAsString()).doesNotContain("password", "token", "correct-password-123");
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    private void assertUnauthorized(ResultActions request, String code) throws Exception {
        var response = request.andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.status").value(401)).andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.timestamp").isNotEmpty()).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("password", "broken-token", "correct-password-123");
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }
}
