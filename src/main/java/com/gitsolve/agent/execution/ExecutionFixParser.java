package com.gitsolve.agent.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

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

    private static final Logger log = LoggerFactory.getLogger(ExecutionFixParser.class);
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
            // Fallback: LLM returned a GitHub PR-style schema instead of the required one.
            // Try remapping pull_request.commits[].changes[] → files[]
            response = tryRemapPrSchema(json, response);
            if (response.files() == null || response.files().isEmpty()) {
                throw new ExecutionParseException("LLM returned no files to modify");
            }
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
    // Alternative schema remapper                                          //
    // ------------------------------------------------------------------ //

    /**
     * Handles the case where the LLM returns a GitHub PR-style schema:
     * {@code pull_request.commits[].changes[]{path, content}} instead of {@code files[]}.
     * Extracts all changes across all commits and maps them to {@link FileChange} entries.
     */
    private ExecutionFixResponse tryRemapPrSchema(String json, ExecutionFixResponse original) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode pr = root.path("pull_request");
            if (pr.isMissingNode()) return original;

            List<FileChange> files = new ArrayList<>();
            for (JsonNode commit : pr.path("commits")) {
                for (JsonNode change : commit.path("changes")) {
                    String path    = change.path("path").asText(null);
                    String content = change.path("content").asText(null);
                    if (path != null && content != null) {
                        files.add(new FileChange(path, content));
                    }
                }
            }
            // Also try top-level "output" array: {"output": [{"type": "modified", "path": "...", "content": "..."}]}
            if (files.isEmpty()) {
                for (JsonNode change : root.path("output")) {
                    String path    = change.path("path").asText(null);
                    String content = change.path("content").asText(null);
                    if (path != null && content != null) {
                        files.add(new FileChange(path, content));
                    }
                }
            }

            if (files.isEmpty()) return original;

            String commitMessage = original.commitMessage();
            if (commitMessage == null || commitMessage.isBlank()) {
                JsonNode firstCommit = pr.path("commits").path(0);
                commitMessage = firstCommit.path("message").asText("fix: apply changes");
            }
            String prTitle = original.prTitle();
            if (prTitle == null || prTitle.isBlank()) {
                prTitle = pr.path("title").asText("Fix");
            }
            String prBody = original.prBody();
            if (prBody == null || prBody.isBlank()) {
                prBody = pr.path("body").asText("");
            }

            log.debug("Remapped PR-schema response: {} file(s) extracted", files.size());
            return new ExecutionFixResponse(files, commitMessage, prTitle, prBody);
        } catch (Exception e) {
            log.debug("PR-schema remap failed: {}", e.getMessage());
            return original;
        }
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
