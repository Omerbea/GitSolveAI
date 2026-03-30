package com.gitsolve.agent.buildprofile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitsolve.docker.BuildEnvironment;
import com.gitsolve.docker.BuildOutput;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Build Profile Inspector service.
 *
 * <p>Reads README.md and CONTRIBUTING.md from the container workspace, validates
 * the container environment (JDK version, Maven wrapper presence), then asks the
 * LLM to extract a {@link BuildProfile} from the documentation.
 *
 * <p>On any failure (file read error, LLM call failure, parse error), returns
 * {@link BuildProfile#defaultProfile()} — never throws to the caller.
 *
 * <p>Singleton scope — stateless, thread-safe (no ChatMemory needed).
 */
@Service
public class BuildProfileInspector {

    private static final Logger log = LoggerFactory.getLogger(BuildProfileInspector.class);

    private final BuildProfileInspectorAiService aiService;
    private final ObjectMapper objectMapper;

    public BuildProfileInspector(BuildProfileInspectorAiService aiService,
                                  ObjectMapper objectMapper) {
        this.aiService    = aiService;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ //
    // Public API                                                           //
    // ------------------------------------------------------------------ //

    /**
     * Inspects the build profile for the repository mounted in {@code env}.
     *
     * @param env the container build environment to inspect
     * @return a non-null {@link BuildProfile} — falls back to default on any error
     */
    public BuildProfile inspect(BuildEnvironment env) {
        try {
            // 1. Read documentation files from the container
            String readmeContent      = env.readFile("README.md").orElse("");
            String contributingContent = env.readFile("CONTRIBUTING.md").orElse("");

            // 2. Validate container environment — log results, do not fail on these
            BuildOutput javaVersion = env.runBuild("java -version 2>&1");
            log.info("BuildProfileInspector: JDK check stdout='{}' stderr='{}' exit={}",
                    javaVersion.stdout().trim(), javaVersion.stderr().trim(), javaVersion.exitCode());

            BuildOutput wrapperCheck = env.runBuild("test -f /workspace/mvnw && echo yes || echo no");
            log.info("BuildProfileInspector: mvnw present={}",
                    wrapperCheck.stdout().trim());

            // 3. Call LLM to extract build profile
            Response<AiMessage> aiResponse = aiService.inspect(readmeContent, contributingContent);
            String rawResponse = aiResponse.content().text();

            // 4. Parse response — strip code fences first
            String stripped = stripCodeFences(rawResponse.trim());
            BuildProfile profile = objectMapper.readValue(stripped, BuildProfile.class);

            log.info("BuildProfileInspector: tool={} cmd='{}' jdk={}",
                    profile.buildTool(), profile.buildCommand(), profile.jdkConstraint());

            return profile;

        } catch (Exception e) {
            log.warn("BuildProfileInspector: failed to extract build profile, returning default. Reason: {}",
                    e.getMessage());
            return BuildProfile.defaultProfile();
        }
    }

    // ------------------------------------------------------------------ //
    // Private helpers                                                      //
    // ------------------------------------------------------------------ //

    /**
     * Strips {@code ```json ... ```} or {@code ``` ... ```} code fences from the LLM response.
     * Project-standard fence-stripping pattern (same as ReviewerService).
     */
    private static String stripCodeFences(String raw) {
        if (raw.startsWith("```")) {
            int firstNewline = raw.indexOf('\n');
            int lastFence    = raw.lastIndexOf("```");
            if (firstNewline != -1 && lastFence > firstNewline) {
                return raw.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return raw;
    }
}
