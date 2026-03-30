package com.gitsolve.agent.execution;

import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * LangChain4j AiService — Step 1 of the two-step execution flow.
 *
 * <p>Given the fix instructions and a flat list of available file paths (no content),
 * asks the LLM which files it needs to read or modify. The response is a small JSON
 * object — no file content involved, so input and output token counts are minimal.
 *
 * <p>The result is validated by {@link FileSelectorParser}, which filters out any
 * hallucinated paths not present in the original list.
 */
public interface FileSelectorAiService {

    @SystemMessage("""
            You are a senior software engineer doing targeted code analysis.

            You will receive:
            - Fix instructions describing what needs to change in a Java repository.
            - A list of Java file paths available in the repository.

            Your task: identify which files need to be READ OR MODIFIED to implement the fix.

            Rules:
            - Return AT MOST 5 file paths.
            - Only return paths from the provided list — never invent paths.
            - Prefer files whose names match classes, packages, or concepts mentioned in the fix instructions.
            - Return ONLY valid JSON — no markdown, no prose:
              {"paths": ["path/to/File.java", "path/to/Other.java"]}
            """)
    @UserMessage("""
            Fix instructions:
            {{fixInstructions}}

            Available files (one per line):
            {{filePaths}}

            Build error from previous attempt (empty on first iteration):
            {{buildError}}

            Analysis-suggested files (use as priority hints, may be empty):
            {{analysisHints}}

            Return the JSON object with the paths you need.
            """)
    Response<String> selectFiles(
            @V("fixInstructions") String fixInstructions,
            @V("filePaths")       String filePaths,
            @V("buildError")      String buildError,
            @V("analysisHints")   String analysisHints
    );
}
