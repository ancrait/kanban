package com.kanban.backend.auth.internal.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "refresh_sessions", schema = "auth")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_rotated_at", nullable = false)
    private Instant lastRotatedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    public RefreshSession(UUID userId, Instant createdAt, Instant expiresAt) {
        this.userId = Objects.requireNonNull(userId);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.lastRotatedAt = createdAt;
        this.expiresAt = Objects.requireNonNull(expiresAt);
    }

    public void rotate(Instant rotatedAt, Instant expiresAt) {
        if (revokedAt != null) {
            throw new IllegalStateException("A revoked refresh session cannot be rotated");
        }
        Objects.requireNonNull(rotatedAt);
        Objects.requireNonNull(expiresAt);
        this.lastRotatedAt = rotatedAt;
        this.expiresAt = expiresAt;
    }

    public void revoke(Instant revokedAt) {
        Objects.requireNonNull(revokedAt);
        if (this.revokedAt == null) {
            this.revokedAt = revokedAt;
        }
    }
}
