package com.gitsolve.docker;

import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Result of a build command execution inside a Docker container.
 */
public record BuildOutput(
        String stdout,
        String stderr,
        int exitCode,
        boolean timedOut,
        Duration duration
) {

    /** True if the build exited with code 0 and did not time out. */
    public boolean buildPassed() {
        return exitCode == 0 && !timedOut;
    }

    /** True if the build failed or timed out. */
    public boolean buildFailed() {
        return !buildPassed();
    }

    /**
     * Extracts the most relevant portion of stderr for LLM consumption.
     * Returns the last 100 lines of stderr, or all of stderr if fewer than 100 lines.
     */
    public String extractStackTrace() {
        if (stderr == null || stderr.isBlank()) return "";
        String[] lines = stderr.split("\n");
        if (lines.length <= 100) return stderr;
        return Arrays.stream(lines, lines.length - 100, lines.length)
                .collect(Collectors.joining("\n"));
    }
}
