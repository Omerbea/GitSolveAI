package com.gitsolve.config;

import com.gitsolve.agent.analysis.AnalysisAiService;
import com.gitsolve.agent.execution.ExecutionAiService;
import com.gitsolve.agent.execution.FileSelectorAiService;
import com.gitsolve.agent.instructions.FixInstructionsAiService;
import com.gitsolve.agent.reviewer.ReviewerAiService;
import com.gitsolve.agent.reviewer.RuleExtractorAiService;
import com.gitsolve.agent.scout.ScoutAiService;
import com.gitsolve.agent.scout.ScoutTools;
import com.gitsolve.agent.swe.SweAiService;
import com.gitsolve.agent.triage.TriageAiService;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

/**
 * Wires LangChain4j ChatLanguageModel and AiService beans.
 * Uses @ConditionalOnProperty so only the configured provider's model beans are created.
 * AiService beans are conditional on a ChatLanguageModel being present.
 */
@Configuration
public class AgentConfig {

    // ------------------------------------------------------------------ //
    // Anthropic                                                            //
    // ------------------------------------------------------------------ //

    @Bean
    @ConditionalOnProperty(name = "gitsolve.llm.provider", havingValue = "anthropic")
    public ChatLanguageModel anthropicChatModel(GitSolveProperties props) {
        return AnthropicChatModel.builder()
                .apiKey(apiKey("ANTHROPIC_API_KEY"))
                .modelName(props.llm().model())
                .maxTokens(16384)  // haiku-4-5 supports 64k output; 16k is ample for complete file rewrites
                .maxRetries(5)   // retries on 429 rate-limit with exponential backoff
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "gitsolve.llm.provider", havingValue = "anthropic")
    public StreamingChatLanguageModel anthropicStreamingChatModel(GitSolveProperties props) {
        return AnthropicStreamingChatModel.builder()
                .apiKey(apiKey("ANTHROPIC_API_KEY"))
                .modelName(props.llm().model())
                .maxTokens(16384)
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
    // AiService beans (conditional on a ChatLanguageModel being present)  //
    // ------------------------------------------------------------------ //

    @Bean
    @ConditionalOnBean(ChatLanguageModel.class)
    public FixInstructionsAiService fixInstructionsAiService(ChatLanguageModel model) {
        return AiServices.builder(FixInstructionsAiService.class)
                .chatLanguageModel(model)
                .build();
    }

    @Bean
    @ConditionalOnBean(ChatLanguageModel.class)
    public FileSelectorAiService fileSelectorAiService(ChatLanguageModel model) {
        return AiServices.builder(FileSelectorAiService.class)
                .chatLanguageModel(model)
                .build();
    }

    @Bean
    @Scope("prototype")
    @ConditionalOnBean(ChatLanguageModel.class)
    public ExecutionAiService executionAiService(ChatLanguageModel model) {
        return AiServices.builder(ExecutionAiService.class)
                .chatLanguageModel(model)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
                .build();
    }

    @Bean
    @ConditionalOnBean(ChatLanguageModel.class)
    public AnalysisAiService analysisAiService(ChatLanguageModel model) {
        return AiServices.builder(AnalysisAiService.class)
                .chatLanguageModel(model)
                .build();
    }

    @Bean
    @ConditionalOnBean(ChatLanguageModel.class)
    public ScoutAiService scoutAiService(ChatLanguageModel model, ScoutTools tools) {
        return AiServices.builder(ScoutAiService.class)
                .chatLanguageModel(model)
                .tools(tools)
                .build();
    }

    @Bean
    @ConditionalOnBean(ChatLanguageModel.class)
    public TriageAiService triageAiService(ChatLanguageModel model) {
        return AiServices.builder(TriageAiService.class)
                .chatLanguageModel(model)
                .build();
    }

    @Bean
    @ConditionalOnBean(ChatLanguageModel.class)
    public RuleExtractorAiService ruleExtractorAiService(ChatLanguageModel model) {
        return AiServices.builder(RuleExtractorAiService.class)
                .chatLanguageModel(model)
                .build();
    }

    @Bean
    @ConditionalOnBean(ChatLanguageModel.class)
    public ReviewerAiService reviewerAiService(ChatLanguageModel model) {
        return AiServices.builder(ReviewerAiService.class)
                .chatLanguageModel(model)
                .build();
    }

    @Bean
    @Scope("prototype")
    @ConditionalOnBean(StreamingChatLanguageModel.class)
    public SweAiService sweAiService(StreamingChatLanguageModel model) {
        return AiServices.builder(SweAiService.class)
                .streamingChatLanguageModel(model)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
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
