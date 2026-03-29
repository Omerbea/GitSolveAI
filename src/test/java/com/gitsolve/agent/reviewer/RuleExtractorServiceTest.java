package com.gitsolve.agent.reviewer;

import com.gitsolve.github.GitHubClient;
import com.gitsolve.model.ConstraintJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for RuleExtractorService — no Spring context.
 * GitHubClient and RuleExtractorAiService are Mockito mocks.
 */
@Tag("unit")
class RuleExtractorServiceTest {

    private static final String REPO = "apache/commons-lang";

    private RuleExtractorAiService aiService;
    private GitHubClient gitHubClient;
    private RuleExtractorService service;

    @BeforeEach
    void setUp() {
        aiService     = mock(RuleExtractorAiService.class);
        gitHubClient  = mock(GitHubClient.class);
        service       = new RuleExtractorService(aiService, gitHubClient);
    }

    // ------------------------------------------------------------------ //
    // Absent / empty / blank CONTRIBUTING.md — safe defaults returned     //
    // ------------------------------------------------------------------ //

    @Test
    void extract_absentContributingMd_returnsDefaultConstraints() {
        when(gitHubClient.getFileContent(eq(REPO), eq("CONTRIBUTING.md")))
                .thenReturn(Mono.empty());

        ConstraintJson result = service.extract(REPO);

        assertDefaultConstraints(result);
        verifyNoInteractions(aiService);
    }

    @Test
    void extract_emptyContributingMd_returnsDefaultConstraints() {
        when(gitHubClient.getFileContent(eq(REPO), eq("CONTRIBUTING.md")))
                .thenReturn(Mono.just(""));

        ConstraintJson result = service.extract(REPO);

        assertDefaultConstraints(result);
        verifyNoInteractions(aiService);
    }

    @Test
    void extract_blankContributingMd_returnsDefaultConstraints() {
        when(gitHubClient.getFileContent(eq(REPO), eq("CONTRIBUTING.md")))
                .thenReturn(Mono.just("   \n  "));

        ConstraintJson result = service.extract(REPO);

        assertDefaultConstraints(result);
        verifyNoInteractions(aiService);
    }

    // ------------------------------------------------------------------ //
    // Populated CONTRIBUTING.md — LLM response used                       //
    // ------------------------------------------------------------------ //

    @Test
    void extract_populatedContributingMd_returnsLlmExtractedConstraints() {
        String content = "# Contributing\nAll PRs must include tests.\n" +
                         "Use mvn verify to build.\nDo not use System.out.println.";
        ConstraintJson expected = new ConstraintJson(
                null,
                false,
                true,
                "21",
                "mvn verify",
                List.of("System.out.println")
        );

        when(gitHubClient.getFileContent(eq(REPO), eq("CONTRIBUTING.md")))
                .thenReturn(Mono.just(content));
        when(aiService.extractRules(eq(content))).thenReturn(expected);

        ConstraintJson result = service.extract(REPO);

        assertThat(result.requiresTests()).isTrue();
        assertThat(result.buildCommand()).isEqualTo("mvn verify");
        assertThat(result.forbiddenPatterns()).containsExactly("System.out.println");
        assertThat(result.jdkVersion()).isEqualTo("21");

        verify(aiService, times(1)).extractRules(eq(content));
    }

    // ------------------------------------------------------------------ //
    // Helper                                                               //
    // ------------------------------------------------------------------ //

    private static void assertDefaultConstraints(ConstraintJson c) {
        assertThat(c).isNotNull();
        assertThat(c.checkstyleConfig()).isNull();
        assertThat(c.requiresDco()).isFalse();
        assertThat(c.requiresTests()).isTrue();   // conservative default
        assertThat(c.forbiddenPatterns()).isEmpty();
    }
}
