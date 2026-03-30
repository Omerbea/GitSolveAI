package com.gitsolve.dashboard;

import com.gitsolve.model.AppSettings;
import com.gitsolve.model.AppSettings.ScoutMode;
import com.gitsolve.persistence.SettingsStore;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Settings page controller.
 *
 * GET  /settings — render the settings form
 * POST /settings — save changes and redirect back
 */
@Controller
@RequestMapping("/settings")
public class SettingsController {

    private final SettingsStore settingsStore;

    public SettingsController(SettingsStore settingsStore) {
        this.settingsStore = settingsStore;
    }

    @GetMapping
    public String showSettings(Model model) {
        AppSettings settings = settingsStore.load();
        model.addAttribute("settings", settings);
        model.addAttribute("scoutModes", ScoutMode.values());
        model.addAttribute("targetReposText",
                settings.targetRepos() != null
                        ? String.join("\n", settings.targetRepos())
                        : "");
        return "dashboard/settings";
    }

    @PostMapping
    public String saveSettings(
            @RequestParam("scoutMode")        String scoutModeStr,
            @RequestParam("targetReposText")  String targetReposText,
            @RequestParam("starMin")          int starMin,
            @RequestParam("starMax")          int starMax,
            @RequestParam("maxReposPerRun")   int maxReposPerRun,
            @RequestParam("maxIssuesPerRepo") int maxIssuesPerRepo,
            RedirectAttributes redirectAttributes) {

        ScoutMode mode;
        try {
            mode = ScoutMode.valueOf(scoutModeStr);
        } catch (IllegalArgumentException e) {
            mode = ScoutMode.PINNED;
        }

        List<String> targetRepos = Arrays.stream(targetReposText.split("\\r?\\n"))
                .map(String::strip)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());

        AppSettings updated = new AppSettings(
                mode,
                targetRepos,
                Math.max(1, starMin),
                Math.max(starMin + 1, starMax),
                Math.max(1, Math.min(20, maxReposPerRun)),
                Math.max(1, Math.min(20, maxIssuesPerRepo))
        );

        settingsStore.save(updated);
        redirectAttributes.addFlashAttribute("saved", true);
        return "redirect:/settings";
    }
}
