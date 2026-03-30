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
     * Extracts the most relevant portion of build output for LLM consumption.
     * Combines stdout and stderr (Maven compiler errors appear on stdout),
     * then returns the last 150 lines to capture errors near the end of output.
     */
    public String extractStackTrace() {
        StringBuilder combined = new StringBuilder();
        if (stdout != null && !stdout.isBlank()) combined.append(stdout);
        if (stderr != null && !stderr.isBlank()) {
            if (combined.length() > 0) combined.append("\n");
            combined.append(stderr);
        }
        if (combined.isEmpty()) return "";
        String[] lines = combined.toString().split("\n");
        if (lines.length <= 150) return combined.toString();
        return Arrays.stream(lines, lines.length - 150, lines.length)
                .collect(Collectors.joining("\n"));
    }
}
