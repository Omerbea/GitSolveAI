package com.gitsolve.agent.triage;

/**
 * Structured output from the Triage Agent's LLM classification call.
 * LangChain4j extracts this from the LLM's JSON response.
 *
 * Pure Java record — no framework annotations.
 */
public record TriageResponse(
        String complexity,           // "EASY" or "MEDIUM"
        String reasoning,            // 1-2 sentence explanation
        boolean hasClearSteps,       // Does the issue describe a clear fix path?
        boolean requiresUiWork,      // true = skip (UI work not supported)
        boolean requiresDbMigration  // true = skip (schema changes not supported)
) {}
