package com.gitsolve.dashboard;

import com.gitsolve.orchestration.FixLoopOrchestrator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Minimal run trigger for local development.
 * POST /api/runs/trigger fires the fix loop asynchronously and returns immediately.
 * The run executes in a virtual thread and results appear in the dashboard.
 */
@RestController
@RequestMapping("/api/runs")
public class RunTriggerController {

    private final FixLoopOrchestrator orchestrator;

    public RunTriggerController(FixLoopOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/trigger")
    public ResponseEntity<Map<String, String>> trigger() {
        CompletableFuture.runAsync(orchestrator::runFixLoop);
        return ResponseEntity.accepted()
                .body(Map.of(
                        "status", "accepted",
                        "message", "Fix loop started — check /actuator/health and the dashboard for progress"
                ));
    }
}
