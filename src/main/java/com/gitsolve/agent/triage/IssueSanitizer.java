package com.gitsolve.agent.triage;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sanitizes GitHub issue bodies before they are sent to the Triage LLM.
 *
 * Transformations applied in order:
 *   1. Strip HTML tags
 *   2. Collapse code blocks with more than 50 lines to "[code block truncated]"
 *   3. Truncate total length to MAX_BODY_CHARS, appending "[truncated]" marker
 */
@Component
public class IssueSanitizer {

    static final int MAX_BODY_CHARS = 3000;
    static final int MAX_CODE_BLOCK_LINES = 50;
    static final String TRUNCATED_MARKER = "[truncated]";
    static final String CODE_BLOCK_TRUNCATED = "[code block truncated]";

    // Matches fenced code blocks: ``` (optional lang) newline ... ```
    private static final Pattern CODE_FENCE_PATTERN =
            Pattern.compile("```[^\\n]*\\n(.*?)```", Pattern.DOTALL);

    // Matches any HTML tag
    private static final Pattern HTML_TAG_PATTERN =
            Pattern.compile("<[^>]+>");

    /**
     * Sanitizes a raw issue body for safe, focused LLM consumption.
     *
     * @param rawBody the raw issue body (may be null)
     * @return sanitized string, never null
     */
    public String sanitize(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) return "";

        String result = rawBody;

        // Step 1: Strip HTML tags
        result = HTML_TAG_PATTERN.matcher(result).replaceAll("");

        // Step 2: Collapse oversized code blocks
        result = collapseCodeBlocks(result);

        // Step 3: Truncate to MAX_BODY_CHARS
        if (result.length() > MAX_BODY_CHARS) {
            result = result.substring(0, MAX_BODY_CHARS) + TRUNCATED_MARKER;
        }

        return result;
    }

    // ------------------------------------------------------------------ //
    // Private helpers                                                      //
    // ------------------------------------------------------------------ //

    private static String collapseCodeBlocks(String text) {
        Matcher m = CODE_FENCE_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String blockContent = m.group(1);
            long lineCount = blockContent.lines().count();
            if (lineCount > MAX_CODE_BLOCK_LINES) {
                m.appendReplacement(sb, Matcher.quoteReplacement(CODE_BLOCK_TRUNCATED));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
