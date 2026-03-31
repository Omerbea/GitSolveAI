package com.gitsolve.agent.buildclassifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Build Failure Classifier service.
 *
 * <p>Accepts raw Maven or Gradle build output and returns a typed {@link BuildFailure}
 * by calling the LLM via {@link BuildFailureClassifierAiService}.
 *
 * <p>On any failure (LLM call failure, parse error, null type) returns
 * {@link BuildFailure#unknown()} — never throws to the caller.
 *
 * <p>Singleton scope — stateless, thread-safe (no ChatMemory needed).
 */
@Service
public class BuildFailureClassifier {

    private static final Logger log = LoggerFactory.getLogger(BuildFailureClassifier.class);

    private final BuildFailureClassifierAiService aiService;
    private final ObjectMapper objectMapper;

    public BuildFailureClassifier(BuildFailureClassifierAiService aiService,
                                  ObjectMapper objectMapper) {
        this.aiService    = aiService;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ //
    // Public API                                                           //
    // ------------------------------------------------------------------ //

    /**
     * Classifies raw build output into a typed {@link BuildFailure}.
     *
     * @param buildOutput raw stdout/stderr from a Maven or Gradle invocation
     * @return a non-null {@link BuildFailure} — falls back to {@link BuildFailure#unknown()} on any error
     */
    public BuildFailure classify(String buildOutput) {
        try {
            Response<AiMessage> aiResponse = aiService.classify(buildOutput);
            String rawResponse = aiResponse.content().text();

            String stripped = stripCodeFences(rawResponse.trim());
            BuildFailure result = objectMapper.readValue(stripped, BuildFailure.class);

            if (result.type() == null) {
                log.warn("BuildFailureClassifier: LLM returned null type — returning unknown");
                return BuildFailure.unknown();
            }

            log.info("BuildFailureClassifier: type={} location='{}'", result.type(), result.location());
            return result;

        } catch (Exception e) {
            log.warn("BuildFailureClassifier: classification failed, returning unknown. Reason: {}",
                    e.getMessage());
            return BuildFailure.unknown();
        }
    }

    // ------------------------------------------------------------------ //
    // Private helpers                                                      //
    // ------------------------------------------------------------------ //

    /**
     * Strips {@code ```json ... ```} or {@code ``` ... ```} code fences from the LLM response.
     * Project-standard fence-stripping pattern.
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
