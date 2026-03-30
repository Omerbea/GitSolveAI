package com.gitsolve.agent.buildprofile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Structured description of the build profile extracted from repository documentation.
 *
 * <p>Produced by {@link BuildProfileInspector} after reading README.md and CONTRIBUTING.md
 * from the container workspace and calling the LLM to extract build metadata.
 *
 * <p>Uses {@code @JsonProperty} on every component so a plain {@code new ObjectMapper()}
 * (without Spring's parameter-names module) can deserialise it correctly.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BuildProfile(
        @JsonProperty("buildTool")      String buildTool,
        @JsonProperty("wrapperPath")    String wrapperPath,
        @JsonProperty("buildCommand")   String buildCommand,
        @JsonProperty("jdkConstraint")  String jdkConstraint,
        @JsonProperty("customPrereqs")  List<String> customPrereqs,
        @JsonProperty("notes")          String notes
) {

    /**
     * Safe fallback returned when documentation is absent or the LLM call fails.
     * Defaults to a plain Maven invocation with no special requirements.
     */
    public static BuildProfile defaultProfile() {
        return new BuildProfile(
                "maven",
                null,
                "mvn clean package",
                null,
                List.of(),
                "No docs found"
        );
    }
}
