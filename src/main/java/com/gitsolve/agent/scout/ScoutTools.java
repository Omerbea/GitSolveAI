package com.gitsolve.agent.scout;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitsolve.github.GitHubClient;
import com.gitsolve.github.dto.GitHubIssueDto;
import com.gitsolve.github.dto.GitHubRepoDto;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Tool methods exposed to the Scout LLM Agent.
 * Each method bridges the LLM's tool invocations to the real GitHub API.
 *
 * Tool descriptions must be unambiguous to prevent the LLM from hallucinating parameters.
 */
@Component
public class ScoutTools {

    private static final Logger log = LoggerFactory.getLogger(ScoutTools.class);

    private final GitHubClient gitHubClient;
    private final ObjectMapper objectMapper;

    public ScoutTools(GitHubClient gitHubClient, ObjectMapper objectMapper) {
        this.gitHubClient = gitHubClient;
        this.objectMapper = objectMapper;
    }

    @Tool("Search GitHub for active Java repositories. " +
          "Returns a JSON array of objects with fields: " +
          "fullName (string, format 'owner/repo'), stars (integer), forks (integer), " +
          "cloneUrl (string), htmlUrl (string). " +
          "Only use language:java queries. Count must be between 1 and 30.")
    public String searchJavaRepositories(
            @P("GitHub search query string, e.g. 'language:java stars:>500'. Must include language:java.")
            String query,
            @P("Number of repositories to return. Integer between 1 and 30.")
            int count) {

        List<GitHubRepoDto> repos = gitHubClient.searchJavaRepos(1, Math.min(count, 30)).block();
        if (repos == null || repos.isEmpty()) return "[]";

        List<Map<String, Object>> result = repos.stream()
                .map(r -> Map.<String, Object>of(
                        "fullName", r.fullName(),
                        "stars", r.stargazersCount(),
                        "forks", r.forksCount(),
                        "cloneUrl", r.cloneUrl(),
                        "htmlUrl", r.htmlUrl()
                ))
                .toList();

        return toJson(result);
    }

    @Tool("Get the number of commits made to a GitHub repository in the last 90 days. " +
          "Input must be the full repository name in 'owner/repo' format. " +
          "Returns an integer. A value of 100 means 100 or more commits.")
    public int getRecentCommitCount(
            @P("Repository full name in 'owner/repo' format, e.g. 'apache/commons-lang'.")
            String fullName) {

        Integer count = gitHubClient.getRecentCommitCount(fullName).block();
        return count != null ? count : 0;
    }

    @Tool("List open GitHub issues labelled 'good first issue' for a repository. " +
          "Returns a JSON array with fields: issueNumber (integer), title (string), " +
          "bodyPreview (string, first 500 characters of the issue body). " +
          "Limit must be between 1 and 10.")
    public String listGoodFirstIssues(
            @P("Repository full name in 'owner/repo' format.")
            String fullName,
            @P("Maximum number of issues to return. Integer between 1 and 10.")
            int limit) {

        List<GitHubIssueDto> issues =
                gitHubClient.getGoodFirstIssues(fullName, Math.min(limit, 10)).block();
        if (issues == null || issues.isEmpty()) return "[]";

        List<Map<String, Object>> result = issues.stream()
                .map(i -> Map.<String, Object>of(
                        "issueNumber", i.number(),
                        "title", i.title(),
                        "bodyPreview", i.body() != null
                                ? i.body().substring(0, Math.min(500, i.body().length()))
                                : ""
                ))
                .toList();

        return toJson(result);
    }

    // ------------------------------------------------------------------ //

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.error("ScoutTools JSON serialization failed: {}", e.getMessage());
            return "[]";
        }
    }
}
