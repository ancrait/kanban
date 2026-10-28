package com.kanban.backend.auth.internal.application;

import com.kanban.backend.auth.internal.config.AuthProperties;
import com.kanban.backend.auth.internal.persistence.RefreshSessionRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

@Service
public class RefreshSessionCleanup {
    private final RefreshSessionRepository sessions;
    private final AuthProperties properties;
    private final Clock clock;

    public RefreshSessionCleanup(RefreshSessionRepository sessions, AuthProperties properties, Clock clock) {
        this.sessions = sessions;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.auth.cleanup-interval}", initialDelayString = "${app.auth.cleanup-interval}",
            timeUnit = TimeUnit.SECONDS)
    @Transactional
    public int deleteCompletedSessions() {
        var cutoff = clock.instant().minus(properties.getSessionRetention()).truncatedTo(ChronoUnit.MICROS);
        return sessions.deleteCompletedBefore(cutoff, properties.getCleanupBatchSize());
    }
}
