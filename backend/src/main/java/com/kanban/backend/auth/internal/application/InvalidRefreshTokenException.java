package com.kanban.backend.auth.internal.application;

public class InvalidRefreshTokenException extends RuntimeException {
    public InvalidRefreshTokenException() {
        super("Некоректний або прострочений refresh-токен.");
    }
}
