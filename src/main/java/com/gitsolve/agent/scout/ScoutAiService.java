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

            OUTPUT FORMAT — respond with a JSON array and nothing else:
            [{"fullName":"owner/repo","cloneUrl":"https://...","htmlUrl":"https://...",\
            "starCount":0,"forkCount":0,"commitCount":0,"velocityScore":0.0}]

            Do not include any text, explanation, or markdown outside the JSON array.
            """)
    @UserMessage("Find the top {{maxRepos}} active Java repositories with good-first-issues. " +
                 "Today's date is {{today}}.")
    String discoverRepositories(
            @V("maxRepos") int maxRepos,
            @V("today")    String today
    );
}
