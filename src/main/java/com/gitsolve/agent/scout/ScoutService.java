package com.gitsolve.agent.scout;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitsolve.config.GitSolveProperties;
import com.gitsolve.github.GitHubClient;
import com.gitsolve.model.GitIssue;
import com.gitsolve.model.GitRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Orchestrates the Scout Agent's discovery and issue-fetching flow.
 *
 * discoverTargetRepos() calls the ScoutAiService (which uses LLM + GitHub tools)
 * and parses the returned JSON into domain records.
 *
 * fetchGoodFirstIssues() calls the GitHub API directly for each discovered repo.
 */
@Service
public class ScoutService {

    private static final Logger log = LoggerFactory.getLogger(ScoutService.class);

    private final ScoutAiService scoutAiService;
    private final GitHubClient gitHubClient;
    private final ObjectMapper objectMapper;
    private final GitSolveProperties props;

    public ScoutService(
            ScoutAiService scoutAiService,
            GitHubClient gitHubClient,
            ObjectMapper objectMapper,
            GitSolveProperties props) {
        this.scoutAiService = scoutAiService;
        this.gitHubClient   = gitHubClient;
        this.objectMapper   = objectMapper;
        this.props          = props;
    }

    /**
     * Discovers the top N most active Java repositories using the Scout Agent.
     *
     * The LLM calls GitHub tools and returns a JSON array of GitRepository objects.
     * Code-fence wrapping (```json...```) is stripped before deserialization.
     * If the LLM returns prose containing a JSON array, the array is extracted.
     * On any parse failure the method returns an empty list — never throws.
     */
    public List<GitRepository> discoverTargetRepos(int maxRepos) {
        String today = LocalDate.now().toString();
        log.info("Scout: discovering top {} repos (date={})", maxRepos, today);

        String raw = scoutAiService.discoverRepositories(maxRepos, today);
        String json = extractJson(raw);

        try {
            List<GitRepository> repos = objectMapper.readValue(json,
                    new TypeReference<List<GitRepository>>() {});
            log.info("Scout: parsed {} repositories from LLM response", repos.size());
            return repos;
        } catch (Exception e) {
            log.error("Scout: failed to parse LLM response as GitRepository list. raw={}, error={}",
                    raw, e.getMessage());
            return List.of();
        }
    }

    /**
     * Fetches good-first-issue tickets for each discovered repository.
     * Returns a flat list of GitIssue domain records.
     */
    public List<GitIssue> fetchGoodFirstIssues(List<GitRepository> repos, int maxPerRepo) {
        return repos.stream()
                .flatMap(repo -> {
                    List<com.gitsolve.github.dto.GitHubIssueDto> dtos =
                            gitHubClient.getGoodFirstIssues(repo.fullName(), maxPerRepo).block();
                    if (dtos == null) return java.util.stream.Stream.empty();
                    return dtos.stream().map(dto -> new GitIssue(
                            repo.fullName(),
                            dto.number(),
                            dto.title(),
                            dto.body(),
                            dto.htmlUrl(),
                            dto.labels() != null
                                    ? dto.labels().stream()
                                            .map(l -> l.name())
                                            .collect(Collectors.toList())
                                    : List.of()
                    ));
                })
                .collect(Collectors.toList());
    }

    // ------------------------------------------------------------------ //
    // Private helpers                                                      //
    // ------------------------------------------------------------------ //

    /**
     * Extracts a JSON array from the LLM response.
     *
     * Handles three cases:
     * 1. Clean JSON array:                    [{"fullName":...}]
     * 2. Code-fenced:                         ```json\n[...]\n```
     * 3. Prose with embedded array:           "Here are repos: [{"fullName":...}]"
     *
     * Returns the extracted JSON string, or the original if no array is found.
     */
    static String extractJson(String raw) {
        if (raw == null) return "[]";
        String trimmed = raw.strip();

        // Step 1: strip code fences
        trimmed = stripCodeFences(trimmed);

        // Step 2: if it already starts with '[', trust it
        if (trimmed.startsWith("[")) {
            return trimmed;
        }

        // Step 3: find the first '[' and last ']' — extract the embedded array
        int start = trimmed.indexOf('[');
        int end   = trimmed.lastIndexOf(']');
        if (start != -1 && end != -1 && end > start) {
            String extracted = trimmed.substring(start, end + 1);
            log.warn("Scout: LLM returned prose — extracted JSON array from response");
            return extracted;
        }

        // Step 4: no array found — return empty array so parse succeeds with 0 repos
        log.warn("Scout: LLM returned no JSON array in response. raw={}", raw);
        return "[]";
    }

    /**
     * Strips JSON code fences that LLMs frequently add despite instructions not to.
     * Handles: ```json\n...\n``` and ```\n...\n```
     */
    static String stripCodeFences(String raw) {
        if (raw == null) return "";
        String trimmed = raw.strip();
        // Remove opening fence (```json or ```)
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline != -1) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
        }
        // Remove closing fence
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.lastIndexOf("```")).strip();
        }
        return trimmed;
    }
}
