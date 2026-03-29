package com.gitsolve.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Type-safe configuration properties for GitSolve AI.
 * All external configuration must flow through this record — no @Value injection
 * in agent or model classes.
 *
 * @Valid on nested records is required for Bean Validation to cascade into them.
 * @NotNull on the nested record fields ensures the group itself is required.
 */
@ConfigurationProperties(prefix = "gitsolve")
@Validated
public record GitSolveProperties(
        @NotNull @Valid GitHub github,
        @NotNull @Valid Llm llm,
        Docker docker,
        Schedule schedule
) {

    public record GitHub(
            @NotBlank String appToken,
            int maxReposPerRun,      // default: 10
            int maxIssuesPerRepo     // default: 5
    ) {}

    public record Llm(
            @NotBlank String provider,          // "anthropic" | "gemini"
            @NotBlank String model,
            int maxTokensPerRun,                // daily cap: 500000
            int maxIterationsPerFix             // default: 5
    ) {}

    public record Docker(
            String image,                       // default: "eclipse-temurin:21-jdk"
            long memLimitMb,                    // default: 1024
            long cpuQuota,                      // default: 50000 (50%)
            int buildTimeoutSeconds             // default: 300
    ) {}

    public record Schedule(
            String cron                         // default: "0 0 0 * * *"
    ) {}
}
