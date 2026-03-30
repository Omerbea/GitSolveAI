package com.gitsolve.agent.reviewer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitsolve.model.ConstraintJson;
import com.gitsolve.model.FixResult;
import com.gitsolve.model.GitIssue;
import com.gitsolve.model.PhaseStats;
import com.gitsolve.model.ReviewResult;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Reviewer Agent orchestrator.
 *
 * <p>For a given {@link FixResult}, this service:
 * <ol>
 *   <li>Fetches governance constraints via {@link RuleExtractorService}.</li>
 *   <li>Serialises the {@link ConstraintJson} to JSON for the prompt.</li>
 *   <li>Calls {@link ReviewerAiService} with the diff and constraints.</li>
 *   <li>Parses the LLM response into a {@link ReviewResult}.</li>
 * </ol>
 *
 * <p>On any failure (serialisation, LLM error, parse error), returns a
 * {@link ReviewResult}{@code (approved=false, violations=["Reviewer error: ..."], summary="Error")}
 * — never throws to the caller.
 *
 * <p>Singleton scope — stateless, thread-safe.
 */
@Service
public class ReviewerService {

    private static final Logger log = LoggerFactory.getLogger(ReviewerService.class);

    private final ReviewerAiService reviewerAiService;
    private final RuleExtractorService ruleExtractorService;
    private final ObjectMapper objectMapper;

    public ReviewerService(ReviewerAiService reviewerAiService,
                           RuleExtractorService ruleExtractorService) {
        this.reviewerAiService    = reviewerAiService;
        this.ruleExtractorService = ruleExtractorService;
        this.objectMapper         = new ObjectMapper();
    }

    // ------------------------------------------------------------------ //
    // Public API                                                           //
    // ------------------------------------------------------------------ //

    /**
     * Reviews the fix described by {@code fixResult} for the given {@code issue}.
     *
     * @param fixResult the completed fix to review (must have a non-null finalDiff)
     * @param issue     the GitHub issue the fix addresses
     * @return a non-null {@link ReviewResult} — never throws
     */
    public ReviewResult review(FixResult fixResult, GitIssue issue) {
        String issueId = issue.repoFullName() + "#" + issue.issueNumber();

        try {
            // 1. Extract governance constraints
            ConstraintJson constraints = ruleExtractorService.extract(issue.repoFullName());

            // 2. Serialise constraints to JSON string for the prompt
            String constraintsJson = objectMapper.writeValueAsString(constraints);

            // 3. Call reviewer LLM
            long start = System.currentTimeMillis();
            Response<String> aiResponse = reviewerAiService.reviewFix(
                    issue.title(),
                    constraintsJson,
                    fixResult.finalDiff() != null ? fixResult.finalDiff() : "");
            long durationMs = System.currentTimeMillis() - start;
            String rawResponse = aiResponse.content();

            // Capture token usage for observability (T02 will thread into domain model)
            TokenUsage tu = aiResponse.tokenUsage();
            @SuppressWarnings("unused")
            PhaseStats phaseStats = new PhaseStats(
                    tu != null && tu.inputTokenCount()  != null ? tu.inputTokenCount()  : 0,
                    tu != null && tu.outputTokenCount() != null ? tu.outputTokenCount() : 0,
                    "reviewer",
                    durationMs,
                    ""
            );

            // 4. Parse the response
            ReviewResponse response = parseResponse(rawResponse);

            // 5. Log outcome
            if (response.approved()) {
                log.info("[{}] Reviewer: approved=true, violations=0", issueId);
            } else {
                log.info("[{}] Reviewer: approved=false, violations={}", issueId,
                        response.violations().size());
                log.warn("[{}] Reviewer violations: {}", issueId, response.violations());
            }

            return new ReviewResult(
                    response.approved(),
                    response.violations() != null ? response.violations() : List.of(),
                    response.summary() != null ? response.summary() : "");

        } catch (Exception e) {
            log.error("[{}] Reviewer error: {}", issueId, e.getMessage(), e);
            return new ReviewResult(
                    false,
                    List.of("Reviewer error: " + e.getMessage()),
                    "Error");
        }
    }

    // ------------------------------------------------------------------ //
    // Private helpers                                                      //
    // ------------------------------------------------------------------ //

    /**
     * Strips optional code fences and deserialises the LLM JSON response.
     */
    private ReviewResponse parseResponse(String raw) throws ReviewerParseException {
        if (raw == null || raw.isBlank()) {
            throw new ReviewerParseException("LLM returned null or blank response");
        }

        String stripped = stripCodeFences(raw.trim());

        try {
            return objectMapper.readValue(stripped, ReviewResponse.class);
        } catch (JsonProcessingException e) {
            throw new ReviewerParseException("Failed to parse LLM review response: " + e.getMessage(), e);
        }
    }

    /**
     * Strips ```json ... ``` or ``` ... ``` code fences from the LLM response.
     * Copied from ScoutService — project-standard fence-stripping pattern.
     */
    private static String stripCodeFences(String raw) {
        if (raw.startsWith("```")) {
            int firstNewline = raw.indexOf('\n');
            int lastFence    = raw.lastIndexOf("```");
            if (firstNewline != -1 && lastFence > firstNewline) {
                return raw.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return raw;
    }
}
