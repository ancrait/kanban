package com.kanban.backend.auth.internal.application;

public class InvalidAccessTokenException extends RuntimeException {
    public InvalidAccessTokenException() {
        super("Потрібен чинний access-токен.");
    }
}
