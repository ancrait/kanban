package com.kanban.backend.auth.internal.persistence;

import com.kanban.backend.auth.internal.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    // Include consumed and expired tokens so replay can be detected.
    Optional<RefreshToken> findByTokenHash(String tokenHash);
}
