package com.gitsolve.agent.instructions;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * LangChain4j AiService for generating fix instructions.
 *
 * Given a senior-engineer analysis report, the repository README, CONTRIBUTING.md
 * (if present), and the last 10 commit messages, produces a detailed prompt that
 * tells an AI coding agent exactly how to implement the fix — including how to
 * format commit messages, PR descriptions, and code style per this repo's rules.
 */
public interface FixInstructionsAiService {

    @SystemMessage("""
            You are a senior open-source contributor preparing instructions for an AI coding agent
            that will implement a fix for a GitHub issue.

            Your output must be a single self-contained text block that the AI agent can read and
            immediately act on. It should cover:

            1. WHAT TO FIX — a precise description of the change required, referencing the specific
               files and methods identified in the analysis.
            2. HOW TO IMPLEMENT — step-by-step implementation guidance in plain English.
               Reference existing patterns visible in the commit history where relevant.
            3. REPOSITORY CONVENTIONS — derived from the README and CONTRIBUTING.md:
               - Commit message format (e.g. "fix(module): description" or "[JIRA-123] message")
               - Code style rules (tabs vs spaces, naming conventions, etc.)
               - PR/branch naming conventions if mentioned
               - Test requirements (must tests be added? what test framework?)
               - Any pre-commit hooks, linting, or formatting steps to run
            4. EXAMPLE COMMIT MESSAGE — a ready-to-use commit message following the repo's format.
            5. CHECKLIST — a short bullet list of things the agent must verify before considering
               the fix complete.

            Write in clear, direct English. Use numbered sections. No code snippets unless showing
            a commit message format example. This text will be pasted directly into an AI agent prompt.
            """)
    @UserMessage("""
            Repository: {{repoFullName}}
            Issue #{{issueNumber}}: {{issueTitle}}

            === SENIOR ENGINEER ANALYSIS ===
            Root cause: {{rootCauseAnalysis}}
            Affected files: {{affectedFiles}}
            Suggested approach: {{suggestedApproach}}
            Relevant patterns: {{relevantPatterns}}

            === README (first 3000 chars) ===
            {{readme}}

            === CONTRIBUTING.md (if present) ===
            {{contributing}}

            === LAST 10 COMMITS (main branch) ===
            {{recentCommits}}

            Generate the fix instructions now.
            """)
    String generateInstructions(
            @V("repoFullName")       String repoFullName,
            @V("issueNumber")        int issueNumber,
            @V("issueTitle")         String issueTitle,
            @V("rootCauseAnalysis")  String rootCauseAnalysis,
            @V("affectedFiles")      String affectedFiles,
            @V("suggestedApproach")  String suggestedApproach,
            @V("relevantPatterns")   String relevantPatterns,
            @V("readme")             String readme,
            @V("contributing")       String contributing,
            @V("recentCommits")      String recentCommits
    );
}
