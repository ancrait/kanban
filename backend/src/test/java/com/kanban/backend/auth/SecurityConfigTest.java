package com.kanban.backend.auth;

import com.kanban.backend.auth.internal.config.AuthProperties;
import com.kanban.backend.auth.internal.config.AuthConfig;
import com.kanban.backend.auth.internal.security.JwtConfig;
import com.kanban.backend.auth.internal.security.JwtService;
import com.kanban.backend.auth.internal.security.SecurityConfig;
import com.kanban.backend.auth.internal.security.SecurityErrorHandler;
import com.kanban.backend.auth.internal.web.CsrfController;
import com.kanban.backend.auth.internal.web.dto.CsrfResponse;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {CsrfController.class, SecurityConfigTest.SecurityProbeController.class},
        properties = {"app.auth.cookie-secure=false", "app.auth.bcrypt-strength=4"})
@AutoConfigureMockMvc
@Import({AuthConfig.class, JwtConfig.class, JwtService.class, SecurityConfig.class, SecurityErrorHandler.class,
        CsrfController.class, SecurityConfigTest.TestClock.class, SecurityConfigTest.SecurityProbeController.class})
class SecurityConfigTest {
    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final UUID USER_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final Path KEY_DIRECTORY = createKeys();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.auth.private-key-location", () -> KEY_DIRECTORY.resolve("private.pem").toUri().toString());
        registry.add("app.auth.public-key-location", () -> KEY_DIRECTORY.resolve("public.pem").toUri().toString());
    }

    @Autowired private MockMvc mvc;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private JwtService tokens;
    @Autowired private JwtEncoder encoder;
    @Autowired private JwtDecoder decoder;
    @Autowired private PasswordEncoder passwords;
    @Autowired private AuthProperties properties;

    @AfterAll
    static void removeKeys() throws IOException {
        Files.deleteIfExists(KEY_DIRECTORY.resolve("private.pem"));
        Files.deleteIfExists(KEY_DIRECTORY.resolve("public.pem"));
        Files.deleteIfExists(KEY_DIRECTORY);
    }

    @Test
    void returnsRealCsrfTokenAndHttpOnlyCookieWithoutCreatingSession() throws Exception {
        var result = mvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
                .andReturn();
        Cookie cookie = result.getResponse().getCookie("kanban_csrf");
        CsrfResponse csrf = jsonMapper.readValue(result.getResponse().getContentAsString(), CsrfResponse.class);

        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isFalse();
        assertThat(cookie.getPath()).isEqualTo("/api/v1/auth");
        assertThat(cookie.getDomain()).isNull();
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
        assertThat(csrf.token()).isNotEqualTo(cookie.getValue());
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getCookie("JSESSIONID")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"register", "login", "refresh", "logout"})
    void authPostsRequireCsrf(String endpoint) throws Exception {
        expectError(mvc.perform(post("/api/v1/auth/" + endpoint)), 403, "CSRF_INVALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"register", "login", "refresh", "logout"})
    void authPostsAcceptReturnedCsrfEvenWithExpiredBearer(String endpoint) throws Exception {
        var response = mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse();
        var csrf = jsonMapper.readValue(response.getContentAsString(), CsrfResponse.class);
        String expired = sign(claims().issuedAt(NOW.minusSeconds(300)).expiresAt(NOW.minusSeconds(120)));

        mvc.perform(post("/api/v1/auth/" + endpoint)
                        .cookie(response.getCookie("kanban_csrf"))
                        .header(csrf.headerName(), csrf.token())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isNoContent());
    }

    @Test
    void rejectsWrongCsrfAndRawCookieValue() throws Exception {
        var response = mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse();
        Cookie cookie = response.getCookie("kanban_csrf");
        for (String token : List.of("wrong-csrf", cookie.getValue())) {
            expectError(mvc.perform(post("/api/v1/auth/login").cookie(cookie)
                    .header("X-CSRF-TOKEN", token)), 403, "CSRF_INVALID");
        }
    }

    @Test
    void requiresTheCookieMatchingTheReturnedCsrfToken() throws Exception {
        var response = mvc.perform(get("/api/v1/auth/csrf")).andReturn().getResponse();
        var csrf = jsonMapper.readValue(response.getContentAsString(), CsrfResponse.class);
        expectError(mvc.perform(post("/api/v1/auth/login")
                .header(csrf.headerName(), csrf.token())), 403, "CSRF_INVALID");
    }

    @Test
    void defaultsToSecureCsrfCookie() {
        var repository = new SecurityConfig().csrfTokenRepository(new AuthProperties());
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        repository.saveToken(repository.generateToken(request), request, response);
        assertThat(response.getCookie("kanban_csrf").getSecure()).isTrue();
    }

    @Test
    void requiresBearerAndIgnoresRefreshCookieAndQueryParameter() throws Exception {
        expectError(mvc.perform(get("/api/v1/security-probe")), 401, "ACCESS_TOKEN_MISSING");
        expectError(mvc.perform(get("/api/v1/security-probe")
                .cookie(new Cookie("kanban_refresh", "opaque-refresh"))), 401, "ACCESS_TOKEN_MISSING");
        expectError(mvc.perform(get("/api/v1/security-probe")
                .queryParam("access_token", tokens.createAccessToken(USER_ID))), 401, "ACCESS_TOKEN_MISSING");
    }

    @Test
    void doesNotAuthenticateFromHttpSessionOrBasicCredentials() throws Exception {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(USER_ID.toString(), null, List.of()));
        var session = new MockHttpSession();
        session.setAttribute("SPRING_SECURITY_CONTEXT", context);
        expectError(mvc.perform(get("/api/v1/security-probe").session(session)), 401, "ACCESS_TOKEN_MISSING");
        expectError(mvc.perform(get("/api/v1/security-probe")
                .header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNzd29yZA==")), 401, "ACCESS_TOKEN_MISSING");
    }

    @Test
    void acceptsSignedJwtForReadsAndWritesWithoutCsrf() throws Exception {
        String token = tokens.createAccessToken(USER_ID);
        mvc.perform(get("/api/v1/security-probe").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value(USER_ID.toString()))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
        mvc.perform(post("/api/v1/security-probe").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test
    void emitsOnlyRequiredAccessClaimsWithConfiguredTtl() {
        var jwt = decoder.decode(tokens.createAccessToken(USER_ID));
        assertThat(jwt.getHeaders().get("alg")).isEqualTo("RS256");
        assertThat(jwt.getClaims()).containsOnlyKeys("iss", "aud", "sub", "iat", "exp");
        assertThat(jwt.getSubject()).isEqualTo(USER_ID.toString());
        assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plus(properties.getAccessTtl()));
    }

    @Test
    void distinguishesExpiredFromMalformedTokens() throws Exception {
        String expired = sign(claims().issuedAt(NOW.minusSeconds(300)).expiresAt(NOW.minusSeconds(120)));
        expectError(mvc.perform(get("/api/v1/security-probe")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + expired)), 401, "ACCESS_TOKEN_EXPIRED");
        expectError(mvc.perform(get("/api/v1/security-probe")
                .header(HttpHeaders.AUTHORIZATION, "Bearer malformed-secret")), 401, "ACCESS_TOKEN_INVALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"iss", "aud", "sub", "iat", "exp", "wrong-issuer", "wrong-audience",
            "invalid-sub", "future-iat", "future-nbf"})
    void rejectsInvalidOrMissingClaims(String problem) throws Exception {
        var claims = claims();
        switch (problem) {
            case "wrong-issuer" -> claims.issuer("another-issuer");
            case "wrong-audience" -> claims.audience(List.of("another-api"));
            case "invalid-sub" -> claims.subject("not-a-user-uuid");
            case "future-iat" -> claims.issuedAt(NOW.plusSeconds(120));
            case "future-nbf" -> claims.notBefore(NOW.plusSeconds(120));
            default -> claims.claims(values -> values.remove(problem));
        }
        expectError(mvc.perform(get("/api/v1/security-probe")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + sign(claims))), 401, "ACCESS_TOKEN_INVALID");
    }

    @Test
    void appliesConfiguredClockSkew() throws Exception {
        String token = sign(claims().issuedAt(NOW.minusSeconds(300)).expiresAt(NOW.minusSeconds(10)));
        mvc.perform(get("/api/v1/security-probe").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsOtherSignatureAlgorithms() throws Exception {
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS512).build(), claims().build())).getTokenValue();
        expectError(mvc.perform(get("/api/v1/security-probe")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)), 401, "ACCESS_TOKEN_INVALID");
    }

    @Test
    void rejectsForeignSigningKeys() throws Exception {
        KeyPair pair = newKeyPair();
        var key = new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey((RSAPrivateKey) pair.getPrivate()).build();
        var foreignEncoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        String token = foreignEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims().build())).getTokenValue();
        expectError(mvc.perform(get("/api/v1/security-probe")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)), 401, "ACCESS_TOKEN_INVALID");
    }

    @Test
    void permitsMinimalHealthAndDeniesOtherRoutesAndSwaggerByDefault() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        String token = tokens.createAccessToken(USER_ID);
        for (String path : List.of("/actuator/env", "/v3/api-docs", "/swagger-ui/index.html", "/outside-api")) {
            expectError(mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)), 403, "ACCESS_DENIED");
        }
    }

    @Test
    void hashesPasswordsWithConfiguredBcryptStrength() {
        String hash = passwords.encode("test-password-123");
        assertThat(hash).startsWith("$2a$04$");
        assertThat(passwords.matches("test-password-123", hash)).isTrue();
        assertThat(passwords.matches("wrong-password", hash)).isFalse();
    }

    @Test
    void rejectsMismatchedConfiguredKeys() throws Exception {
        Path otherPublic = Files.createTempFile(KEY_DIRECTORY, "other-public-", ".pem");
        try {
            Files.writeString(otherPublic, pem("PUBLIC KEY", newKeyPair().getPublic().getEncoded()));
            var invalid = new AuthProperties();
            invalid.setPrivateKeyLocation(properties.getPrivateKeyLocation());
            invalid.setPublicKeyLocation(otherPublic.toUri().toString());
            assertThatThrownBy(() -> new JwtConfig().rsaKey(invalid, new DefaultResourceLoader()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("matching RSA key pair");
        } finally {
            Files.deleteIfExists(otherPublic);
        }
    }

    private void expectError(ResultActions result, int status, String code) throws Exception {
        String body = result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("Exception", "stackTrace", "malformed-secret", "opaque-refresh", "wrong-csrf");
        if (status == 401) result.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
    }

    private JwtClaimsSet.Builder claims() {
        return JwtClaimsSet.builder().issuer(properties.getIssuer()).audience(List.of(properties.getAudience()))
                .subject(USER_ID.toString()).issuedAt(NOW).expiresAt(NOW.plusSeconds(900));
    }

    private String sign(JwtClaimsSet.Builder claims) {
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims.build())).getTokenValue();
    }

    private static KeyPair newKeyPair() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    static Path createKeys() {
        try {
            Path directory = Files.createTempDirectory(Path.of("target"), "security-test-keys-");
            KeyPair pair = newKeyPair();
            Files.writeString(directory.resolve("private.pem"), pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
            Files.writeString(directory.resolve("public.pem"), pem("PUBLIC KEY", pair.getPublic().getEncoded()));
            return directory;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not create test RSA keys", exception);
        }
    }

    private static String pem(String type, byte[] value) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(value)
                + "\n-----END " + type + "-----\n";
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClock {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @RestController
    @TestComponent
    static class SecurityProbeController {
        @GetMapping("/actuator/health")
        Map<String, String> health() {
            return Map.of("status", "UP");
        }

        @GetMapping("/api/v1/security-probe")
        Map<String, String> identity(Authentication authentication) {
            return Map.of("subject", authentication.getName());
        }

        @PostMapping({"/api/v1/security-probe", "/api/v1/auth/register", "/api/v1/auth/login",
                "/api/v1/auth/refresh", "/api/v1/auth/logout"})
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void mutate() {
        }
    }
}
