package com.gitsolve.dashboard;

import com.gitsolve.model.IssueStatus;
import com.gitsolve.persistence.IssueStore;
import com.gitsolve.persistence.entity.IssueRecord;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Dashboard controller — serves the local HTML fix-history UI.
 *
 * <p>Routes:
 * <ul>
 *   <li>GET /                   — fix history table, run stats, recent runs</li>
 *   <li>GET /issues/{id}/diff   — unified diff view for a single issue</li>
 *   <li>GET /issues/{id}/report — full investigation report for a single issue</li>
 * </ul>
 */
@Controller
public class DashboardController {

    private final IssueStore issueStore;

    public DashboardController(IssueStore issueStore) {
        this.issueStore = issueStore;
    }

    // ------------------------------------------------------------------ //
    // GET /                                                                //
    // ------------------------------------------------------------------ //

    @GetMapping("/")
    public String index(Model model) {
        // Combine SUCCESS and FAILED records, sort newest first
        List<IssueRecord> issues = new ArrayList<>();
        issues.addAll(issueStore.findByStatus(IssueStatus.SUCCESS));
        issues.addAll(issueStore.findByStatus(IssueStatus.FAILED));
        issues.sort(Comparator.comparing(
                IssueRecord::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())));

        model.addAttribute("issues",     issues);
        model.addAttribute("stats",      issueStore.currentRunStats());
        model.addAttribute("recentRuns", issueStore.recentRuns(10));
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

        model.addAttribute("issue",  record);
        model.addAttribute("report", record.getFixReport());
        return "dashboard/report";
    }
}
