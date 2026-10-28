package com.kanban.backend;

import com.kanban.backend.auth.internal.web.dto.LoginRequest;
import com.kanban.backend.auth.internal.web.dto.RegisterRequest;
import com.kanban.backend.infrastructure.web.ApiExceptionHandler;
import com.kanban.backend.infrastructure.web.ApiJsonConfig;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Min;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApiExceptionHandlerTest.ProbeController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({ApiExceptionHandlerTest.ProbeController.class,
        ApiExceptionHandler.class, ApiJsonConfig.class})
@ExtendWith(OutputCaptureExtension.class)
class ApiExceptionHandlerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private Validator validator;

    @Test
    void returnsFieldErrorsWithoutRejectedPassword() throws Exception {
        String body = mvc.perform(post("/api/v1/probe/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"invalid", "password":"secret", "displayName":" "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.error").value("Validation Failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.password").value("Пароль має містити від 12 до 72 символів."))
                .andExpect(jsonPath("$.errors.email").value("Некоректний формат email."))
                .andExpect(jsonPath("$.errors.displayName").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("secret", "rejectedValue", "properties");
    }

    @Test
    void validatesRequiredFields() throws Exception {
        mvc.perform(post("/api/v1/probe/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.email").value("Email є обов'язковим."))
                .andExpect(jsonPath("$.errors.password").value("Пароль є обов'язковим."))
                .andExpect(jsonPath("$.errors.displayName").value("Ім'я є обов'язковим."));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"password\":\"secret\"",
            "{\"email\":\"user@example.com\",\"password\":\"valid-password\",\"displayName\":\"User\",\"extra\":\"secret\"}",
            "{\"email\":\"user@example.com\",\"password\":123456789012,\"displayName\":\"User\"}",
            "{\"email\":true,\"password\":\"valid-password\",\"displayName\":\"User\"}",
            "{\"email\":\"user@example.com\",\"password\":[],\"displayName\":\"User\"}",
            "{} {}"
    })
    void rejectsMalformedUnknownAndWronglyTypedJson(String json) throws Exception {
        String body = mvc.perform(post("/api/v1/probe/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("secret", "123456789012", "Exception", "stackTrace");
    }

    @Test
    void validatesControllerParameters() throws Exception {
        mvc.perform(get("/api/v1/probe/parameter").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void returnsSafeBadRequestForMethodValidation() throws Exception {
        String body = mvc.perform(post("/api/v1/probe/validated-request").param("limit", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"invalid", "password":"secret", "displayName":"User"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("secret", "rejectedValue");
    }

    @Test
    void preservesBadRequestForWrongParameterType() throws Exception {
        String body = mvc.perform(get("/api/v1/probe/parameter").param("limit", "secret"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("secret", "Exception");
    }

    @Test
    void returnsInternalErrorForInvalidControllerReturnValue() throws Exception {
        mvc.perform(get("/api/v1/probe/invalid-response"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void returnsSafeInternalErrorWithoutLoggingSecrets(CapturedOutput output) throws Exception {
        String body = mvc.perform(get("/api/v1/probe/failure").queryParam("password", "query-secret"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value("Сталася внутрішня помилка сервера."))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("password", "refresh-secret", "query-secret", "IllegalStateException");
        assertThat(output.getAll()).contains("exceptionType=").doesNotContain("refresh-secret", "query-secret");
    }

    @Test
    void preservesMethodNotAllowedAndAllowHeader() throws Exception {
        mvc.perform(delete("/api/v1/probe/request"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, "POST"))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void preservesUnsupportedMediaType() throws Exception {
        mvc.perform(post("/api/v1/probe/request").contentType(MediaType.TEXT_PLAIN).content("secret"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void returnsNotFoundForUnknownRoute() throws Exception {
        mvc.perform(get("/api/v1/probe/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void usesBuiltInConstraintsForUnicodeLengths() {
        assertThat(validator.validate(new RegisterRequest("user@example.com", "😀".repeat(12), "😀😀"))).isEmpty();
        assertThat(validator.validate(new RegisterRequest("user@example.com", "😀".repeat(6), "User")))
                .anyMatch(error -> error.getPropertyPath().toString().equals("password"));
        assertThat(validator.validate(new RegisterRequest("user@example.com", "valid-password", "😀")))
                .anyMatch(error -> error.getPropertyPath().toString().equals("displayName"));
    }

    @Test
    void enforcesBcryptByteLimitWithoutCustomValidators() throws Exception {
        assertThat(validator.validate(new RegisterRequest("user@example.com", "я".repeat(36), "User"))).isEmpty();
        String body = mvc.perform(post("/api/v1/probe/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user@example.com","password":"%s","displayName":"User"}
                                """.formatted("я".repeat(37))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("я".repeat(37));
        assertThatThrownBy(() -> new LoginRequest("user@example.com", "я".repeat(37)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizesFieldsAndKeepsRegistrationRulesOutOfLogin() {
        RegisterRequest request = new RegisterRequest(" User@Example.com ", " valid-password ", " User ");
        assertThat(request.email()).isEqualTo("user@example.com");
        assertThat(request.displayName()).isEqualTo("User");
        assertThat(request.password()).isEqualTo(" valid-password ");
        assertThat(validator.validate(new LoginRequest(" User@Example.com ", "short"))).isEmpty();
        assertThat(validator.validate(new LoginRequest("user@example.com", " "))).isNotEmpty();
    }

    @RestController
    @TestComponent
    static class ProbeController {
        @PostMapping("/api/v1/probe/request")
        void request(@Valid @RequestBody RegisterRequest request) {
        }

        @GetMapping("/api/v1/probe/parameter")
        void parameter(@RequestParam @Min(1) int limit) {
        }

        @PostMapping("/api/v1/probe/validated-request")
        void validatedRequest(@Valid @RequestBody RegisterRequest request, @RequestParam @Min(1) int limit) {
        }

        @GetMapping("/api/v1/probe/invalid-response")
        @Min(1)
        int invalidResponse() {
            return 0;
        }

        @GetMapping("/api/v1/probe/failure")
        void failure() {
            throw new IllegalStateException("password=refresh-secret");
        }

    }
}
