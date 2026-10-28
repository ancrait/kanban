package com.kanban.backend.auth.internal.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

public record LoginRequest(
        @NotBlank(message = "Email є обов'язковим.")
        @Email(message = "Некоректний формат email.")
        @Size(max = 254, message = "Email має містити не більше 254 символів.")
        String email,
        @NotBlank(message = "Пароль є обов'язковим.")
        String password
) {
    public LoginRequest {
        email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
        if (password != null && password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Пароль має містити не більше 72 байтів UTF-8.");
        }
    }
}
