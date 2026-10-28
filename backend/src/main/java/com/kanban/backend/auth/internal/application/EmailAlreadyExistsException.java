package com.kanban.backend.auth.internal.application;

public class EmailAlreadyExistsException extends RuntimeException {
    public EmailAlreadyExistsException() {
        super("Користувач із таким email вже існує.");
    }
}
