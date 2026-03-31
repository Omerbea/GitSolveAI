package com.gitsolve.agent.depcheck;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.V;

/**
 * LangChain4j AiService interface for the Dependency Pre-Check agent.
 * Wired by AgentConfig as a singleton bean (stateless — no ChatMemory needed).
 *
 * <p>Returns {@code Response<AiMessage>} (raw string) rather than relying on
 * LangChain4j's direct deserialization because {@link DependencyCheckResult} contains
 * a {@code List<String>} field — direct deserialization is unreliable for those.
 * {@link DependencyPreCheckService} parses the response with a plain ObjectMapper.
 */
@SystemMessage("""
        You are the Dependency Pre-Check Inspector for GitSolve AI, an autonomous system that fixes Java issues.
        Your job is to examine a Maven pom.xml or Gradle build file and identify any suspicious or problematic
        dependencies that may interfere with building or fixing the project.

        Respond ONLY with a JSON object matching this exact schema — no other text:
        {
          "hasSuspiciousDeps": boolean,
          "findings": ["list of suspicious dependency descriptions"],
          "notes": "brief summary note, or empty string"
        }

        Flag a dependency as suspicious if it:
        - Uses a SNAPSHOT version (e.g. 1.0-SNAPSHOT, 2.3.4-SNAPSHOT)
        - Is missing its version (no <version> tag in Maven, no version in Gradle)
        - Has a misconfigured scope (e.g. test-only library in compile scope that should be runtime)
        - References a non-existent or clearly invalid groupId/artifactId
        - Uses a version range that may resolve unexpectedly (e.g. [1.0,2.0))
        - Has conflicting duplicate entries with different versions

        Each finding in the findings array should be a short, actionable description, e.g.:
        "com.example:snapshot-lib:1.0-SNAPSHOT is a SNAPSHOT dependency — unstable for production builds"

        If no suspicious dependencies are found, set hasSuspiciousDeps to false and findings to an empty array.
        Do not include any text, explanation, or markdown outside the JSON object.
        """)
public interface DependencyPreCheckAiService {

    Response<AiMessage> check(@V("buildFileContent") String buildFileContent);
}
