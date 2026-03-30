package com.gitsolve.agent.reviewer;

import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * LangChain4j AiService interface for the Reviewer Agent.
 * Wired by AgentConfig as a singleton bean (stateless — no ChatMemory needed).
 *
 * The LLM receives a git diff, the repository's governance constraints, and the
 * issue title; it returns a strict JSON object describing approval/rejection.
 * Returns a raw String so ReviewerService can parse it with ObjectMapper,
 * matching the same JSON-parse-from-string pattern.
 */
public interface ReviewerAiService {

    @SystemMessage("""
            You are the Reviewer Agent for GitSolve AI, an autonomous system that fixes Java issues.
            Your job is to validate a proposed git diff against a set of repository governance constraints
            and decide whether the fix can be approved.

            Respond ONLY with a JSON object matching this exact schema — no other text:
            {
              "approved":   boolean,
              "violations": ["description of constraint violated", ...],
              "summary":    "one or two sentences summarising the review decision"
            }

            Rules:
            - "approved" is true only if the diff violates NONE of the constraints.
            - "violations" lists each constraint that was violated (by name or short description).
              Use an empty array [] when "approved" is true.
            - "summary" briefly explains the decision (approved or the main reason for rejection).
            - Check the diff for: forbidden code patterns (e.g. System.out.println if listed),
              missing tests (if requiresTests is true), missing Signed-off-by line (if requiresDco is true),
              and any other constraints listed in the provided constraint JSON.
            - If the diff is empty or there is nothing to review, approve with an empty violations list.

            Do not include any text, explanation, or markdown outside the JSON object.
            """)
    @UserMessage("""
            Issue: {{issueTitle}}

            Governance constraints (JSON):
            {{constraintsJson}}

            Git diff:
            {{diff}}

            Return the JSON review object now.
            """)
    Response<String> reviewFix(
            @V("issueTitle")      String issueTitle,
            @V("constraintsJson") String constraintsJson,
            @V("diff")            String diff
    );
}
