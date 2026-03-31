package com.gitsolve.agent.buildrepair;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * LangChain4j AiService interface for the Build Repair guidance agent.
 * Wired by AgentConfig as a singleton bean (stateless — no ChatMemory needed).
 *
 * <p>One method per {@link com.gitsolve.agent.buildclassifier.BuildFailureType}, each
 * with a focused {@code @UserMessage} prompt for that failure category.
 *
 * <p>Returns {@code Response<AiMessage>} (plain text hint, not JSON).
 * {@link BuildRepairService} extracts the text and prepends it to the build error
 * for the next fix iteration.
 */
@SystemMessage("""
        You are a repair guidance agent for GitSolve AI, an autonomous system that fixes Java issues.
        For the given build failure, emit a short focused repair hint (2-4 lines, plain text, no JSON)
        that will be prepended to the build error for the next fix iteration.
        Focus only on the specific failure provided. Be concise and actionable.
        Do not repeat the full build output. Do not include markdown formatting.
        """)
public interface BuildRepairAiService {

    @UserMessage("""
            COMPILE_ERROR at {{location}}
            Error excerpt:
            {{excerpt}}
            Suggested fix direction: {{suggestedFix}}
            Full build output (for context):
            {{fullBuildError}}
            """)
    Response<AiMessage> repairCompileError(
            @V("location")       String location,
            @V("excerpt")        String excerpt,
            @V("suggestedFix")   String suggestedFix,
            @V("fullBuildError") String fullBuildError
    );

    @UserMessage("""
            TEST_FAILURE at {{location}}
            Error excerpt:
            {{excerpt}}
            Suggested fix direction: {{suggestedFix}}
            Full build output (for context):
            {{fullBuildError}}
            """)
    Response<AiMessage> repairTestFailure(
            @V("location")       String location,
            @V("excerpt")        String excerpt,
            @V("suggestedFix")   String suggestedFix,
            @V("fullBuildError") String fullBuildError
    );

    @UserMessage("""
            DEPENDENCY_RESOLUTION failure at {{location}}
            Error excerpt:
            {{excerpt}}
            Suggested fix direction: {{suggestedFix}}
            Full build output (for context):
            {{fullBuildError}}
            """)
    Response<AiMessage> repairDependencyResolution(
            @V("location")       String location,
            @V("excerpt")        String excerpt,
            @V("suggestedFix")   String suggestedFix,
            @V("fullBuildError") String fullBuildError
    );

    @UserMessage("""
            WRONG_BUILD_COMMAND at {{location}}
            Error excerpt:
            {{excerpt}}
            Suggested fix direction: {{suggestedFix}}
            Full build output (for context):
            {{fullBuildError}}
            """)
    Response<AiMessage> repairWrongBuildCommand(
            @V("location")       String location,
            @V("excerpt")        String excerpt,
            @V("suggestedFix")   String suggestedFix,
            @V("fullBuildError") String fullBuildError
    );

    @UserMessage("""
            MISSING_FILE at {{location}}
            Error excerpt:
            {{excerpt}}
            Suggested fix direction: {{suggestedFix}}
            Full build output (for context):
            {{fullBuildError}}
            """)
    Response<AiMessage> repairMissingFile(
            @V("location")       String location,
            @V("excerpt")        String excerpt,
            @V("suggestedFix")   String suggestedFix,
            @V("fullBuildError") String fullBuildError
    );

    @UserMessage("""
            UNKNOWN build failure at {{location}}
            Error excerpt:
            {{excerpt}}
            Suggested fix direction: {{suggestedFix}}
            Full build output (for context):
            {{fullBuildError}}
            """)
    Response<AiMessage> repairUnknown(
            @V("location")       String location,
            @V("excerpt")        String excerpt,
            @V("suggestedFix")   String suggestedFix,
            @V("fullBuildError") String fullBuildError
    );
}
