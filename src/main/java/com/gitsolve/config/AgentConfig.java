package com.gitsolve.config;

import com.gitsolve.agent.analysis.AnalysisAiService;
import com.gitsolve.agent.execution.ExecutionAiService;
import com.gitsolve.agent.execution.FileSelectorAiService;
import com.gitsolve.agent.instructions.FixInstructionsAiService;
import com.gitsolve.agent.reviewer.ReviewerAiService;
import com.gitsolve.agent.reviewer.RuleExtractorAiService;
import com.gitsolve.agent.scout.ScoutAiService;
import com.gitsolve.agent.scout.ScoutTools;
import com.gitsolve.agent.triage.TriageAiService;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

/**
 * Wires LangChain4j ChatLanguageModel and AiService beans.
 * Uses @ConditionalOnProperty so only the configured provider's model beans are created.
 *
 * Two Anthropic chat models are registered with different temperatures:
 *   - strictChatModel (temp 0.1): used for JSON-output agents that need deterministic structured responses
 *   - generativeChatModel (temp 0.3): used for code-generation agents that benefit from slight variation
 *
 * All AiService factory methods use @Qualifier to explicitly select the correct model bean,
 * avoiding Spring ambiguity when two ChatLanguageModel beans are registered simultaneously.
 */
@Configuration
public class AgentConfig {

    // ------------------------------------------------------------------ //
    // Anthropic                                                            //
    // ------------------------------------------------------------------ //

    /**
     * Low-temperature model for agents that emit structured JSON.
     * Strict agents: TriageAiService, FileSelectorAiService, AnalysisAiService,
     *                ReviewerAiService, RuleExtractorAiService, ScoutAiService.
     */
    @Bean("strictChatModel")
    @ConditionalOnProperty(name = "gitsolve.llm.provider", havingValue = "anthropic")
    public ChatLanguageModel strictChatModel(GitSolveProperties props) {
        return AnthropicChatModel.builder()
                .apiKey(apiKey("ANTHROPIC_API_KEY"))
                .modelName(props.llm().liteModel())
                .maxTokens(16384)
                .maxRetries(5)
                .temperature(0.1)
                .build();
    }

    /**
     * Higher-temperature model for agents that generate code or prose.
     * Generative agents: ExecutionAiService, FixInstructionsAiService.
     */
    @Bean("generativeChatModel")
    @ConditionalOnProperty(name = "gitsolve.llm.provider", havingValue = "anthropic")
    public ChatLanguageModel generativeChatModel(GitSolveProperties props) {
        return AnthropicChatModel.builder()
                .apiKey(apiKey("ANTHROPIC_API_KEY"))
                .modelName(props.llm().powerModel())
                .maxTokens(16384)
                .maxRetries(5)
                .temperature(0.3)
                .build();
    }

    // ------------------------------------------------------------------ //
    // Google Gemini                                                        //
    // ------------------------------------------------------------------ //

    @Bean
    @ConditionalOnProperty(name = "gitsolve.llm.provider", havingValue = "gemini")
    public ChatLanguageModel geminiChatModel(GitSolveProperties props) {
        return GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey("GEMINI_API_KEY"))
                .modelName(props.llm().model())
                .build();
    }

    // ------------------------------------------------------------------ //
    // AiService beans (Anthropic; conditional on provider=anthropic)      //
    // ------------------------------------------------------------------ //

    // --- Generative agents (code / prose output; temp 0.3) --- //

    @Bean
    @ConditionalOnProperty(name = "gitsolve.llm.provider", havingValue = "anthropic")
    public FixInstructionsAiService fixInstructionsAiService(
            @Qualifier("generativeChatModel") ChatLanguageModel model) {
        return AiServices.builder(FixInstructionsAiService.class)
                .chatLanguageModel(model)
                .build();
    }

    @Bean
    @Scope("prototype")
    @ConditionalOnProperty(name = "gitsolve.llm.provider", havingValue = "anthropic")
    public ExecutionAiService executionAiService(
            @Qualifier("generativeChatModel") ChatLanguageModel model) {
        return AiServices.builder(ExecutionAiService.class)
                .chatLanguageModel(model)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
                .build();
    }

    // --- Strict agents (JSON output; temp 0.1) --- //

    @Bean
    @ConditionalOnProperty(name = "gitsolve.llm.provider", havingValue = "anthropic")
    public FileSelectorAiService fileSelectorAiService(
            @Qualifier("strictChatModel") ChatLanguageModel model) {
        return AiServices.builder(FileSelectorAiService.class)
                .chatLanguageModel(model)
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "gitsolve.llm.provider", havingValue = "anthropic")
    public AnalysisAiService analysisAiService(
            @Qualifier("strictChatModel") ChatLanguageModel model) {
        return AiServices.builder(AnalysisAiService.class)
                .chatLanguageModel(model)
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "gitsolve.llm.provider", havingValue = "anthropic")
    public ScoutAiService scoutAiService(
            @Qualifier("strictChatModel") ChatLanguageModel model,
            ScoutTools tools) {
        return AiServices.builder(ScoutAiService.class)
                .chatLanguageModel(model)
                .tools(tools)
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "gitsolve.llm.provider", havingValue = "anthropic")
    public TriageAiService triageAiService(
            @Qualifier("strictChatModel") ChatLanguageModel model) {
        return AiServices.builder(TriageAiService.class)
                .chatLanguageModel(model)
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "gitsolve.llm.provider", havingValue = "anthropic")
    public RuleExtractorAiService ruleExtractorAiService(
            @Qualifier("strictChatModel") ChatLanguageModel model) {
        return AiServices.builder(RuleExtractorAiService.class)
                .chatLanguageModel(model)
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "gitsolve.llm.provider", havingValue = "anthropic")
    public ReviewerAiService reviewerAiService(
            @Qualifier("strictChatModel") ChatLanguageModel model) {
        return AiServices.builder(ReviewerAiService.class)
                .chatLanguageModel(model)
                .build();
    }

    // ------------------------------------------------------------------ //
    // Helpers                                                              //
    // ------------------------------------------------------------------ //

    /**
     * Returns the env var value, or a placeholder string if not set.
     * LangChain4j clients accept a placeholder at construction time —
     * the real key is only needed when an actual API call is made.
     */
    private static String apiKey(String envVar) {
        String value = System.getenv(envVar);
        return (value != null && !value.isBlank()) ? value : "PLACEHOLDER-NOT-SET";
    }
}
