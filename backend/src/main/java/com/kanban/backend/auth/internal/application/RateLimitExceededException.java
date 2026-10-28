package com.kanban.backend.auth.internal.application;

public class RateLimitExceededException extends RuntimeException {
    private final long retryAfter;

    public RateLimitExceededException(long retryAfter) {
        super("Забагато запитів. Спробуйте пізніше.");
        this.retryAfter = retryAfter;
    }

    public long getRetryAfter() {
        return retryAfter;
    }
}
