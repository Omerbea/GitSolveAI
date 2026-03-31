package com.gitsolve.agent.buildclassifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for BuildFailureClassifier — all external dependencies mocked,
 * no Spring context.
 */
@Tag("unit")
class BuildFailureClassifierTest {

    private BuildFailureClassifierAiService mockAiService;
    private BuildFailureClassifier          service;

    @BeforeEach
    void setUp() {
        mockAiService = mock(BuildFailureClassifierAiService.class);
        service       = new BuildFailureClassifier(mockAiService, new ObjectMapper());
    }

    // ------------------------------------------------------------------ //
    // TC-01 — realistic mvn compile error → COMPILE_ERROR with location  //
    // ------------------------------------------------------------------ //

    @Test
    void classify_compilationError_returnsCompileErrorWithLocation() throws Exception {
        // Arrange
        String json = """
                {"type":"COMPILE_ERROR","location":"ServiceImpl.java:42",\
"excerpt":"error: cannot find symbol","suggestedFix":"check method signature"}
                """.strip();

        when(mockAiService.classify(anyString()))
                .thenReturn(Response.from(new AiMessage(json), null, null));

        // Act
        BuildFailure result = service.classify(
                "[ERROR] ServiceImpl.java:42: error: cannot find symbol");

        // Assert
        assertThat(result.type()).isEqualTo(BuildFailureType.COMPILE_ERROR);
        assertThat(result.location()).contains("ServiceImpl.java:42");
    }

    // ------------------------------------------------------------------ //
    // TC-02 — LLM throws → LLM_HALLUCINATION fallback, no exception      //
    // ------------------------------------------------------------------ //

    @Test
    void classify_llmThrows_returnsHallucinationFallbackWithoutThrowing() throws Exception {
        // Arrange
        when(mockAiService.classify(anyString()))
                .thenThrow(new RuntimeException("LLM unavailable"));

        // Act — must not throw
        BuildFailure result = service.classify("garbage input %%% @@@");

        // Assert
        assertThat(result.type()).isEqualTo(BuildFailureType.LLM_HALLUCINATION);
    }
}
