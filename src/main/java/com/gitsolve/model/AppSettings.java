package com.gitsolve.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Runtime-configurable settings stored in the app_settings table.
 * Loaded at startup and on every request to the settings page.
 * Changes take effect on the next run — no restart required.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AppSettings(
        @JsonProperty("scoutMode")        ScoutMode scoutMode,
        @JsonProperty("targetRepos")      List<String> targetRepos,
        @JsonProperty("starMin")          int starMin,
        @JsonProperty("starMax")          int starMax,
        @JsonProperty("maxReposPerRun")   int maxReposPerRun,
        @JsonProperty("maxIssuesPerRepo") int maxIssuesPerRepo
) {
    public enum ScoutMode { PINNED, STAR_RANGE, LLM }

    /** Returns the star range in GitHub search syntax, e.g. "100..3000". */
    public String starRange() {
        return starMin + ".." + starMax;
    }

    public boolean hasTargetRepos() {
        return targetRepos != null && !targetRepos.isEmpty();
    }

    /** Returns a safe default when the DB row is missing or corrupt. */
    public static AppSettings defaults() {
        return new AppSettings(
                ScoutMode.PINNED,
                List.of("apache/commons-lang", "javaparser/javaparser", "diffplug/spotless"),
                100, 3000, 2, 5
        );
    }
}
