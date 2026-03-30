package com.gitsolve.config;

import java.util.List;
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
        Schedule schedule,
        RepoCache repoCache
) {

    public record GitHub(
            @NotBlank String appToken,
            int maxReposPerRun,      // default: 2
            int maxIssuesPerRepo,    // default: 5
            List<String> targetRepos,   // optional: pin specific repos e.g. ["apache/commons-lang"]
            String starRange            // optional: e.g. "50..2000" — only used when targetRepos is empty
    ) {
        public boolean hasTargetRepos() {
            return targetRepos != null && !targetRepos.isEmpty();
        }
    }

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

    /**
     * Local repository cache — persists cloned repos to avoid redundant network clones.
     * baseDir defaults to ~/.gitsolve-repos.
     * updateExisting: if true (default), run git fetch+pull on a cache hit.
     */
    public record RepoCache(
            String baseDir,         // default: ${HOME}/.gitsolve-repos
            boolean updateExisting  // default: true
    ) {
        /** Returns baseDir, falling back to ${HOME}/.gitsolve-repos if not configured. */
        public String effectiveBaseDir() {
            if (baseDir != null && !baseDir.isBlank()) return baseDir;
            return System.getProperty("user.home") + "/.gitsolve-repos";
        }
    }
}
