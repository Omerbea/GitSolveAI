package com.gitsolve.agent.execution;

import com.gitsolve.docker.BuildEnvironment;
import com.gitsolve.docker.BuildEnvironmentException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Builds a bounded source context string from a targeted list of file paths.
 *
 * <p>Unlike {@link com.gitsolve.agent.swe.ContextWindowManager}, this builder:
 * <ul>
 *   <li>Only reads files explicitly selected by {@link FileSelectorAiService}.</li>
 *   <li>Caps each file at {@link #MAX_FILE_CHARS} characters (hard truncation with a marker).</li>
 *   <li>Caps total output at {@link #MAX_TOTAL_CHARS} characters.</li>
 * </ul>
 *
 * <p>The per-file cap exists to protect the total context budget. If a file exceeds
 * the cap, a visible truncation marker is appended so the LLM knows more content exists.
 * A visible truncation marker at the end of a file tells the LLM that more content exists.
 */
public class TargetedContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(TargetedContextBuilder.class);

    /** Maximum characters to include from any single file. ~7500 tokens. */
    public static final int MAX_FILE_CHARS  = 30_000;

    /** Hard cap on total context across all files. ~20000 tokens. */
    public static final int MAX_TOTAL_CHARS = 80_000;

    /**
     * Reads the selected files from the build environment and builds a context string.
     *
     * @param selectedPaths paths selected by {@link FileSelectorParser}
     * @param env           the active build environment
     * @return formatted context string, never null
     */
    public String build(List<String> selectedPaths, BuildEnvironment env)
            throws BuildEnvironmentException {

        StringBuilder sb = new StringBuilder();

        for (String path : selectedPaths) {
            if (sb.length() >= MAX_TOTAL_CHARS) break;

            var contentOpt = env.readFile(path);
            if (contentOpt.isEmpty()) {
                log.debug("[TargetedContext] File not found in workspace: {}", path);
                continue;
            }

            String content = contentOpt.get();
            boolean truncated = false;

            if (content.length() > MAX_FILE_CHARS) {
                content = content.substring(0, MAX_FILE_CHARS);
                truncated = true;
                log.debug("[TargetedContext] Truncated {} to {} chars", path, MAX_FILE_CHARS);
            }

            String block = "// FILE: " + path + "\n" + content
                    + (truncated ? "\n// ... [file truncated at " + MAX_FILE_CHARS + " chars]\n" : "")
                    + "\n\n";

            // Don't add if it would blow the total cap
            if (sb.length() + block.length() > MAX_TOTAL_CHARS) {
                log.debug("[TargetedContext] Total cap reached, skipping {}", path);
                break;
            }

            sb.append(block);
        }

        log.info("[TargetedContext] Built context: {} chars across {} file(s)",
                sb.length(), selectedPaths.size());
        return sb.toString();
    }
}
