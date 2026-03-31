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

            // 2. Filesystem detection — fast, reliable, does not depend on docs quality
            BuildOutput javaVersion = env.runBuild("java -version 2>&1");
            log.info("BuildProfileInspector: JDK check stdout='{}' stderr='{}' exit={}",
                    javaVersion.stdout().trim(), javaVersion.stderr().trim(), javaVersion.exitCode());

            BuildOutput mvnwCheck   = env.runBuild("test -f /workspace/mvnw   && echo yes || echo no");
            BuildOutput gradlewCheck = env.runBuild("test -f /workspace/gradlew && echo yes || echo no");
            BuildOutput pomCheck    = env.runBuild("test -f /workspace/pom.xml && echo yes || echo no");
            BuildOutput gradleCheck = env.runBuild(
                    "(test -f /workspace/build.gradle || test -f /workspace/build.gradle.kts) && echo yes || echo no");

            boolean hasMvnw   = "yes".equals(mvnwCheck.stdout().trim());
            boolean hasGradlew = "yes".equals(gradlewCheck.stdout().trim());
            boolean hasPom    = "yes".equals(pomCheck.stdout().trim());
            boolean hasGradle = "yes".equals(gradleCheck.stdout().trim());

            log.info("BuildProfileInspector: mvnw={} gradlew={} pom.xml={} build.gradle={}",
                    hasMvnw, hasGradlew, hasPom, hasGradle);

            // 3. Call LLM to extract build profile from docs (best-effort — may return nulls)
            Response<AiMessage> aiResponse = aiService.inspect(readmeContent, contributingContent);
            String rawResponse = aiResponse.content().text();

            // 4. Parse response — strip code fences first
            String stripped = stripCodeFences(rawResponse.trim());
            BuildProfile profile = objectMapper.readValue(stripped, BuildProfile.class);

            // 5. Fill in null fields from filesystem evidence when LLM couldn't infer them
            if (profile.buildTool() == null) {
                profile = fillFromFilesystem(profile, hasMvnw, hasGradlew, hasPom, hasGradle);
            }

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
     * Returns a new profile with {@code buildTool}, {@code wrapperPath}, and {@code buildCommand}
     * derived from filesystem evidence when the LLM couldn't infer them from documentation.
     */
    private static BuildProfile fillFromFilesystem(BuildProfile base,
            boolean hasMvnw, boolean hasGradlew, boolean hasPom, boolean hasGradle) {
        String tool, wrapperPath, command;
        if (hasGradlew) {
            tool = "gradle";
            wrapperPath = "chmod +x /workspace/gradlew && cd /workspace && ./gradlew";
            command = "./gradlew clean build --no-daemon";
        } else if (hasGradle) {
            tool = "gradle";
            wrapperPath = null;
            command = "gradle clean build --no-daemon";
        } else if (hasMvnw) {
            tool = "maven";
            wrapperPath = "chmod +x /workspace/mvnw && /workspace/mvnw";
            command = "./mvnw clean package --no-transfer-progress";
        } else if (hasPom) {
            tool = "maven";
            wrapperPath = null;
            command = "mvn clean package --no-transfer-progress";
        } else {
            // Nothing recognisable — keep base (will fall back to Maven default in ExecutionService)
            return base;
        }
        return new BuildProfile(
                tool,
                wrapperPath,
                command,
                base.jdkConstraint(),
                base.customPrereqs(),
                base.notes()
        );
    }

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
