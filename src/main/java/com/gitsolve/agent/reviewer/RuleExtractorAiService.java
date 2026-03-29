package com.gitsolve.agent.reviewer;

import com.gitsolve.model.ConstraintJson;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * LangChain4j AiService interface for extracting governance constraints
 * from a repository's CONTRIBUTING.md file.
 *
 * Returns a {@link ConstraintJson} record populated from the markdown content.
 * The LLM is instructed to return a strict JSON object — no prose outside the JSON.
 *
 * Wired by AgentConfig as a singleton bean (stateless — no ChatMemory needed).
 */
public interface RuleExtractorAiService {

    @SystemMessage("""
            You are the Rule Extractor for GitSolve AI, an autonomous system that fixes Java issues.
            Your job is to read a repository's CONTRIBUTING.md and extract governance constraints
            into a structured JSON object.

            Respond ONLY with a JSON object matching this exact schema — no other text:
            {
              "checkstyleConfig":  "path/to/checkstyle.xml" | null,
              "requiresDco":       boolean,
              "requiresTests":     boolean,
              "jdkVersion":        "21" | "17" | "11" | "unknown",
              "buildCommand":      "mvn test" | "gradle test" | "mvn verify" | ...,
              "forbiddenPatterns": ["pattern1", "pattern2"]
            }

            Rules:
            - "checkstyleConfig": path to the checkstyle XML if the file mentions one; null otherwise.
            - "requiresDco": true if the file mentions "Signed-off-by", DCO, or Developer Certificate of Origin.
            - "requiresTests": true if the file requires tests to accompany every fix or change.
            - "jdkVersion": the Java version required, as a string ("21", "17", "11"). Use "unknown" if not stated.
            - "buildCommand": the primary command used to run tests (e.g. "mvn test", "gradle test").
              Default to "mvn test" if not stated.
            - "forbiddenPatterns": a list of code patterns explicitly disallowed (e.g. "System.out.println",
              "printStackTrace"). Empty array if none mentioned.

            If a field cannot be determined from the content, use the safe default:
            null for checkstyleConfig, false for requiresDco, true for requiresTests,
            "unknown" for jdkVersion, "mvn test" for buildCommand, [] for forbiddenPatterns.

            Do not include any text, explanation, or markdown outside the JSON object.
            """)
    @UserMessage("""
            CONTRIBUTING.md content:
            {{contributingMd}}
            """)
    ConstraintJson extractRules(@V("contributingMd") String contributingMd);
}
