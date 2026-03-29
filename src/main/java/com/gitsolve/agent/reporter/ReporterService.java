package com.gitsolve.agent.reporter;

import com.gitsolve.model.FixAttempt;
import com.gitsolve.model.FixReport;
import com.gitsolve.model.FixResult;
import com.gitsolve.model.GitIssue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Assembles a {@link FixReport} from a completed SWE Agent fix attempt.
 *
 * <p>No LLM call is made — the report is compiled entirely from data the SWE Agent
 * already produced: FixAttempts (file path, diff, build outcome) and the original
 * GitIssue (title, body, URL). This means a report is available even when the build
 * failed.
 *
 * <p>Never throws to the caller — returns a minimal safe report on any error.
 */
@Service
public class ReporterService {

    private static final Logger log = LoggerFactory.getLogger(ReporterService.class);
    private static final int MAX_BODY_EXCERPT = 500;

    // ------------------------------------------------------------------ //
    // Public API                                                           //
    // ------------------------------------------------------------------ //

    /**
     * Generates a fix report from the SWE Agent's output.
     *
     * @param fixResult the completed fix attempt (success or failure)
     * @param issue     the original GitHub issue
     * @return a non-null {@link FixReport}
     */
    public FixReport generateReport(FixResult fixResult, GitIssue issue) {
        try {
            return buildReport(fixResult, issue);
        } catch (Exception e) {
            log.error("[{}#{}] Failed to generate fix report: {}",
                    issue.repoFullName(), issue.issueNumber(), e.getMessage(), e);
            return minimalReport(issue);
        }
    }

    // ------------------------------------------------------------------ //
    // Private helpers                                                      //
    // ------------------------------------------------------------------ //

    private FixReport buildReport(FixResult fixResult, GitIssue issue) {
        List<FixAttempt> attempts = fixResult.attempts() != null
                ? fixResult.attempts()
                : List.of();

        // Find the last attempt that actually wrote a file (best proposed fix)
        FixAttempt lastAttempt = attempts.isEmpty() ? null : attempts.get(attempts.size() - 1);
        FixAttempt bestAttempt = attempts.stream()
                .filter(a -> a.modifiedFilePath() != null && !a.modifiedFilePath().isBlank())
                .reduce((first, second) -> second)   // last one with a file
                .orElse(lastAttempt);

        String proposedFilePath = bestAttempt != null ? bestAttempt.modifiedFilePath() : "";
        String unifiedDiff      = bestAttempt != null ? bestAttempt.patchDiff()        : "";

        // Use the finalDiff from FixResult if available (git diff HEAD after last write)
        if (fixResult.finalDiff() != null && !fixResult.finalDiff().isBlank()) {
            unifiedDiff = fixResult.finalDiff();
        }

        // Build iteration history
        List<String> history = new ArrayList<>();
        for (FixAttempt attempt : attempts) {
            String outcome = attempt.buildPassed() ? "✅ BUILD PASSED" : "❌ BUILD FAILED";
            String file    = attempt.modifiedFilePath() != null && !attempt.modifiedFilePath().isBlank()
                    ? " → " + attempt.modifiedFilePath()
                    : "";
            history.add("Iteration " + attempt.iterationNumber() + ": " + outcome + file);
        }

        // Build status
        String buildStatus;
        if (attempts.isEmpty()) {
            buildStatus = "NOT_RUN";
        } else if (fixResult.success()) {
            buildStatus = "PASSED";
        } else {
            buildStatus = "FAILED";
        }

        // Root cause: first 500 chars of the issue body is the maintainer's description
        String bodyExcerpt = excerpt(issue.body());

        // Root cause analysis: combine issue body context with failure reason
        String rootCause = bodyExcerpt;
        if (!fixResult.success() && fixResult.failureReason() != null) {
            rootCause += "\n\nSWE Agent outcome: " + fixResult.failureReason();
        }

        // Issue URL
        String issueUrl = issue.htmlUrl() != null
                ? issue.htmlUrl()
                : "https://github.com/" + issue.repoFullName() + "/issues/" + issue.issueNumber();

        return new FixReport(
                issue.title(),
                issueUrl,
                bodyExcerpt,
                rootCause,
                proposedFilePath,
                "",   // proposedCode: not stored in FixAttempt — would need SweFixResponse change
                unifiedDiff,
                buildStatus,
                attempts.size(),
                history,
                Instant.now()
        );
    }

    private static String excerpt(String text) {
        if (text == null) return "";
        return text.length() <= MAX_BODY_EXCERPT ? text : text.substring(0, MAX_BODY_EXCERPT) + "…";
    }

    private static FixReport minimalReport(GitIssue issue) {
        return new FixReport(
                issue.title(),
                issue.htmlUrl() != null ? issue.htmlUrl() : "",
                excerpt(issue.body()),
                "",
                "",
                "",
                "",
                "NOT_RUN",
                0,
                List.of(),
                Instant.now()
        );
    }
}
