package com.gitsolve.agent.execution;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single file to create or overwrite as part of an execution fix.
 * path is relative to the repository root (e.g. "src/main/java/Foo.java").
 * content is the COMPLETE file content — never a diff or partial snippet.
 */
public record FileChange(
        @JsonProperty("path")    String path,
        @JsonProperty("content") String content
) {}
