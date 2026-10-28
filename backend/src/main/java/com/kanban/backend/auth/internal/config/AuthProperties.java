package com.kanban.backend.auth.internal.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

    @NotNull
    @DurationMin(seconds = 1)
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration accessTtl = Duration.ofMinutes(15);

    @NotNull
    @DurationMin(seconds = 1)
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration refreshTtl = Duration.ofDays(7);

    @NotNull
    @DurationMin(seconds = 0)
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration sessionRetention = Duration.ofDays(7);

    @NotNull
    @DurationMin(seconds = 1)
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration cleanupInterval = Duration.ofHours(1);

    @Min(1)
    @Max(1000)
    private int cleanupBatchSize = 500;

    @NotBlank
    private String issuer;

    @NotBlank
    private String audience;

    @NotBlank
    @Pattern(regexp = "^(file:|classpath:).+", message = "must use a file: or classpath: location")
    private String privateKeyLocation;

    @NotBlank
    @Pattern(regexp = "^(file:|classpath:).+", message = "must use a file: or classpath: location")
    private String publicKeyLocation;

    @NotNull
    @DurationMin(seconds = 0)
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration clockSkew = Duration.ofSeconds(30);

    private boolean cookieSecure = true;

    @Min(4)
    @Max(31)
    private int bcryptStrength = 12;
}
