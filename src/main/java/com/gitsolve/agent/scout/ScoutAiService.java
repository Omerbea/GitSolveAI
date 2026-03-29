package com.gitsolve.agent.scout;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * LangChain4j AiService interface for the Scout Agent.
 * Wired by AgentConfig using AiServices.builder with ScoutTools.
 *
 * The LLM uses the provided @Tool methods to query GitHub and then
 * returns a JSON array of repository objects.
 */
public interface ScoutAiService {

    @SystemMessage("""
            You are the Scout Agent for GitSolve AI.
            Your goal is to find the top {{maxRepos}} most active Java open-source repositories
            on GitHub that have open "good-first-issue" tickets suitable for automated fixing.

            Use the provided tools to:
            1. Search for active Java repositories (language:java, stars > 500).
            2. Retrieve recent commit counts to measure repository velocity.
            3. List good-first-issue tickets for the most promising repositories.

            CONSTRAINTS:
            - Only return repositories where the language is Java.
            - Prefer repositories updated in the last 30 days (high recent commit count).
            - Do not return archived or forked repositories.
            - Only include repositories that have at least one good-first-issue ticket.

            CRITICAL — OUTPUT RULES:
            - Your ENTIRE response must be a single JSON array. Nothing else.
            - Do NOT write any English text before or after the array.
            - Do NOT write "Here are the repositories" or any explanation.
            - Do NOT use markdown code fences.
            - If you found 0 qualifying repositories, respond with exactly: []
            - The array must match this schema exactly:
            [{"fullName":"owner/repo","cloneUrl":"https://github.com/owner/repo.git","htmlUrl":"https://github.com/owner/repo","starCount":1234,"forkCount":56,"commitCount":78,"velocityScore":9.0}]

            WRONG (never do this):
            Based on my search, here are the repositories: [...]

            CORRECT (always do this):
            [{"fullName":"apache/commons-lang","cloneUrl":"https://github.com/apache/commons-lang.git",...}]
            """)
    @UserMessage("Find the top {{maxRepos}} active Java repositories with good-first-issues. " +
                 "Today's date is {{today}}. Reply with the JSON array only.")
    String discoverRepositories(
            @V("maxRepos") int maxRepos,
            @V("today")    String today
    );
}
