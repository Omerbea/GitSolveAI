package com.gitsolve.agent.buildprofile;

import com.gitsolve.docker.BuildEnvironment;
import com.gitsolve.docker.BuildOutput;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BuildProfileInspector — no Spring context, pure Mockito mocks.
 */
@Tag("unit")
class BuildProfileInspectorTest {

    private BuildProfileInspectorAiService mockAiService;
    private BuildEnvironment mockEnv;
    private BuildProfileInspector inspector;

    @BeforeEach
    void setUp() {
        mockAiService = mock(BuildProfileInspectorAiService.class);
        mockEnv       = mock(BuildEnvironment.class);
        inspector     = new BuildProfileInspector(mockAiService, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    // ------------------------------------------------------------------ //
    // TC-01: README with custom build command → extracts correctly        //
    // ------------------------------------------------------------------ //

    @Test
    void inspect_readmeWithCustomBuildCommand_extractsCorrectly() throws Exception {
        when(mockEnv.readFile("README.md"))
                .thenReturn(Optional.of("## Build\n Run `./mvnw clean package -DskipTests` to build."));
        when(mockEnv.readFile("CONTRIBUTING.md"))
                .thenReturn(Optional.empty());
        when(mockEnv.runBuild(anyString()))
                .thenReturn(new BuildOutput("java 17", "", 0, false, Duration.ofMillis(10)));

        String json = """
                {"buildTool":"maven","wrapperPath":"./mvnw","buildCommand":"./mvnw clean package -DskipTests","jdkConstraint":"17","customPrereqs":[],"notes":""}
                """.trim();
        when(mockAiService.inspect(anyString(), anyString()))
                .thenReturn(Response.from(new AiMessage(json), null, null));

        BuildProfile profile = inspector.inspect(mockEnv);

        assertThat(profile.buildTool()).isEqualTo("maven");
        assertThat(profile.buildCommand()).isEqualTo("./mvnw clean package -DskipTests");
    }

    // ------------------------------------------------------------------ //
    // TC-02: Empty docs + LLM failure → returns default profile           //
    // ------------------------------------------------------------------ //

    @Test
    void inspect_emptyDocs_returnsDefaultProfile() throws Exception {
        when(mockEnv.readFile("README.md"))
                .thenReturn(Optional.empty());
        when(mockEnv.readFile("CONTRIBUTING.md"))
                .thenReturn(Optional.empty());
        when(mockEnv.runBuild(anyString()))
                .thenReturn(new BuildOutput("", "", 0, false, Duration.ofMillis(10)));
        when(mockAiService.inspect(anyString(), anyString()))
                .thenThrow(new RuntimeException("LLM unavailable"));

        BuildProfile profile = inspector.inspect(mockEnv);

        assertThat(profile.buildTool()).isEqualTo("maven");
        assertThat(profile.buildCommand()).isEqualTo("mvn clean package");
    }
}
