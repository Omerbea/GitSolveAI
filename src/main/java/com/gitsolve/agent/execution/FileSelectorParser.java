package com.gitsolve.agent.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Parses the raw JSON response from {@link FileSelectorAiService} and validates
 * each returned path against the set of available paths.
 *
 * <p>Anti-hallucination rule: any path not present in {@code availablePaths} is silently
 * dropped. This prevents the execution agent from trying to read non-existent files.
 *
 * <p>Returns at most {@link #MAX_PATHS} paths.
 */
@Component
public class FileSelectorParser {

    private static final Logger log    = LoggerFactory.getLogger(FileSelectorParser.class);
    public  static final int    MAX_PATHS = 5;

    private final ObjectMapper objectMapper;

    public FileSelectorParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parses and validates the LLM response.
     *
     * @param raw            raw string from {@link FileSelectorAiService#selectFiles}
     * @param availablePaths the complete set of paths the LLM was shown
     * @return validated list of up to {@link #MAX_PATHS} real paths; empty if parsing fails
     */
    public List<String> parse(String raw, List<String> availablePaths) {
        if (raw == null || raw.isBlank()) return List.of();

        Set<String> available = Set.copyOf(availablePaths);
        String json = extractJson(raw);

        FileSelectorResponse response;
        try {
            response = objectMapper.readValue(json, FileSelectorResponse.class);
        } catch (Exception e) {
            log.warn("[FileSelector] Failed to parse response: {} — raw: {}", e.getMessage(),
                    raw.length() > 200 ? raw.substring(0, 200) + "..." : raw);
            return List.of();
        }

        if (response.paths() == null || response.paths().isEmpty()) {
            log.warn("[FileSelector] LLM returned empty paths list");
            return List.of();
        }

        List<String> valid = new ArrayList<>();
        for (String path : response.paths()) {
            if (path == null || path.isBlank()) continue;
            if (!available.contains(path)) {
                log.debug("[FileSelector] Dropping hallucinated path: {}", path);
                continue;
            }
            valid.add(path);
            if (valid.size() >= MAX_PATHS) break;
        }

        log.info("[FileSelector] Selected {} file(s): {}", valid.size(), valid);
        return valid;
    }

    /** Strips markdown fences and finds the outermost JSON object. */
    private static String extractJson(String raw) {
        String s = raw.strip();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl != -1) s = s.substring(nl + 1).strip();
        }
        if (s.endsWith("```")) s = s.substring(0, s.lastIndexOf("```")).strip();
        int start = s.indexOf('{');
        int end   = s.lastIndexOf('}');
        if (start != -1 && end != -1 && end > start) return s.substring(start, end + 1);
        return s;
    }
}
