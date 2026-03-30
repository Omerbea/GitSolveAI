package com.gitsolve.dashboard;

import com.gitsolve.agent.execution.ExecutionService;
import com.gitsolve.agent.instructions.FixInstructionsService;
import com.gitsolve.config.GitSolveProperties;
import com.gitsolve.model.GitIssue;
import com.gitsolve.model.IssueStatus;
import com.gitsolve.persistence.IssueStore;
import com.gitsolve.persistence.entity.IssueRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Dashboard controller — serves the local HTML fix-history UI.
 *
 * <p>Routes:
 * <ul>
 *   <li>GET /                       — fix history table, run stats, recent runs</li>
 *   <li>GET /issues/{id}/diff       — unified diff view for a single issue</li>
 *   <li>GET /issues/{id}/report     — full investigation report for a single issue</li>
 *   <li>POST /issues/{id}/execute   — trigger execution agent (async, returns 202)</li>
 *   <li>GET /issues/{id}/execution-status — poll execution state + prUrl</li>
 * </ul>
 */
@Controller
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    private final IssueStore             issueStore;
    private final GitSolveProperties     props;
    private final FixInstructionsService fixInstructionsService;
    private final SseEmitterRegistry     sseRegistry;
    private final ApplicationContext     applicationContext;

    public DashboardController(IssueStore issueStore,
                               GitSolveProperties props,
                               FixInstructionsService fixInstructionsService,
                               SseEmitterRegistry sseRegistry,
                               ApplicationContext applicationContext) {
        this.issueStore             = issueStore;
        this.props                  = props;
        this.fixInstructionsService = fixInstructionsService;
        this.sseRegistry            = sseRegistry;
        this.applicationContext     = applicationContext;
    }

    // ------------------------------------------------------------------ //
    // GET /                                                                //
    // ------------------------------------------------------------------ //

    @GetMapping("/")
    public String index(Model model) {
        List<IssueRecord> issues = new ArrayList<>();
        issues.addAll(issueStore.findByStatus(IssueStatus.SUCCESS));
        issues.addAll(issueStore.findByStatus(IssueStatus.FAILED));
        issues.sort(Comparator.comparing(
                IssueRecord::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())));

        // Scout config summary for the dashboard header
        String scoutMode;
        if (props.github().hasTargetRepos()) {
            scoutMode = "Pinned repos: " + String.join(", ", props.github().targetRepos());
        } else if (props.github().starRange() != null && !props.github().starRange().isBlank()) {
            scoutMode = "Star range: " + props.github().starRange();
        } else {
            scoutMode = "LLM Scout (stars > 500)";
        }

        model.addAttribute("issues",      issues);
        model.addAttribute("stats",       issueStore.currentRunStats());
        model.addAttribute("recentRuns",  issueStore.recentRuns(10));
        model.addAttribute("scoutMode",   scoutMode);
        model.addAttribute("issueCount",  issueStore.countAllIssues());
        return "dashboard/index";
    }

    // ------------------------------------------------------------------ //
    // GET /issues/{id}/diff                                                //
    // ------------------------------------------------------------------ //

    @GetMapping("/issues/{id}/diff")
    public String diff(@PathVariable Long id, Model model) {
        IssueRecord record = issueStore.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Issue record not found: " + id));

        String diff = (record.getFixDiff() != null && !record.getFixDiff().isBlank())
                ? record.getFixDiff()
                : "No diff available";

        model.addAttribute("issue", record);
        model.addAttribute("diff",  diff);
        return "dashboard/diff";
    }

    // ------------------------------------------------------------------ //
    // GET /issues/{id}/report                                              //
    // ------------------------------------------------------------------ //

    @GetMapping("/issues/{id}/report")
    public String report(@PathVariable Long id, Model model) {
        IssueRecord record = issueStore.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Issue record not found: " + id));

        issueStore.markReportViewed(id);

        model.addAttribute("issue",           record);
        model.addAttribute("report",          record.getFixReport());
        model.addAttribute("fixInstructions", record.getFixInstructions());
        return "dashboard/report";
    }

    // ------------------------------------------------------------------ //
    // POST /issues/{id}/fix-instructions                                  //
    // ------------------------------------------------------------------ //

    /**
     * Triggers the Fix Instructions Agent for a given issue.
     * Long-running (~10s) — called asynchronously from the UI via fetch().
     * Returns JSON {instructions: "..."} on success, {error: "..."} on failure.
     */
    @PostMapping("/issues/{id}/fix-instructions")
    public ResponseEntity<Map<String, Object>> generateFixInstructions(@PathVariable Long id) {
        IssueRecord record = issueStore.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Issue record not found: " + id));

        if (record.getFixReport() == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "No analysis report found for this issue. Run the analysis first."));
        }

        String instructions = fixInstructionsService.generate(
                record.getRepoFullName(),
                record.getIssueNumber(),
                record.getIssueTitle(),
                record.getFixReport()
        );

        issueStore.saveFixInstructions(id, instructions);
        return ResponseEntity.ok(Map.of("instructions", instructions));
    }

    // ------------------------------------------------------------------ //
    // POST /issues/{id}/execute                                           //
    // ------------------------------------------------------------------ //

    /**
     * Triggers the Execution Agent for a given issue.
     *
     * <p>Returns 202 Accepted immediately. The agent runs on a virtual thread.
     * Progress is delivered via SSE events: {@code execution-started} and
     * {@code execution-complete} / {@code execution-failed}.
     *
     * <p>Returns 400 if no fix instructions have been generated yet.
     * Returns 409 if a PR has already been submitted (idempotency guard).
     */
    @PostMapping("/issues/{id}/execute")
    public ResponseEntity<Map<String, Object>> triggerExecution(@PathVariable Long id) {
        IssueRecord record = issueStore.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Issue record not found: " + id));

        if (record.getFixInstructions() == null || record.getFixInstructions().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "No fix instructions found. Generate them first."));
        }

        if (record.getPrUrl() != null && !record.getPrUrl().isBlank()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "PR already submitted", "prUrl", record.getPrUrl()));
        }

        if ("EXECUTING".equals(record.getExecutionStatus())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Execution already in progress for this issue. Please wait."));
        }

        issueStore.markExecuting(id);
        sseRegistry.broadcast("execution-started",
                "{\"recordId\":" + id + ",\"issueId\":\"" + jsonEscape(record.getIssueId()) + "\"}");
        log.info("[Dashboard] Execution triggered for record id={} issue={}", id, record.getIssueId());

        GitIssue issue = new GitIssue(
                record.getRepoFullName(),
                record.getIssueNumber(),
                record.getIssueTitle(),
                record.getIssueBody(),
                "https://github.com/" + record.getRepoFullName() + "/issues/" + record.getIssueNumber(),
                List.of()
        );
        String fixInstructions = record.getFixInstructions();

        CompletableFuture.runAsync(() -> {
            try {
                ExecutionService executor = applicationContext.getBean(ExecutionService.class);
                executor.setProgressReporter(id, new SseExecutionProgressReporter(sseRegistry));
                var result = executor.execute(issue, fixInstructions, List.of());
                if (result.success()) {
                    issueStore.markPrSubmitted(id, result.prUrl());
                    sseRegistry.broadcast("execution-complete",
                            "{\"recordId\":" + id + ",\"prUrl\":\"" + jsonEscape(result.prUrl()) + "\",\"success\":true}");
                    log.info("[Dashboard] Execution complete for id={} prUrl={}", id, result.prUrl());
                } else {
                    issueStore.markExecutionFailed(id, result.failureReason());
                    sseRegistry.broadcast("execution-failed",
                            "{\"recordId\":" + id + ",\"reason\":\"" + jsonEscape(result.failureReason()) + "\",\"success\":false}");
                    log.warn("[Dashboard] Execution failed for id={}: {}", id, result.failureReason());
                }
            } catch (Exception e) {
                log.error("[Dashboard] Execution error for id={}: {}", id, e.getMessage(), e);
                issueStore.markExecutionFailed(id, "Unexpected error: " + e.getMessage());
                sseRegistry.broadcast("execution-failed",
                        "{\"recordId\":" + id + ",\"reason\":\"" + jsonEscape(e.getMessage()) + "\",\"success\":false}");
            }
        });

        return ResponseEntity.accepted()
                .body(Map.of("status", "started", "recordId", id));
    }

    // ------------------------------------------------------------------ //
    // GET /issues/{id}/execution-status                                   //
    // ------------------------------------------------------------------ //

    /**
     * Returns the current execution state for an issue record.
     * Clients can poll this after receiving {@code execution-complete} to refresh the UI.
     */
    @GetMapping("/issues/{id}/execution-status")
    public ResponseEntity<Map<String, Object>> executionStatus(@PathVariable Long id) {
        IssueRecord record = issueStore.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Issue record not found: " + id));
        return ResponseEntity.ok(Map.of(
                "executionStatus", record.getExecutionStatus() != null ? record.getExecutionStatus() : "NONE",
                "prUrl",           record.getPrUrl() != null ? record.getPrUrl() : ""
        ));
    }

    // ------------------------------------------------------------------ //
    // GET /api/events  (SSE stream)                                       //
    // ------------------------------------------------------------------ //

    /**
     * Server-Sent Events stream. Clients connect once and receive push events
     * without polling. Events: "issue-analysed", "run-complete".
     */
    @GetMapping(value = "/api/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events() {
        return sseRegistry.register();
    }

    /**
     * Returns current run stats as JSON — called by the SSE handler after run-complete.
     */
    @GetMapping("/api/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        var s = issueStore.currentRunStats();
        return ResponseEntity.ok(Map.of(
                "analysed", s.succeeded(),
                "failed",   s.failed(),
                "pending",  s.pending(),
                "skipped",  s.skipped(),
                "total",    s.total()
        ));
    }

    // ------------------------------------------------------------------ //
    // POST /admin/reset                                                    //
    // ------------------------------------------------------------------ //

    /**
     * Deletes all issue records so the next run processes fresh issues.
     * Returns JSON {deleted: N} for the dashboard JS to display.
     */
    @PostMapping("/admin/reset")
    public ResponseEntity<Map<String, Object>> reset() {
        long count = issueStore.countAllIssues();
        issueStore.deleteAllIssues();
        return ResponseEntity.ok(Map.of("deleted", count, "message",
                "Cleared " + count + " issue records — next run will process fresh issues."));
    }

    // ------------------------------------------------------------------ //
    // Private helpers                                                      //
    // ------------------------------------------------------------------ //

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "");
    }
}
