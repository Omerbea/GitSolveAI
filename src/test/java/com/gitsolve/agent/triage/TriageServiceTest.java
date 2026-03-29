package com.gitsolve.agent.triage;

import com.gitsolve.model.GitIssue;
import com.gitsolve.model.IssueComplexity;
import com.gitsolve.model.TriageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TriageService — no Spring context, Mockito mocks.
 */
@Tag("unit")
class TriageServiceTest {

    private TriageAiService triageAiService;
    private TriageService   triageService;

    // A body long enough to pass the pre-LLM length check
    private static final String LONG_BODY = "A".repeat(200);

    @BeforeEach
    void setUp() {
        triageAiService = mock(TriageAiService.class);
        // Use real IssueSanitizer — it has no external dependencies
        triageService = new TriageService(triageAiService, new IssueSanitizer());
    }

    // ------------------------------------------------------------------ //
    // Pre-LLM rejection: LLM must NOT be called                           //
    // ------------------------------------------------------------------ //

    @Test
    void triage_rejectsShortBody_llmNotInvoked() {
        GitIssue issue = issue(1, "Fix NPE", "Too short", List.of("bug"));

        TriageResult result = triageService.triage(issue);

        assertThat(result.accepted()).isFalse();
        assertThat(result.reasoning()).isEqualTo("INSUFFICIENT_DESCRIPTION");
        verifyNoInteractions(triageAiService);
    }

    @Test
    void triage_rejectsNullBody_llmNotInvoked() {
        GitIssue issue = issue(2, "Fix NPE", null, List.of());

        TriageResult result = triageService.triage(issue);

        assertThat(result.accepted()).isFalse();
        verifyNoInteractions(triageAiService);
    }

    @Test
    void triage_rejectsWipTitle_llmNotInvoked() {
        GitIssue issue = issue(3, "[WIP] Fix something", LONG_BODY, List.of("bug"));

        TriageResult result = triageService.triage(issue);

        assertThat(result.accepted()).isFalse();
        assertThat(result.reasoning()).isEqualTo("WIP_ISSUE");
        verifyNoInteractions(triageAiService);
    }

    @Test
    void triage_rejectsWipTitleCaseInsensitive_llmNotInvoked() {
        GitIssue issue = issue(4, "wip: refactor thing", LONG_BODY, List.of());

        TriageResult result = triageService.triage(issue);

        assertThat(result.accepted()).isFalse();
        verifyNoInteractions(triageAiService);
    }

    @Test
    void triage_rejectsBlockedLabel_llmNotInvoked() {
        GitIssue issue = issue(5, "Fix NPE", LONG_BODY, List.of("blocked", "bug"));

        TriageResult result = triageService.triage(issue);

        assertThat(result.accepted()).isFalse();
        assertThat(result.reasoning()).isEqualTo("BLOCKED_LABEL");
        verifyNoInteractions(triageAiService);
    }

    @Test
    void triage_rejectsNeedsDiscussionLabel_llmNotInvoked() {
        GitIssue issue = issue(6, "Fix NPE", LONG_BODY, List.of("needs-discussion"));

        TriageResult result = triageService.triage(issue);

        assertThat(result.accepted()).isFalse();
        verifyNoInteractions(triageAiService);
    }

    // ------------------------------------------------------------------ //
    // Post-LLM rejections                                                 //
    // ------------------------------------------------------------------ //

    @Test
    void triage_rejectsRequiresUiWork() {
        when(triageAiService.analyzeIssue(any(), anyInt(), any(), any(), any()))
                .thenReturn(new TriageResponse("EASY", "Needs UI changes", true, true, false));

        TriageResult result = triageService.triage(issue(7, "Update button", LONG_BODY, List.of()));

        assertThat(result.accepted()).isFalse();
        assertThat(result.reasoning()).isEqualTo("REQUIRES_UI_WORK");
    }

    @Test
    void triage_rejectsRequiresDbMigration() {
        when(triageAiService.analyzeIssue(any(), anyInt(), any(), any(), any()))
                .thenReturn(new TriageResponse("MEDIUM", "Needs DB column", true, false, true));

        TriageResult result = triageService.triage(issue(8, "Add column", LONG_BODY, List.of()));

        assertThat(result.accepted()).isFalse();
        assertThat(result.reasoning()).isEqualTo("REQUIRES_DB_MIGRATION");
    }

    @Test
    void triage_rejectsMediumWithoutClearSteps() {
        when(triageAiService.analyzeIssue(any(), anyInt(), any(), any(), any()))
                .thenReturn(new TriageResponse("MEDIUM", "Unclear scope", false, false, false));

        TriageResult result = triageService.triage(issue(9, "Improve something", LONG_BODY, List.of()));

        assertThat(result.accepted()).isFalse();
        assertThat(result.reasoning()).isEqualTo("MEDIUM_WITHOUT_CLEAR_STEPS");
    }

    // ------------------------------------------------------------------ //
    // Accepted cases                                                       //
    // ------------------------------------------------------------------ //

    @Test
    void triage_acceptsEasyWithClearSteps() {
        when(triageAiService.analyzeIssue(any(), anyInt(), any(), any(), any()))
                .thenReturn(new TriageResponse("EASY", "Clear NPE fix.", true, false, false));

        TriageResult result = triageService.triage(issue(10, "Fix NPE", LONG_BODY, List.of("bug")));

        assertThat(result.accepted()).isTrue();
        assertThat(result.complexity()).isEqualTo(IssueComplexity.EASY);
        assertThat(result.reasoning()).isEqualTo("Clear NPE fix.");
    }

    @Test
    void triage_acceptsMediumWithClearSteps() {
        when(triageAiService.analyzeIssue(any(), anyInt(), any(), any(), any()))
                .thenReturn(new TriageResponse("MEDIUM", "Refactor with clear steps.", true, false, false));

        TriageResult result = triageService.triage(issue(11, "Refactor X", LONG_BODY, List.of()));

        assertThat(result.accepted()).isTrue();
        assertThat(result.complexity()).isEqualTo(IssueComplexity.MEDIUM);
    }

    // ------------------------------------------------------------------ //
    // Batch ordering                                                       //
    // ------------------------------------------------------------------ //

    @Test
    void triageBatch_sortsEasyBeforeMedium() {
        GitIssue easyIssue   = issue(20, "Easy fix",   LONG_BODY, List.of());
        GitIssue mediumIssue = issue(21, "Medium fix",  LONG_BODY, List.of());
        GitIssue rejected    = issue(22, "Short",       "x",       List.of());

        when(triageAiService.analyzeIssue(any(), eq(20), any(), any(), any()))
                .thenReturn(new TriageResponse("EASY", "Simple.", true, false, false));
        when(triageAiService.analyzeIssue(any(), eq(21), any(), any(), any()))
                .thenReturn(new TriageResponse("MEDIUM", "Moderate.", true, false, false));

        List<TriageResult> results = triageService.triageBatch(
                List.of(mediumIssue, easyIssue, rejected));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).complexity()).isEqualTo(IssueComplexity.EASY);
        assertThat(results.get(1).complexity()).isEqualTo(IssueComplexity.MEDIUM);
    }

    @Test
    void triageBatch_excludesRejectedIssues() {
        GitIssue accepted = issue(30, "Fix NPE", LONG_BODY, List.of());
        GitIssue rejected = issue(31, "Too short", "x", List.of());

        when(triageAiService.analyzeIssue(any(), eq(30), any(), any(), any()))
                .thenReturn(new TriageResponse("EASY", "Simple.", true, false, false));

        List<TriageResult> results = triageService.triageBatch(List.of(accepted, rejected));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).issue().issueNumber()).isEqualTo(30);
    }

    // ------------------------------------------------------------------ //
    // Error cases                                                          //
    // ------------------------------------------------------------------ //

    @Test
    void triage_invalidComplexity_throwsDescriptiveException() {
        when(triageAiService.analyzeIssue(any(), anyInt(), any(), any(), any()))
                .thenReturn(new TriageResponse("HARD", "Unknown.", true, false, false));

        assertThatThrownBy(() -> triageService.triage(issue(40, "Fix NPE", LONG_BODY, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HARD");
    }

    // ------------------------------------------------------------------ //
    // Helper                                                               //
    // ------------------------------------------------------------------ //

    private static GitIssue issue(int number, String title, String body, List<String> labels) {
        return new GitIssue("apache/commons-lang", number, title, body,
                "https://github.com/apache/commons-lang/issues/" + number, labels);
    }
}
