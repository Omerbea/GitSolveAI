package com.gitsolve.agent.execution;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * LangChain4j AiService for the Execution Agent.
 *
 * <p>Given pre-generated fix instructions, the current source context, and an optional
 * build error from a previous attempt, produces a JSON object describing ALL files that
 * must be created or modified to implement the fix.
 *
 * <p>Two methods share the same {@link SystemMessage} (applied at interface level):
 * <ul>
 *   <li>{@link #execute} — iteration 1: includes full fix instructions and source context.</li>
 *   <li>{@link #executeFollowUp} — iteration 2+: omits both to avoid duplicating large
 *       payloads that are already in the chat memory window.</li>
 * </ul>
 *
 * <p>Returns raw String — {@link ExecutionFixParser} handles deserialisation and validation.
 */
@SystemMessage("""
        You are an expert software engineer executing a precise set of fix instructions.

        You will receive:
        - The fix instructions (written by a senior engineer) telling you exactly what to change.
        - The current source code context for the repository.
        - Optionally: a build error from a previous attempt at this fix.

        Your task is to return ALL files that need to be created or modified to implement the fix.

        YOU MUST RESPOND WITH ONLY THIS EXACT JSON SCHEMA. NO OTHER FORMAT IS ACCEPTABLE:
        {
          "files": [
            {"path": "relative/path/to/File.java", "content": "<complete file content>"},
            {"path": "README.md", "content": "<complete file content>"}
          ],
          "commitMessage": "<conventional commit message as specified in instructions>",
          "prTitle": "<concise PR title>",
          "prBody": "<markdown PR body; must end with Closes #<issueNumber>>"
        }

        CRITICAL — DO NOT:
        - Use any other JSON schema (no "operations", no "pull_request", no "commits", no "changes" keys)
        - Ask to view files or request more information — use only what is provided
        - Include markdown fences, prose, or explanation outside the JSON
        - Truncate file content

        DO:
        - Include the COMPLETE file content for each entry in "files"
        - Only include files you are actually changing
        - Reference the issue in prBody using "Closes #<issueNumber>"
        - Fix any build error provided
        """)
public interface ExecutionAiService {

    /**
     * First iteration call. Sends fix instructions and full source context.
     * Subsequent iterations must use {@link #executeFollowUp} to avoid
     * sending the same large context multiple times into the chat memory window.
     */
    @UserMessage("""
            Repository: {{repoFullName}}
            Issue #{{issueNumber}}: {{issueTitle}}
            Build tool: {{buildTool}}

            === FIX INSTRUCTIONS ===
            {{fixInstructions}}

            === SOURCE CONTEXT ===
            {{sourceContext}}

            === BUILD ERROR (empty if first attempt) ===
            {{buildError}}

            Implement the fix now. Return only the JSON object.
            """)
    Response<AiMessage> execute(
            @V("repoFullName")     String repoFullName,
            @V("issueNumber")      int    issueNumber,
            @V("issueTitle")       String issueTitle,
            @V("buildTool")        String buildTool,
            @V("fixInstructions")  String fixInstructions,
            @V("sourceContext")    String sourceContext,
            @V("buildError")       String buildError
    );

    /**
     * Follow-up iteration call (iteration 2+). Omits fix instructions and source context —
     * those are already in the chat memory from the first message. Only provides the
     * updated build error so the LLM can correct the previous attempt.
     */
    @UserMessage("""
            Repository: {{repoFullName}}
            Issue #{{issueNumber}}: {{issueTitle}}

            (Source context and fix instructions were provided in the first message — do not re-request them.)

            === BUILD ERROR FROM PREVIOUS ATTEMPT ===
            {{buildError}}

            The previous fix attempt failed. Apply corrections and return the updated JSON object.
            """)
    Response<AiMessage> executeFollowUp(
            @V("repoFullName")  String repoFullName,
            @V("issueNumber")   int    issueNumber,
            @V("issueTitle")    String issueTitle,
            @V("buildError")    String buildError
    );
}
