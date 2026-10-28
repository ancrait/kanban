package com.kanban.backend.auth.internal.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.auth.rate-limit")
public class AuthRateLimitProperties {
    @Min(1)
    private int registerAttempts = 5;

    @NotNull
    @DurationMin(seconds = 1)
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration registerWindow = Duration.ofHours(1);

    @Min(1)
    private int loginIpAttempts = 30;

    @Min(1)
    private int loginEmailAttempts = 10;

    @NotNull
    @DurationMin(seconds = 1)
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration loginWindow = Duration.ofMinutes(15);

    @Min(1)
    private int refreshAttempts = 120;

    @NotNull
    @DurationMin(seconds = 1)
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration refreshWindow = Duration.ofMinutes(1);

    @Min(2)
    @Max(100000)
    private int maxEntries = 10000;
}
