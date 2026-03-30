package com.gitsolve.agent.reviewer;

import com.gitsolve.model.ConstraintJson;
import com.gitsolve.model.FixResult;
import com.gitsolve.model.GitIssue;
import com.gitsolve.model.ReviewResult;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ReviewerService — no Spring context, pure Mockito mocks.
 *
 * RuleExtractorService is mocked to return a ConstraintJson with
 * forbiddenPatterns=["System.out.println"] for all tests.
 * ReviewerAiService is mocked per-test to return specific JSON responses.
 */
@Tag("unit")
class ReviewerServiceTest {

    private static final GitIssue TEST_ISSUE = new GitIssue(
            "apache/commons-lang", 99, "Fix null handling",
            "There is a null dereference in StringUtils.",
            "https://github.com/apache/commons-lang/issues/99",
            List.of("good first issue")
    );

    private static final ConstraintJson CONSTRAINTS_WITH_FORBIDDEN = new ConstraintJson(
            null, false, true, "21", "mvn test", List.of("System.out.println")
    );

    private ReviewerAiService    mockReviewerAiService;
    private RuleExtractorService mockRuleExtractorService;
    private ReviewerService      service;

    @BeforeEach
    void setUp() {
        mockReviewerAiService    = mock(ReviewerAiService.class);
        mockRuleExtractorService = mock(RuleExtractorService.class);
        service = new ReviewerService(mockReviewerAiService, mockRuleExtractorService);

        // Default: constraints always returned from mock
        when(mockRuleExtractorService.extract(anyString()))
                .thenReturn(CONSTRAINTS_WITH_FORBIDDEN);
    }

    // ------------------------------------------------------------------ //
    // TC-01: Fix that violates a constraint → approved=false              //
    // ------------------------------------------------------------------ //

    @Test
    void review_violatingFix_returnsApprovedFalseWithViolations() {
        String violationMsg = "System.out.println is forbidden by governance constraints";
        String responseJson = """
                {
                  "approved": false,
                  "violations": ["%s"],
                  "summary": "Fix uses System.out.println which is explicitly forbidden."
                }
                """.formatted(violationMsg);

        when(mockReviewerAiService.reviewFix(anyString(), anyString(), anyString()))
                .thenReturn(Response.from(new AiMessage(responseJson), null, null));

        ReviewResult result = service.review(buildFixResult("+System.out.println(\"debug\");"), TEST_ISSUE);

        assertThat(result.approved()).isFalse();
        assertThat(result.violations()).hasSize(1);
        assertThat(result.violations().get(0)).contains("System.out.println");
        assertThat(result.summary()).isNotBlank();
    }

    // ------------------------------------------------------------------ //
    // TC-02: Clean fix → approved=true, empty violations                  //
    // ------------------------------------------------------------------ //

    @Test
    void review_cleanFix_returnsApprovedTrueWithNoViolations() {
        String responseJson = """
                {
                  "approved": true,
                  "violations": [],
                  "summary": "Fix looks correct and follows all governance constraints."
                }
                """;

        when(mockReviewerAiService.reviewFix(anyString(), anyString(), anyString()))
                .thenReturn(Response.from(new AiMessage(responseJson), null, null));

        ReviewResult result = service.review(buildFixResult("+Objects.requireNonNull(value);"), TEST_ISSUE);

        assertThat(result.approved()).isTrue();
        assertThat(result.violations()).isEmpty();
        assertThat(result.summary()).isNotBlank();
    }

    // ------------------------------------------------------------------ //
    // TC-03: LLM throws → approved=false, error in violations, no throw   //
    // ------------------------------------------------------------------ //

    @Test
    void review_llmThrows_returnsApprovedFalseWithErrorMessage() {
        when(mockReviewerAiService.reviewFix(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("LLM unavailable"));

        ReviewResult result = service.review(buildFixResult("+// some change"), TEST_ISSUE);

        assertThat(result.approved()).isFalse();
        assertThat(result.violations()).isNotEmpty();
        assertThat(result.violations().get(0)).contains("Reviewer error");
        // Must not throw to the caller
    }

    // ------------------------------------------------------------------ //
    // TC-04: JSON with code fences → parsed correctly                     //
    // ------------------------------------------------------------------ //

    @Test
    void review_jsonWithCodeFences_parsedCorrectly() {
        String responseJson = """
                ```json
                {
                  "approved": true,
                  "violations": [],
                  "summary": "All constraints satisfied."
                }
                ```""";

        when(mockReviewerAiService.reviewFix(anyString(), anyString(), anyString()))
                .thenReturn(Response.from(new AiMessage(responseJson), null, null));

        ReviewResult result = service.review(buildFixResult("+// clean change"), TEST_ISSUE);

        assertThat(result.approved()).isTrue();
        assertThat(result.violations()).isEmpty();
    }

    // ------------------------------------------------------------------ //
    // Helper                                                               //
    // ------------------------------------------------------------------ //

    private static FixResult buildFixResult(String diff) {
        return new FixResult(
                "apache/commons-lang#99",
                true,
                List.of(),
                diff,
                null
        );
    }
}
