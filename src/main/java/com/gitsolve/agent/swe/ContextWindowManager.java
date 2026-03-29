package com.gitsolve.agent.swe;

import com.gitsolve.docker.BuildEnvironment;
import com.gitsolve.docker.BuildEnvironmentException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Packs source files into a single context string for the SWE Agent's prompt,
 * respecting a hard character budget.
 *
 * <p>Policy: greedy first-fit.
 * Files are processed in alphabetical order. A file whose formatted block alone
 * exceeds {@code maxChars} is <em>skipped entirely</em> — it is never truncated.
 * Truncation would produce syntactically broken Java that could mislead the LLM.
 *
 * <p>This is a plain class — no Spring annotations — so it can be instantiated
 * and tested without a Spring context.
 */
public class ContextWindowManager {

    /** Default hard limit used by production callers. */
    public static final int MAX_CONTEXT_CHARS = 60_000;

    private final int maxChars;

    public ContextWindowManager(int maxChars) {
        this.maxChars = maxChars;
    }

    /**
     * Builds a consolidated context string from the supplied file paths.
     *
     * <p>Each file is formatted as:
     * <pre>
     * // FILE: relative/path/to/File.java
     * &lt;file content&gt;
     *
     * </pre>
     *
     * @param filePaths relative paths to include (order is normalised to alphabetical)
     * @param env       the build environment used to read file contents
     * @return the accumulated context string (may be empty if no files fit)
     * @throws BuildEnvironmentException if {@code env.readFile} signals a hard I/O failure
     */
    public String buildContext(List<String> filePaths, BuildEnvironment env)
            throws BuildEnvironmentException {

        // Defensive copy + deterministic ordering.
        List<String> sorted = new ArrayList<>(filePaths);
        Collections.sort(sorted);

        StringBuilder accumulated = new StringBuilder();

        for (String path : sorted) {
            Optional<String> contentOpt = env.readFile(path);
            if (contentOpt.isEmpty()) {
                // File does not exist in the workspace — skip silently.
                continue;
            }

            String content = contentOpt.get();
            String block = "// FILE: " + path + "\n" + content + "\n\n";

            // A single oversized file is SKIPPED (not truncated) — truncation would
            // produce syntactically broken Java that could mislead the LLM.
            if (block.length() > maxChars) {
                continue;
            }

            if (accumulated.length() + block.length() > maxChars) {
                // Budget exhausted — stop adding more files.
                break;
            }

            accumulated.append(block);
        }

        return accumulated.toString();
    }
}
