package com.gitsolve.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that the application fails fast when GITHUB_APP_TOKEN (gitsolve.github.app-token)
 * is blank or missing.
 *
 * With @NotBlank on appToken and @NotNull on the GitHub nested record, startup must fail
 * when the token is absent or empty. DB/JPA/Flyway are excluded to isolate this.
 */
@Tag("unit")
class MissingTokenTest {

    private static final String[] INFRA_EXCLUDES = {
            "spring.main.web-application-type=none",
            "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration," +
            "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
            "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
    };

    @Test
    void applicationFailsWhenAppTokenIsBlank() {
        assertStartupFails(new String[]{
                INFRA_EXCLUDES[0], INFRA_EXCLUDES[1],
                "gitsolve.github.app-token=",        // blank — @NotBlank should reject
                "gitsolve.github.max-repos-per-run=10",
                "gitsolve.github.max-issues-per-repo=5",
                "gitsolve.llm.provider=anthropic",
                "gitsolve.llm.model=claude-3-5-sonnet-20241022",
                "gitsolve.llm.max-tokens-per-run=500000",
                "gitsolve.llm.max-iterations-per-fix=5",
                "gitsolve.docker.image=eclipse-temurin:21-jdk",
                "gitsolve.docker.mem-limit-mb=1024",
                "gitsolve.docker.cpu-quota=50000",
                "gitsolve.docker.build-timeout-seconds=300",
                "gitsolve.schedule.cron=0 0 0 * * *",
                "management.otlp.tracing.endpoint=http://localhost:4318/v1/traces"
        }, "blank app-token");
    }

    @Test
    void applicationFailsWhenGithubGroupIsAbsent() {
        assertStartupFails(new String[]{
                INFRA_EXCLUDES[0], INFRA_EXCLUDES[1],
                // github group entirely absent — @NotNull on GitSolveProperties.github() should fire
                "gitsolve.llm.provider=anthropic",
                "gitsolve.llm.model=claude-3-5-sonnet-20241022",
                "gitsolve.llm.max-tokens-per-run=500000",
                "gitsolve.llm.max-iterations-per-fix=5",
                "gitsolve.docker.image=eclipse-temurin:21-jdk",
                "gitsolve.docker.mem-limit-mb=1024",
                "gitsolve.docker.cpu-quota=50000",
                "gitsolve.docker.build-timeout-seconds=300",
                "gitsolve.schedule.cron=0 0 0 * * *",
                "management.otlp.tracing.endpoint=http://localhost:4318/v1/traces"
        }, "absent github group");
    }

    // ------------------------------------------------------------------ //

    private static void assertStartupFails(String[] props, String scenario) {
        assertThatThrownBy(() -> {
            var ctx = new org.springframework.boot.builder.SpringApplicationBuilder(
                    com.gitsolve.GitSolveApplication.class)
                    .properties(props)
                    .run();
            ctx.close();
            throw new AssertionError("Expected context startup to fail for: " + scenario);
        }).as("Context should fail to start when: " + scenario)
          .isInstanceOf(Exception.class);
    }
}
