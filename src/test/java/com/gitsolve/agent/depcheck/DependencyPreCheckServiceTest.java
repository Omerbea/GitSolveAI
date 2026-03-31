package com.gitsolve.agent.depcheck;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitsolve.agent.buildprofile.BuildProfile;
import com.gitsolve.docker.BuildEnvironment;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for DependencyPreCheckService — all external dependencies mocked,
 * no Spring context.
 */
@Tag("unit")
class DependencyPreCheckServiceTest {

    private DependencyPreCheckAiService mockAiService;
    private BuildEnvironment            mockEnv;
    private DependencyPreCheckService   service;

    @BeforeEach
    void setUp() {
        mockAiService = mock(DependencyPreCheckAiService.class);
        mockEnv       = mock(BuildEnvironment.class);
        service       = new DependencyPreCheckService(mockAiService, new ObjectMapper());
    }

    // ------------------------------------------------------------------ //
    // TC-01 — pom.xml with a suspicious SNAPSHOT dep returns true        //
    // ------------------------------------------------------------------ //

    @Test
    void check_pomWithSuspiciousDep_returnsTrueWithFindings() throws Exception {
        // Arrange
        String pomContent = "<dependency><groupId>com.example</groupId>"
                + "<artifactId>snapshot-lib</artifactId>"
                + "<version>1.0-SNAPSHOT</version></dependency>";
        when(mockEnv.readFile("pom.xml")).thenReturn(Optional.of(pomContent));

        BuildProfile buildProfile = new BuildProfile(
                "maven", null, "mvn clean package", null, List.of(), "");

        String llmJson = "{\"hasSuspiciousDeps\":true,"
                + "\"findings\":[\"snapshot-lib 1.0-SNAPSHOT is a snapshot dependency\"],"
                + "\"notes\":\"\"}";
        when(mockAiService.check(anyString()))
                .thenReturn(Response.from(new AiMessage(llmJson), null, null));

        // Act
        DependencyCheckResult result = service.check(mockEnv, buildProfile);

        // Assert
        assertThat(result.hasSuspiciousDeps()).isTrue();
        assertThat(result.findings()).isNotEmpty();
        assertThat(result.findings().get(0)).contains("snapshot-lib");
    }

    // ------------------------------------------------------------------ //
    // TC-02 — LLM throws, service returns noIssues() safely              //
    // ------------------------------------------------------------------ //

    @Test
    void check_llmThrows_returnsNoIssues() throws Exception {
        // Arrange
        when(mockEnv.readFile("pom.xml")).thenReturn(Optional.of("<project/>"));

        BuildProfile buildProfile = new BuildProfile(
                "maven", null, "mvn clean package", null, List.of(), "");

        when(mockAiService.check(anyString()))
                .thenThrow(new RuntimeException("LLM unavailable"));

        // Act
        DependencyCheckResult result = service.check(mockEnv, buildProfile);

        // Assert — never throws, returns safe default
        assertThat(result.hasSuspiciousDeps()).isFalse();
        assertThat(result.findings()).isEmpty();
    }
}
