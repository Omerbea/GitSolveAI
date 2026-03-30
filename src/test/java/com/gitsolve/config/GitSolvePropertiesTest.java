package com.gitsolve.config;

import com.gitsolve.persistence.IssueStore;
import com.gitsolve.persistence.SettingsStore;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that GitSolveProperties binds correctly when all required fields are present.
 * Excludes DB/JPA auto-configuration to avoid needing a live Postgres in unit tests.
 * MockBean IssueStore/SettingsStore prevents wiring failure when JPA is excluded.
 */
@Tag("unit")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = { com.gitsolve.GitSolveApplication.class }
)
@ActiveProfiles("test")
class GitSolvePropertiesTest {

    @MockBean IssueStore    issueStore;
    @MockBean SettingsStore settingsStore;

    @Autowired
    GitSolveProperties props;

    @Test
    void propertiesBindCorrectly() {
        assertThat(props.github().appToken()).isEqualTo("test-token");
        assertThat(props.github().maxReposPerRun()).isEqualTo(10);
        assertThat(props.github().maxIssuesPerRepo()).isEqualTo(5);

        assertThat(props.llm().provider()).isEqualTo("anthropic");
        assertThat(props.llm().model()).isEqualTo("claude-3-5-sonnet-20241022");
        assertThat(props.llm().maxTokensPerRun()).isEqualTo(500_000);
        assertThat(props.llm().maxIterationsPerFix()).isEqualTo(5);

        assertThat(props.docker().image()).isEqualTo("eclipse-temurin:21-jdk");
        assertThat(props.docker().memLimitMb()).isEqualTo(1024L);
        assertThat(props.docker().cpuQuota()).isEqualTo(50_000L);
        assertThat(props.docker().buildTimeoutSeconds()).isEqualTo(300);

        assertThat(props.schedule().cron()).isEqualTo("0 0 0 * * *");
    }
}
