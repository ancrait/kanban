package com.kanban.backend.auth.internal.persistence;

import com.kanban.backend.auth.internal.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    // Include consumed and expired tokens so replay can be detected.
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    // Read only the session ID before locking, so no stale token entity enters the persistence context.
    @Query("select t.sessionId from RefreshToken t where t.tokenHash = :tokenHash")
    Optional<UUID> findSessionIdByTokenHash(@Param("tokenHash") String tokenHash);
}
