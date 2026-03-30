package com.gitsolve.agent.instructions;

import com.gitsolve.github.GitHubClient;
import com.gitsolve.model.FixReport;
import com.gitsolve.model.PhaseStats;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Fix Instructions Service.
 *
 * Fetches README, CONTRIBUTING.md, and last 10 commits from GitHub,
 * then calls the FixInstructionsAiService to produce a structured
 * prompt that tells an AI coding agent how to implement the fix
 * following this repository's specific conventions.
 *
 * Never throws — returns an error message string on any failure.
 */
@Service
public class FixInstructionsService {

    private static final Logger log = LoggerFactory.getLogger(FixInstructionsService.class);

    private final FixInstructionsAiService aiService;
    private final GitHubClient             gitHubClient;

    public FixInstructionsService(FixInstructionsAiService aiService, GitHubClient gitHubClient) {
        this.aiService    = aiService;
        this.gitHubClient = gitHubClient;
    }

    /**
     * Generates fix instructions for a given issue and its analysis report.
     *
     * @param repoFullName "owner/repo"
     * @param issueNumber  GitHub issue number
     * @param issueTitle   Issue title
     * @param report       The FixReport (analysis) already stored for this issue
     * @return A non-null string with the fix instructions, or an error description.
     */
    public String generate(String repoFullName, int issueNumber, String issueTitle, FixReport report) {
        String issueId = repoFullName + "#" + issueNumber;
        log.info("[FixInstructions] Generating for {}", issueId);

        try {
            // Fetch README (cap at 3000 chars to stay within token budget)
            String readme = gitHubClient.getFileContent(repoFullName, "README.md")
                    .switchIfEmpty(gitHubClient.getFileContent(repoFullName, "readme.md"))
                    .blockOptional()
                    .orElse("(README not found)");
            readme = readme.length() > 3000 ? readme.substring(0, 3000) + "\n...(truncated)" : readme;

            // Fetch CONTRIBUTING.md (optional)
            String contributing = gitHubClient.getFileContent(repoFullName, "CONTRIBUTING.md")
                    .switchIfEmpty(gitHubClient.getFileContent(repoFullName, "contributing.md"))
                    .blockOptional()
                    .orElse("(No CONTRIBUTING.md found)");
            contributing = contributing.length() > 2000
                    ? contributing.substring(0, 2000) + "\n...(truncated)"
                    : contributing;

            // Fetch last 10 commits
            List<String> commits = gitHubClient.getRecentCommits(repoFullName, 10).block();
            String recentCommits = commits != null && !commits.isEmpty()
                    ? String.join("\n", commits)
                    : "(No commits fetched)";

            // Extract fields from FixReport
            String rootCause       = report != null ? nvl(report.rootCauseAnalysis()) : "(none)";
            String affectedFiles   = report != null ? nvl(report.proposedFilePath())  : "(none)";
            String approach        = report != null ? nvl(report.proposedCode())      : "(none)";
            String patterns        = report != null && report.iterationHistory() != null
                    && report.iterationHistory().size() > 1
                    ? report.iterationHistory().get(1).replace("Patterns: ", "")
                    : "(none)";

            log.info("[FixInstructions] Context fetched for {} — readme={}chars contributing={}chars commits={}",
                    issueId, readme.length(), contributing.length(),
                    commits != null ? commits.size() : 0);

            long start = System.currentTimeMillis();
            Response<String> response = aiService.generateInstructions(
                    repoFullName, issueNumber, issueTitle,
                    rootCause, affectedFiles, approach, patterns,
                    readme, contributing, recentCommits
            );
            long durationMs = System.currentTimeMillis() - start;
            String result = response.content();

            // Capture token usage for observability (T02 will thread into domain model)
            TokenUsage tu = response.tokenUsage();
            @SuppressWarnings("unused")
            PhaseStats phaseStats = new PhaseStats(
                    tu != null && tu.inputTokenCount()  != null ? tu.inputTokenCount()  : 0,
                    tu != null && tu.outputTokenCount() != null ? tu.outputTokenCount() : 0,
                    "fix-instructions",
                    durationMs,
                    ""
            );

            log.info("[FixInstructions] Generated {}chars for {}", result.length(), issueId);
            return result;

        } catch (Exception e) {
            log.error("[FixInstructions] Failed for {}: {}", issueId, e.getMessage());
            return "Error generating fix instructions: " + e.getMessage();
        }
    }

    private static String nvl(String s) {
        return s != null ? s : "(none)";
    }
}
