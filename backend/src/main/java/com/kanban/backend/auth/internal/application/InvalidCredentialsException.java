package com.kanban.backend.auth.internal.application;

public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
        super("Неправильний email або пароль.");
    }
}
