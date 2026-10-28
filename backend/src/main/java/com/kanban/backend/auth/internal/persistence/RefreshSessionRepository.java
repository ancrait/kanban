package com.kanban.backend.auth.internal.persistence;

import com.kanban.backend.auth.internal.domain.RefreshSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.time.Instant;
import java.util.UUID;

public interface RefreshSessionRepository extends JpaRepository<RefreshSession, UUID> {

    /**
     * Requires a caller transaction spanning the entire refresh or logout operation.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RefreshSession s where s.id = :id")
    Optional<RefreshSession> findByIdForUpdate(@Param("id") UUID id);

    @Modifying
    @Query(value = """
            WITH completed AS (
                SELECT id FROM auth.refresh_sessions
                WHERE expires_at <= :cutoff OR revoked_at <= :cutoff
                ORDER BY id
                LIMIT :batchSize
                FOR UPDATE SKIP LOCKED
            )
            DELETE FROM auth.refresh_sessions s USING completed c WHERE s.id = c.id
            """, nativeQuery = true)
    int deleteCompletedBefore(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
