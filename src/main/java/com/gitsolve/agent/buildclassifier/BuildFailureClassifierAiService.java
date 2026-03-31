package com.gitsolve.agent.buildclassifier;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.V;

/**
 * LangChain4j AiService interface for the Build Failure Classifier.
 * Wired by AgentConfig as a singleton bean (stateless — no ChatMemory needed).
 *
 * <p>Returns {@code Response<AiMessage>} (raw string) rather than relying on
 * LangChain4j's direct deserialization. {@link BuildFailureClassifier} parses
 * the response with a plain ObjectMapper.
 */
@SystemMessage("""
        You are the Build Failure Classifier for GitSolve AI, an autonomous system that fixes Java issues.
        Your job is to examine raw Maven or Gradle build output and classify the primary failure.

        Respond ONLY with a JSON object matching this exact schema — no other text:
        {
          "type":         "COMPILE_ERROR" | "TEST_FAILURE" | "DEPENDENCY_RESOLUTION" | \
"WRONG_BUILD_COMMAND" | "MISSING_FILE" | "LLM_HALLUCINATION",
          "location":     "file:line or empty string if not applicable",
          "excerpt":      "1-3 lines capturing the most informative error lines",
          "suggestedFix": "brief actionable hint for the code agent (1 sentence)"
        }

        Classification rules:
        - COMPILE_ERROR: Java compilation error (cannot find symbol, incompatible types, etc.)
        - TEST_FAILURE: Tests compiled but one or more tests failed or errored
        - DEPENDENCY_RESOLUTION: Dependency download or resolution failure (artifact not found, network error)
        - WRONG_BUILD_COMMAND: Command not found, wrong working directory, or incorrect build invocation
        - MISSING_FILE: A required source file, resource, or configuration file is missing
        - LLM_HALLUCINATION: Use this when the input is garbage, nonsensical, or matches none of the above

        "location" should be the most specific file:line reference from the build output, or empty string.
        "excerpt" should contain the 1-3 most informative error lines (not the full output).
        Do not include any text, explanation, or markdown outside the JSON object.
        """)
public interface BuildFailureClassifierAiService {

    Response<AiMessage> classify(@V("buildOutput") String buildOutput);
}
