package com.gitsolve.agent.depcheck;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitsolve.agent.buildprofile.BuildProfile;
import com.gitsolve.docker.BuildEnvironment;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Dependency Pre-Check agent service.
 *
 * <p>Reads the build file (pom.xml or build.gradle/build.gradle.kts) from the
 * container workspace using the build tool indicated by the {@link BuildProfile},
 * then asks the LLM to identify suspicious or problematic dependencies.
 *
 * <p>On any failure (file read error, LLM call failure, parse error) returns
 * {@link DependencyCheckResult#noIssues()} — never throws to the caller.
 *
 * <p>Singleton scope — stateless, thread-safe (no ChatMemory needed).
 */
@Service
public class DependencyPreCheckService {

    private static final Logger log = LoggerFactory.getLogger(DependencyPreCheckService.class);

    private final DependencyPreCheckAiService aiService;
    private final ObjectMapper objectMapper;

    public DependencyPreCheckService(DependencyPreCheckAiService aiService,
                                     ObjectMapper objectMapper) {
        this.aiService    = aiService;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ //
    // Public API                                                           //
    // ------------------------------------------------------------------ //

    /**
     * Inspects the build file for suspicious dependencies.
     *
     * @param env     the container build environment to read the build file from
     * @param profile the build profile identifying which build tool is in use
     * @return a non-null {@link DependencyCheckResult} — falls back to noIssues() on any error
     */
    public DependencyCheckResult check(BuildEnvironment env, BuildProfile profile) {
        try {
            // Resolve build file content based on build tool
            String buildFileContent = readBuildFile(env, profile);

            if (buildFileContent.isBlank()) {
                log.warn("DependencyPreCheckService: no build file content found — skipping check");
                return DependencyCheckResult.noIssues();
            }

            // Call LLM to inspect the build file
            Response<AiMessage> aiResponse = aiService.check(buildFileContent);
            String rawResponse = aiResponse.content().text();

            // Parse response — strip code fences first
            String stripped = stripCodeFences(rawResponse.trim());
            DependencyCheckResult result = objectMapper.readValue(stripped, DependencyCheckResult.class);

            log.info("DependencyPreCheckService: hasSuspiciousDeps={} findings={}",
                    result.hasSuspiciousDeps(), result.findings().size());
            return result;

        } catch (Exception e) {
            log.warn("DependencyPreCheckService: check failed, returning noIssues(). Reason: {}",
                    e.getMessage());
            return DependencyCheckResult.noIssues();
        }
    }

    // ------------------------------------------------------------------ //
    // Private helpers                                                      //
    // ------------------------------------------------------------------ //

    /**
     * Reads the appropriate build file from the container workspace based on the build tool.
     *
     * <ul>
     *   <li>"maven" (or null/unknown) → reads {@code pom.xml}</li>
     *   <li>"gradle" → tries {@code build.gradle} first; falls back to {@code build.gradle.kts};
     *       returns empty string if neither has content</li>
     * </ul>
     */
    private static String readBuildFile(BuildEnvironment env, BuildProfile profile)
            throws com.gitsolve.docker.BuildEnvironmentException {
        String tool = profile != null ? profile.buildTool() : null;

        if ("gradle".equals(tool)) {
            // Try build.gradle first, then build.gradle.kts
            java.util.Optional<String> groovy = env.readFile("build.gradle");
            if (groovy.isPresent() && !groovy.get().isBlank()) {
                return groovy.get();
            }
            java.util.Optional<String> kotlin = env.readFile("build.gradle.kts");
            if (kotlin.isPresent() && !kotlin.get().isBlank()) {
                return kotlin.get();
            }
            return "";
        }

        // Default: maven (covers null, "maven", and any unknown tool)
        return env.readFile("pom.xml").orElse("");
    }

    /**
     * Strips {@code ```json ... ```} or {@code ``` ... ```} code fences from the LLM response.
     * Project-standard fence-stripping pattern (same as BuildProfileInspector).
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
