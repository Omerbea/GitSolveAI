package com.gitsolve.agent.swe;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

/**
 * Stateless parser for the SWE Agent's fix response.
 *
 * Strips JSON code-fences that LLMs frequently add despite instructions not to,
 * then deserialises the payload into a {@link SweFixResponse} record.
 */
@Component
public class SweFixParser {

    private final ObjectMapper objectMapper;

    public SweFixParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parses a raw LLM response string into a {@link SweFixResponse}.
     *
     * @param raw the raw string returned by the LLM (may include code-fence wrappers)
     * @return the deserialised fix response
     * @throws SweParseException if {@code raw} is null/blank or JSON parsing fails
     */
    public SweFixResponse parse(String raw) throws SweParseException {
        if (raw == null || raw.isBlank()) {
            throw new SweParseException("LLM response is null or empty");
        }

        String json = stripCodeFences(raw);

        try {
            return objectMapper.readValue(json, SweFixResponse.class);
        } catch (JsonProcessingException e) {
            throw new SweParseException("Failed to parse SweFixResponse JSON: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ //
    // Private helpers                                                      //
    // ------------------------------------------------------------------ //

    /**
     * Strips JSON code fences that LLMs frequently add despite instructions not to.
     * Handles: ```json\n...\n``` and ```\n...\n```
     *
     * Copied verbatim from ScoutService.stripCodeFences for consistency.
     */
    static String stripCodeFences(String raw) {
        if (raw == null) return "";
        String trimmed = raw.strip();
        // Remove opening fence (```json or ```)
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline != -1) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
        }
        // Remove closing fence
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.lastIndexOf("```")).strip();
        }
        return trimmed;
    }
}
