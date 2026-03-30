package com.gitsolve.agent.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Parses and validates the raw LLM response from {@link ExecutionAiService}.
 *
 * <p>Validation rules (all violations throw {@link ExecutionParseException}):
 * <ol>
 *   <li>JSON must deserialise to {@link ExecutionFixResponse}.</li>
 *   <li>{@code files} must be non-null and non-empty.</li>
 *   <li>Each {@link FileChange#path()} must be non-null and non-blank.</li>
 *   <li>Each {@link FileChange#content()} must be at least 10 characters
 *       (guards against truncated responses).</li>
 *   <li>{@code commitMessage} must be non-null and non-blank.</li>
 * </ol>
 */
@Component
public class ExecutionFixParser {

    private static final int MIN_CONTENT_LENGTH = 10;

    private final ObjectMapper objectMapper;

    public ExecutionFixParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parses a raw LLM response string into a validated {@link ExecutionFixResponse}.
     *
     * @param raw the raw string returned by the LLM (may include markdown fences)
     * @return a validated {@link ExecutionFixResponse}
     * @throws ExecutionParseException if parsing or validation fails
     */
    public ExecutionFixResponse parse(String raw) {
        String json = extractJson(raw);
        ExecutionFixResponse response;
        try {
            response = objectMapper.readValue(json, ExecutionFixResponse.class);
        } catch (Exception e) {
            throw new ExecutionParseException("Failed to deserialise execution response: " + e.getMessage(), e);
        }

        if (response.files() == null || response.files().isEmpty()) {
            throw new ExecutionParseException("LLM returned no files to modify");
        }

        for (int i = 0; i < response.files().size(); i++) {
            FileChange fc = response.files().get(i);
            if (fc.path() == null || fc.path().isBlank()) {
                throw new ExecutionParseException("File at index " + i + " has null or blank path");
            }
            if (fc.content() == null || fc.content().length() < MIN_CONTENT_LENGTH) {
                throw new ExecutionParseException(
                    "File '" + fc.path() + "' content too short (" +
                    (fc.content() == null ? "null" : fc.content().length()) +
                    " chars) — likely truncated by LLM");
            }
        }

        if (response.commitMessage() == null || response.commitMessage().isBlank()) {
            throw new ExecutionParseException("LLM returned null or blank commitMessage");
        }

        return response;
    }

    // ------------------------------------------------------------------ //
    // JSON extraction                                                      //
    // ------------------------------------------------------------------ //

    /**
     * Strips optional markdown code fences and finds the outermost JSON object boundaries.
     * Same approach as {@link com.gitsolve.agent.analysis.AnalysisService#extractJson}.
     */
    static String extractJson(String raw) {
        if (raw == null) return "{}";
        String trimmed = raw.strip();
        // Strip opening fence (```json or ```)
        if (trimmed.startsWith("```")) {
            int newline = trimmed.indexOf('\n');
            if (newline != -1) trimmed = trimmed.substring(newline + 1).strip();
        }
        // Strip closing fence
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.lastIndexOf("```")).strip();
        }
        // Find JSON object boundaries
        int start = trimmed.indexOf('{');
        int end   = trimmed.lastIndexOf('}');
        if (start != -1 && end != -1 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }
}
