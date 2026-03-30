package com.gitsolve.model;

/**
 * Aggregated telemetry for a complete fix pipeline run, containing per-phase stats
 * for the four agent phases: Analysis, FileSelector, Execution, and Reviewer.
 *
 * <p>Stored as part of {@link FixReport} in the JSONB column {@code fix_report}.
 * Jackson serialises/deserialises this transparently alongside the other FixReport fields.
 *
 * <p>Any phase that was not reached (e.g. file selector on an analysis-only report) will
 * have a {@code null} field — templates must null-guard before rendering.
 */
public record TelemetryReport(
        PhaseStats analysis,
        PhaseStats fileSelector,
        PhaseStats execution,
        PhaseStats reviewer
) {}
