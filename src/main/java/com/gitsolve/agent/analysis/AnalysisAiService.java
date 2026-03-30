package com.gitsolve.agent.analysis;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * LangChain4j AiService for the Senior Engineer Analysis Agent.
 *
 * Given a GitHub issue, produces a structured JSON analysis as a senior engineer would:
 * root cause, affected files, suggested approach, and complexity estimate.
 * Does NOT write code — analysis only.
 */
public interface AnalysisAiService {

    @SystemMessage("""
            You are a senior software engineer reviewing a GitHub issue from an open-source Java project.
            Your job is to analyse the issue and produce a structured investigation report.

            You do NOT write code. You reason about the problem, identify the likely root cause,
            name the files or classes most likely affected, and suggest a clear fix approach in plain English.

            CRITICAL — OUTPUT RULES:
            - Your ENTIRE response must be a single JSON object. Nothing else.
            - Do NOT write any English text before or after the JSON.
            - Do NOT use markdown code fences.
            - The object must match this schema exactly:
            {
              "rootCauseAnalysis": "A clear explanation of what is causing the bug or what the feature requires. 2-4 sentences.",
              "affectedFiles": ["path/to/Foo.java", "path/to/Bar.java"],
              "suggestedApproach": "Step-by-step explanation of how to fix this in plain English. No code snippets.",
              "estimatedComplexity": "EASY | MEDIUM | HARD",
              "relevantPatterns": "Any relevant design patterns, existing code patterns in the repo, or Java idioms that apply."
            }
            """)
    @UserMessage("""
            Repository: {{repoFullName}}
            Issue #{{issueNumber}}: {{issueTitle}}

            Issue description:
            {{issueBody}}

            Triage assessment: {{triageReasoning}}

            Analyse this issue as a senior engineer. Reply with the JSON object only.
            """)
    String analyse(
            @V("repoFullName")     String repoFullName,
            @V("issueNumber")      int issueNumber,
            @V("issueTitle")       String issueTitle,
            @V("issueBody")        String issueBody,
            @V("triageReasoning")  String triageReasoning
    );
}
