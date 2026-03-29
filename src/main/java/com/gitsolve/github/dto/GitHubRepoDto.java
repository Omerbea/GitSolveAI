package com.gitsolve.github.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * GitHub repository as returned by the Search API and Repos API.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubRepoDto(
        long id,
        @JsonProperty("full_name")    String fullName,
        @JsonProperty("clone_url")    String cloneUrl,
        @JsonProperty("html_url")     String htmlUrl,
        @JsonProperty("stargazers_count") int stargazersCount,
        @JsonProperty("forks_count")  int forksCount,
        @JsonProperty("open_issues_count") int openIssuesCount,
        String language
) {}
