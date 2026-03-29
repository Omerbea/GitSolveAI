package com.gitsolve.github.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * GitHub issue as returned by the Issues API.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubIssueDto(
        int number,
        String title,
        String body,
        @JsonProperty("html_url") String htmlUrl,
        List<LabelDto> labels
) {
    /**
     * Minimal label representation \u2014 we only need the name.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LabelDto(String name) {}
}
