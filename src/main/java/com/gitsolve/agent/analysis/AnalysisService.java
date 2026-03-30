package com.gitsolve.agent.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitsolve.model.GitIssue;
import com.gitsolve.model.PhaseStats;
import com.gitsolve.model.TriageResult;
import com.gitsolve.repocache.RepoCache;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Senior Engineer Analysis Service.
 *
 * Replaces the SWE Agent + Reviewer + Docker loop with a lightweight LLM call
 * that produces a structured investigation report: root cause, affected files,
 * suggested approach, and complexity estimate.
 *
 * Never throws — returns a safe AnalysisResult.error() on any failure.
 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    private final AnalysisAiService aiService;
    private final ObjectMapper      objectMapper;
    private final RepoCache         repoCache;

    public AnalysisService(AnalysisAiService aiService, ObjectMapper objectMapper, RepoCache repoCache) {
        this.aiService    = aiService;
        this.objectMapper = objectMapper;
        this.repoCache    = repoCache;
    }

    /**
     * Analyse an accepted issue as a senior engineer.
     * Returns a non-null AnalysisResult on any outcome (including LLM failure).
     */
    public AnalysisResult analyse(GitIssue issue, TriageResult triage) {
        String issueId = issue.repoFullName() + "#" + issue.issueNumber();
        log.info("[Analysis] Analysing {} — {}", issueId, issue.title());

        try {
            String bodyExcerpt = issue.body() != null
                    ? issue.body().substring(0, Math.min(2000, issue.body().length()))
                    : "(no description)";

            String sourceContext = "";
            try {
                Path repoPath = repoCache.ensureFork(issue.repoFullName());
                sourceContext = buildSourceContext(issue, repoPath);
                log.info("[Analysis] {} sourceContext built: {} chars", issueId, sourceContext.length());
            } catch (Exception e) {
                log.warn("[Analysis] {} sourceContext=empty (repo cache miss or error): {}", issueId, e.getMessage());
            }

            String raw;
            long start = System.currentTimeMillis();
            Response<AiMessage> response = aiService.analyse(
                    issue.repoFullName(),
                    issue.issueNumber(),
                    issue.title(),
                    bodyExcerpt,
                    triage.reasoning() != null ? triage.reasoning() : "",
                    sourceContext
            );
            long durationMs = System.currentTimeMillis() - start;
            raw = response.content().text();

            String json = extractJson(raw);
            AnalysisResult parsed = objectMapper.readValue(json, AnalysisResult.class);

            // Capture token usage and inject into the result so the orchestrator can propagate it
            TokenUsage tu = response.tokenUsage();
            PhaseStats phaseStats = new PhaseStats(
                    tu != null && tu.inputTokenCount()  != null ? tu.inputTokenCount()  : 0,
                    tu != null && tu.outputTokenCount() != null ? tu.outputTokenCount() : 0,
                    "analysis",
                    durationMs,
                    "complexity=" + (parsed.estimatedComplexity() != null
                            ? parsed.estimatedComplexity() : "UNKNOWN")
            );

            AnalysisResult result = parsed.withPhaseStats(phaseStats);
            log.info("[Analysis] {} — complexity={} affectedFiles={} tokens={}/{}",
                    issueId, result.estimatedComplexity(),
                    result.affectedFiles() != null ? result.affectedFiles().size() : 0,
                    phaseStats.inputTokens(), phaseStats.outputTokens());
            return result;

        } catch (Exception e) {
            log.error("[Analysis] {} — failed: {}", issueId, e.getMessage());
            return AnalysisResult.error(e.getMessage());
        }
    }

    // ------------------------------------------------------------------ //
    // Source context builder                                              //
    // ------------------------------------------------------------------ //

    /**
     * Finds the top-3 most-relevant Java files under {@code repoPath/src/main/java},
     * ranked by how many tokens from the issue title+body appear in the file path.
     * Each file is read up to 5000 chars. Returns the concatenated context string.
     * Never throws — returns empty string on any IO failure.
     */
    private String buildSourceContext(GitIssue issue, Path repoPath) {
        Path sourceRoot = repoPath.resolve("src/main/java");
        if (!Files.isDirectory(sourceRoot)) {
            return "";
        }

        // Token set: split on non-alphanumeric, keep tokens of length >= 3
        String combined = (issue.title() != null ? issue.title() : "") + " "
                        + (issue.body()  != null ? issue.body()  : "");
        Set<String> tokens = Arrays.stream(combined.split("[^a-zA-Z0-9]+"))
                .filter(t -> t.length() >= 3)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        List<Path> top3;
        try (Stream<Path> stream = Files.find(sourceRoot, 20,
                (p, attrs) -> attrs.isRegularFile() && p.toString().endsWith(".java"))) {
            top3 = stream
                    .sorted(Comparator.comparingInt((Path p) -> score(p, tokens)).reversed())
                    .limit(3)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("[Analysis] buildSourceContext walk failed: {}", e.getMessage());
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (Path filePath : top3) {
            try {
                String content = Files.readString(filePath);
                String relativePath = repoPath.relativize(filePath).toString();
                if (content.length() > 5000) {
                    content = content.substring(0, 5000) + "\n// ... [truncated]";
                }
                sb.append("// FILE: ").append(relativePath).append("\n")
                  .append(content).append("\n\n");
            } catch (IOException e) {
                log.warn("[Analysis] buildSourceContext cannot read {}: {}", filePath, e.getMessage());
            }
        }
        return sb.toString();
    }

    /** Score = number of tokens that appear in the lowercased path string. */
    private static int score(Path path, Set<String> tokens) {
        String lower = path.toString().toLowerCase();
        int count = 0;
        for (String token : tokens) {
            if (lower.contains(token)) count++;
        }
        return count;
    }

    // ------------------------------------------------------------------ //
    // JSON extraction (same approach as ScoutService)                     //
    // ------------------------------------------------------------------ //

    static String extractJson(String raw) {
        if (raw == null) return "{}";
        String trimmed = raw.strip();
        if (trimmed.startsWith("```")) {
            int newline = trimmed.indexOf('\n');
            if (newline != -1) trimmed = trimmed.substring(newline + 1);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.lastIndexOf("```")).strip();
        }
        // Find the JSON object boundaries
        int start = trimmed.indexOf('{');
        int end   = trimmed.lastIndexOf('}');
        if (start != -1 && end != -1 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }
}
