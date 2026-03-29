package com.gitsolve.agent.triage;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * LangChain4j AiService interface for the Triage Agent.
 * Wired by AgentConfig using AiServices.builder.
 *
 * The LLM receives an issue and returns a structured TriageResponse JSON object.
 * No tools — analysis is purely based on the issue text.
 */
public interface TriageAiService {

    @SystemMessage("""
            You are the Triage Agent for GitSolve AI, an autonomous system that fixes Java issues.
            Your job is to classify a GitHub issue and decide if it is suitable for automated fixing.

            Classification rules:
            EASY: Bug fix or test addition with a clear root cause described in the issue.
                  The fix likely involves changing fewer than 5 files. No UI work. No schema changes.
            MEDIUM: Logic improvements, refactoring, or feature additions with moderate scope.
                    Still within "good-first-issue" territory but more involved.

            Respond ONLY with a JSON object matching this schema — no other text:
            {
              "complexity": "EASY" | "MEDIUM",
              "reasoning": "string (max 2 sentences)",
              "hasClearSteps": boolean,
              "requiresUiWork": boolean,
              "requiresDbMigration": boolean
            }

            "requiresUiWork" is true if the issue mentions UI, frontend, CSS, HTML templates, or visual changes.
            "requiresDbMigration" is true if the issue requires adding/changing database tables, columns, or migrations.
            "hasClearSteps" is true if the issue body describes the expected behavior and the reproduction steps.

            Do not include any text, explanation, or markdown outside the JSON object.
            """)
    @UserMessage("""
            Repository: {{repoFullName}}
            Issue #{{issueNumber}}: {{issueTitle}}

            Issue body:
            {{issueBody}}

            Labels: {{labels}}
            """)
    TriageResponse analyzeIssue(
            @V("repoFullName")  String repoFullName,
            @V("issueNumber")   int issueNumber,
            @V("issueTitle")    String issueTitle,
            @V("issueBody")     String issueBody,
            @V("labels")        String labels
    );
}
