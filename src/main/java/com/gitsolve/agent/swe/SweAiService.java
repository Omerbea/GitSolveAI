package com.gitsolve.agent.swe;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * LangChain4j AiService interface for the SWE Agent.
 * Returns a {@link TokenStream} so the caller can stream the response
 * and block with a {@link java.util.concurrent.CountDownLatch}.
 *
 * Wired by AgentConfig using AiServices.builder with a StreamingChatLanguageModel.
 */
public interface SweAiService {

    @SystemMessage("""
            You are the SWE Agent for GitSolve AI, an autonomous system that fixes Java issues.
            You will be given a GitHub issue and the source context of the repository.
            Your task is to produce a targeted fix for the reported issue.

            Rules:
            1. Return ONLY a JSON object — no markdown, no explanation outside the JSON, no code fences.
            2. The JSON must match this exact schema:
               {
                 "filePath":     "relative/path/to/File.java",
                 "fixedContent": "full updated content of the file",
                 "explanation":  "one or two sentences describing the fix"
               }
            3. "filePath" must be a relative path from the repository root (e.g. "src/main/java/com/example/Foo.java").
            4. "fixedContent" must be the complete, compilable content of the file after the fix — not a diff or a partial snippet.
            5. Fix only what is necessary to address the issue; do not reformat unrelated code.
            6. If the build error is provided, make sure the fix resolves it.

            Do not include any text, explanation, or markdown outside the JSON object.
            """)
    @UserMessage("""
            Repository: {{repoFullName}}
            Issue: {{issueTitle}}

            Issue body:
            {{issueBody}}

            Source context (relevant Java files):
            {{sourceContext}}

            Build error (empty if first iteration):
            {{buildError}}

            Return the JSON fix object now.
            """)
    TokenStream generateFix(
            @V("repoFullName")   String repoFullName,
            @V("issueTitle")     String issueTitle,
            @V("issueBody")      String issueBody,
            @V("sourceContext")  String sourceContext,
            @V("buildError")     String buildError
    );
}
