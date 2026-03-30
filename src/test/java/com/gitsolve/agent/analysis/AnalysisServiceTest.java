package com.gitsolve.agent.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitsolve.model.GitIssue;
import com.gitsolve.model.IssueComplexity;
import com.gitsolve.model.TriageResult;
import com.gitsolve.repocache.RepoCache;
import com.gitsolve.repocache.RepoCacheException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AnalysisService — sourceContext wiring.
 */
@ExtendWith(MockitoExtension.class)
class AnalysisServiceTest {

    @Mock private AnalysisAiService aiService;
    @Mock private RepoCache         repoCache;

    private AnalysisService service;

    private static final String VALID_JSON = """
            {
              "rootCauseAnalysis": "null pointer in Foo",
              "affectedFiles": ["src/main/java/com/example/Foo.java"],
              "suggestedApproach": "Add null check.",
              "estimatedComplexity": "EASY",
              "relevantPatterns": "null guard"
            }
            """;

    @BeforeEach
    void setUp() {
        service = new AnalysisService(aiService, new ObjectMapper(), repoCache);
    }

    // ------------------------------------------------------------------ //
    // TC-01: sourceContext is populated from a real temp directory        //
    // ------------------------------------------------------------------ //

    @Test
    void tc01_sourceContextIsPassedToAiService(@TempDir Path tempDir) throws Exception {
        // Arrange: create src/main/java tree with one .java file
        Path javaRoot = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(javaRoot);
        Path javaFile = javaRoot.resolve("Foo.java");
        Files.writeString(javaFile, "public class Foo { /* analysis */ }");

        when(repoCache.ensureFork(anyString())).thenReturn(tempDir);
        when(aiService.analyse(any(), anyInt(), any(), any(), any(), any()))
                .thenReturn(VALID_JSON);

        GitIssue issue = new GitIssue("owner/repo", 1, "Fix null analysis in Foo", "Foo crashes", null, List.of());
        TriageResult triage = new TriageResult(issue, IssueComplexity.EASY, "looks easy", true);

        // Act
        AnalysisResult result = service.analyse(issue, triage);

        // Assert: non-null result with expected complexity
        assertThat(result.estimatedComplexity()).isEqualTo("EASY");

        // Capture sourceContext argument (6th param)
        ArgumentCaptor<String> sourceContextCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiService).analyse(
                eq("owner/repo"), eq(1), eq("Fix null analysis in Foo"),
                any(), any(), sourceContextCaptor.capture());
        String capturedContext = sourceContextCaptor.getValue();
        assertThat(capturedContext)
                .as("sourceContext should contain the FILE header for Foo.java")
                .contains("// FILE:")
                .contains("Foo.java");
    }

    // ------------------------------------------------------------------ //
    // TC-02: RepoCacheException → empty sourceContext, AI call still runs //
    // ------------------------------------------------------------------ //

    @Test
    void tc02_repoCacheExceptionFallsBackToEmptySourceContext() throws Exception {
        // Arrange: ensureFork throws
        when(repoCache.ensureFork(anyString()))
                .thenThrow(new RepoCacheException("git clone failed"));
        when(aiService.analyse(any(), anyInt(), any(), any(), any(), any()))
                .thenReturn(VALID_JSON);

        GitIssue issue = new GitIssue("owner/repo", 2, "Bug in Bar", "Bar is broken", null, List.of());
        TriageResult triage = new TriageResult(issue, IssueComplexity.EASY, "trivial", true);

        // Act
        AnalysisResult result = service.analyse(issue, triage);

        // Assert: result is valid (not the error fallback)
        assertThat(result.estimatedComplexity()).isEqualTo("EASY");
        assertThat(result.rootCauseAnalysis()).doesNotContain("Analysis failed");

        // sourceContext must be empty string on cache miss
        ArgumentCaptor<String> sourceContextCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiService).analyse(any(), anyInt(), any(), any(), any(), sourceContextCaptor.capture());
        assertThat(sourceContextCaptor.getValue())
                .as("sourceContext should be empty when repoCache throws")
                .isEmpty();
    }
}
