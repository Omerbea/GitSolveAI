package com.gitsolve.agent.buildrepair;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitsolve.agent.buildclassifier.BuildFailure;
import com.gitsolve.agent.buildclassifier.BuildFailureType;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for BuildRepairService — all external dependencies mocked,
 * no Spring context.
 */
@Tag("unit")
class BuildRepairServiceTest {

    private BuildRepairAiService mockAiService;
    private BuildRepairService   service;

    private static final BuildFailure COMPILE_FAILURE = new BuildFailure(
            BuildFailureType.COMPILE_ERROR,
            "ServiceImpl.java:42",
            "cannot find symbol",
            "check imports"
    );

    @BeforeEach
    void setUp() {
        mockAiService = mock(BuildRepairAiService.class);
        service       = new BuildRepairService(mockAiService, new ObjectMapper());
    }

    // ------------------------------------------------------------------ //
    // TC-01 — COMPILE_ERROR routes to repairCompileError, returns hint   //
    // ------------------------------------------------------------------ //

    @Test
    void repair_compileError_routesToRepairCompileErrorAndReturnsHint() {
        // Arrange
        String expectedHint = "COMPILE_ERROR at ServiceImpl.java:42\nFix: check imports";
        when(mockAiService.repairCompileError(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Response.from(new AiMessage(expectedHint), null, null));

        // Act
        String result = service.repair(COMPILE_FAILURE, "raw build error");

        // Assert — hint is non-blank and contains the type tag
        assertThat(result).isNotBlank();
        assertThat(result).contains("COMPILE_ERROR");

        // Routing verification — only repairCompileError was called
        verify(mockAiService).repairCompileError(anyString(), anyString(), anyString(), anyString());
        verifyNoMoreInteractions(mockAiService);
    }

    // ------------------------------------------------------------------ //
    // TC-02 — LLM throws → falls back to rawBuildError, no exception     //
    // ------------------------------------------------------------------ //

    @Test
    void repair_llmThrows_fallsBackToRawBuildErrorWithoutThrowing() {
        // Arrange
        when(mockAiService.repairCompileError(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("LLM unavailable"));

        String rawError = "raw build error";

        // Act — must not throw
        String result = service.repair(COMPILE_FAILURE, rawError);

        // Assert — fallback is the original raw error
        assertThat(result).isEqualTo(rawError);
    }
}
