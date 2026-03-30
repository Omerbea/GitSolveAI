package com.gitsolve.agent.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitsolve.model.GitIssue;
import com.gitsolve.model.TriageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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

    public AnalysisService(AnalysisAiService aiService, ObjectMapper objectMapper) {
        this.aiService    = aiService;
        this.objectMapper = objectMapper;
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

            String raw = aiService.analyse(
                    issue.repoFullName(),
                    issue.issueNumber(),
                    issue.title(),
                    bodyExcerpt,
                    triage.reasoning() != null ? triage.reasoning() : ""
            );

            String json = extractJson(raw);
            AnalysisResult result = objectMapper.readValue(json, AnalysisResult.class);
            log.info("[Analysis] {} — complexity={} affectedFiles={}",
                    issueId, result.estimatedComplexity(),
                    result.affectedFiles() != null ? result.affectedFiles().size() : 0);
            return result;

        } catch (Exception e) {
            log.error("[Analysis] {} — failed: {}", issueId, e.getMessage());
            return AnalysisResult.error(e.getMessage());
        }
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
