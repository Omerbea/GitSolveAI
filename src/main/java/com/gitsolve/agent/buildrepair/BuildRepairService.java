package com.gitsolve.agent.buildrepair;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gitsolve.agent.buildclassifier.BuildFailure;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Build Repair routing service.
 *
 * <p>Dispatches a classified {@link BuildFailure} to the appropriate per-type repair
 * method on {@link BuildRepairAiService} and returns a focused repair hint (plain text)
 * suitable for prepending to the build error on the next fix iteration.
 *
 * <p>On any failure returns the original {@code rawBuildError} unchanged — the fix loop
 * can always continue with the unmodified error. Never throws to the caller.
 *
 * <p>Singleton scope — stateless, thread-safe (no ChatMemory needed).
 */
@Service
public class BuildRepairService {

    private static final Logger log = LoggerFactory.getLogger(BuildRepairService.class);

    private final BuildRepairAiService aiService;
    @SuppressWarnings("unused") // kept for consistency with other agent services; may be used for future JSON repair paths
    private final ObjectMapper objectMapper;

    public BuildRepairService(BuildRepairAiService aiService,
                              ObjectMapper objectMapper) {
        this.aiService    = aiService;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ //
    // Public API                                                           //
    // ------------------------------------------------------------------ //

    /**
     * Generates a focused repair hint for the given build failure.
     *
     * @param failure       classified failure from {@link com.gitsolve.agent.buildclassifier.BuildFailureClassifier}
     * @param rawBuildError the raw build output (used as context and as fallback)
     * @return a trimmed plain-text repair hint, or {@code rawBuildError} on any error or blank result
     */
    public String repair(BuildFailure failure, String rawBuildError) {
        try {
            Response<AiMessage> response = dispatch(failure, rawBuildError);
            String hint = response.content().text();

            if (hint == null || hint.isBlank()) {
                return rawBuildError;
            }

            return hint.trim();

        } catch (Exception e) {
            log.warn("BuildRepairService: repair call failed for type {}, returning rawBuildError. Reason: {}",
                    failure.type(), e.getMessage());
            return rawBuildError;
        }
    }

    // ------------------------------------------------------------------ //
    // Private helpers                                                      //
    // ------------------------------------------------------------------ //

    private Response<AiMessage> dispatch(BuildFailure failure, String rawBuildError) {
        String location    = failure.location()     != null ? failure.location()     : "";
        String excerpt     = failure.excerpt()      != null ? failure.excerpt()      : "";
        String suggested   = failure.suggestedFix() != null ? failure.suggestedFix() : "";

        return switch (failure.type()) {
            case COMPILE_ERROR           -> aiService.repairCompileError(location, excerpt, suggested, rawBuildError);
            case TEST_FAILURE            -> aiService.repairTestFailure(location, excerpt, suggested, rawBuildError);
            case DEPENDENCY_RESOLUTION   -> aiService.repairDependencyResolution(location, excerpt, suggested, rawBuildError);
            case WRONG_BUILD_COMMAND     -> aiService.repairWrongBuildCommand(location, excerpt, suggested, rawBuildError);
            case MISSING_FILE            -> aiService.repairMissingFile(location, excerpt, suggested, rawBuildError);
            case LLM_HALLUCINATION       -> aiService.repairUnknown(location, excerpt, suggested, rawBuildError);
        };
    }
}
