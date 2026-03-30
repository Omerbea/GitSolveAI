package com.gitsolve.agent.buildprofile;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * LangChain4j AiService interface for the Build Profile Inspector.
 * Wired by AgentConfig as a singleton bean (stateless — no ChatMemory needed).
 *
 * <p>Returns {@code Response<AiMessage>} (raw string) rather than relying on
 * LangChain4j's direct deserialization because {@link BuildProfile} contains a
 * {@code List<String>} field — direct deserialization is unreliable for those.
 * {@link BuildProfileInspector} parses the response with a plain ObjectMapper.
 */
@SystemMessage("""
        You are the Build Profile Inspector for GitSolve AI, an autonomous system that fixes Java issues.
        Your job is to examine README and CONTRIBUTING documentation and extract the build configuration
        for the repository.

        Respond ONLY with a JSON object matching this exact schema — no other text:
        {
          "buildTool":     "maven" | "gradle" | "unknown",
          "wrapperPath":   "./mvnw" | "./gradlew" | null,
          "buildCommand":  "the exact command to compile the project",
          "jdkConstraint": "17" | "21" | null,
          "customPrereqs": ["list of any special prerequisites"],
          "notes":         "brief note about the build setup, or empty string"
        }

        Rules:
        - "buildTool" must be exactly one of: "maven", "gradle", "unknown".
        - "buildCommand" must be an executable shell command (e.g. "./mvnw clean package -DskipTests").
          Prefer the wrapper script (./mvnw or ./gradlew) when wrapperPath is non-null.
        - "jdkConstraint" should be the JDK major version as a string (e.g. "17", "21"), or null if not specified.
        - "customPrereqs" lists any special environment requirements beyond a standard JDK install.
        - If documentation is empty or absent, set all fields to their defaults:
          buildTool="maven", wrapperPath=null, buildCommand="mvn clean package",
          jdkConstraint=null, customPrereqs=[], notes="No docs found".

        Do not include any text, explanation, or markdown outside the JSON object.
        """)
public interface BuildProfileInspectorAiService {

    @UserMessage("""
            README.md content:
            {{readmeContent}}

            CONTRIBUTING.md content:
            {{contributingContent}}

            Return the JSON build profile object now.
            """)
    Response<AiMessage> inspect(
            @V("readmeContent")      String readmeContent,
            @V("contributingContent") String contributingContent
    );
}
