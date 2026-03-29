package com.gitsolve.agent.triage;

import com.gitsolve.model.IssueComplexity;
import com.gitsolve.model.GitIssue;
import com.gitsolve.model.TriageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Triage Agent orchestrator.
 *
 * Applies deterministic rejection rules (pre- and post-LLM) and delegates
 * complexity classification to the LLM via TriageAiService.
 *
 * Pre-LLM rejection rules (no LLM call made):
 *   - Body length < 100 chars  → INSUFFICIENT_DESCRIPTION
 *   - Title contains 'wip'     → WIP_ISSUE
 *   - Labels contain 'blocked' or 'needs-discussion' → BLOCKED_LABEL
 *
 * Post-LLM rejection rules:
 *   - requiresUiWork == true       → REQUIRES_UI_WORK
 *   - requiresDbMigration == true  → REQUIRES_DB_MIGRATION
 *   - MEDIUM + !hasClearSteps      → MEDIUM_WITHOUT_CLEAR_STEPS
 *
 * Invalid complexity values throw IllegalArgumentException.
 */
@Service
public class TriageService {

    private static final Logger log = LoggerFactory.getLogger(TriageService.class);
    private static final int MIN_BODY_LENGTH = 100;

    private final TriageAiService triageAiService;
    private final IssueSanitizer sanitizer;

    public TriageService(TriageAiService triageAiService, IssueSanitizer sanitizer) {
        this.triageAiService = triageAiService;
        this.sanitizer       = sanitizer;
    }

    // ------------------------------------------------------------------ //
    // Public API                                                           //
    // ------------------------------------------------------------------ //

    /**
     * Triages a single issue.
     * Never throws to caller except for invalid complexity values from the LLM.
     */
    public TriageResult triage(GitIssue issue) {
        // --- Pre-LLM deterministic rejections ---
        String bodyLength = issue.body();
        if (bodyLength == null || bodyLength.length() < MIN_BODY_LENGTH) {
            return rejected(issue, null, "INSUFFICIENT_DESCRIPTION");
        }

        String titleLower = issue.title() != null ? issue.title().toLowerCase() : "";
        if (titleLower.contains("wip") || titleLower.contains("[wip]")) {
            return rejected(issue, null, "WIP_ISSUE");
        }

        if (issue.labels() != null) {
            boolean blocked = issue.labels().stream()
                    .anyMatch(l -> l.equalsIgnoreCase("blocked")
                            || l.equalsIgnoreCase("needs-discussion"));
            if (blocked) {
                return rejected(issue, null, "BLOCKED_LABEL");
            }
        }

        // --- LLM classification ---
        String sanitizedBody = sanitizer.sanitize(issue.body());
        String labelsStr = issue.labels() != null
                ? String.join(", ", issue.labels())
                : "";

        TriageResponse response = triageAiService.analyzeIssue(
                issue.repoFullName(),
                issue.issueNumber(),
                issue.title(),
                sanitizedBody,
                labelsStr
        );

        // --- Validate complexity ---
        IssueComplexity complexity = parseComplexity(response.complexity());

        // --- Post-LLM deterministic rejections ---
        if (response.requiresUiWork()) {
            return rejected(issue, complexity, "REQUIRES_UI_WORK");
        }
        if (response.requiresDbMigration()) {
            return rejected(issue, complexity, "REQUIRES_DB_MIGRATION");
        }
        if (!response.hasClearSteps() && complexity == IssueComplexity.MEDIUM) {
            return rejected(issue, complexity, "MEDIUM_WITHOUT_CLEAR_STEPS");
        }

        log.info("Triage: accepted #{} ({}) — {}", issue.issueNumber(), complexity,
                response.reasoning());
        return new TriageResult(issue, complexity, response.reasoning(), true);
    }

    /**
     * Triages a batch of issues.
     * Returns only accepted results, sorted EASY before MEDIUM.
     */
    public List<TriageResult> triageBatch(List<GitIssue> issues) {
        return issues.stream()
                .map(this::triage)
                .filter(TriageResult::accepted)
                .sorted(Comparator.comparing(r ->
                        r.complexity() == IssueComplexity.EASY ? 0 : 1))
                .collect(Collectors.toList());
    }

    // ------------------------------------------------------------------ //
    // Private helpers                                                      //
    // ------------------------------------------------------------------ //

    private static TriageResult rejected(GitIssue issue, IssueComplexity complexity, String reason) {
        LoggerFactory.getLogger(TriageService.class)
                .debug("Triage: rejected #{} — {}", issue.issueNumber(), reason);
        // Use EASY as placeholder complexity for rejected issues (never acted on)
        IssueComplexity c = complexity != null ? complexity : IssueComplexity.EASY;
        return new TriageResult(issue, c, reason, false);
    }

    /**
     * Parses the LLM's complexity string into an enum value.
     * Throws IllegalArgumentException with a descriptive message for unknown values.
     */
    private static IssueComplexity parseComplexity(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException(
                    "TriageAiService returned null complexity — expected 'EASY' or 'MEDIUM'");
        }
        return switch (raw.trim().toUpperCase()) {
            case "EASY"   -> IssueComplexity.EASY;
            case "MEDIUM" -> IssueComplexity.MEDIUM;
            default -> throw new IllegalArgumentException(
                    "TriageAiService returned unexpected complexity value: '" + raw
                    + "' — expected 'EASY' or 'MEDIUM'");
        };
    }
}
