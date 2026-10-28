package com.kanban.backend.auth.internal.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.CodePointLength;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

public record RegisterRequest(
        @NotBlank(message = "Email є обов'язковим.")
        @Email(message = "Некоректний формат email.")
        @Size(max = 254, message = "Email має містити не більше 254 символів.")
        String email,
        @NotBlank(message = "Пароль є обов'язковим.")
        @CodePointLength(min = 12, max = 72, message = "Пароль має містити від 12 до 72 символів.")
        String password,
        @NotBlank(message = "Ім'я є обов'язковим.")
        @CodePointLength(min = 2, max = 50, message = "Ім'я має містити від 2 до 50 символів.")
        String displayName
) {
    public RegisterRequest {
        email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
        displayName = displayName == null ? null : displayName.strip();
        if (password != null && password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Пароль має містити не більше 72 байтів UTF-8.");
        }
    }
}
