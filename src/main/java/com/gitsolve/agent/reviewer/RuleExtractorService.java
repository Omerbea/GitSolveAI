package com.gitsolve.agent.reviewer;

import com.gitsolve.github.GitHubClient;
import com.gitsolve.model.ConstraintJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Fetches a repository's CONTRIBUTING.md via {@link GitHubClient} and extracts
 * structured governance constraints using {@link RuleExtractorAiService}.
 *
 * <p>When the file is absent, empty, or blank, a safe default {@link ConstraintJson}
 * is returned — {@code requiresTests=true}, all other fields at their zero/null defaults.
 * This ensures callers never receive {@code null} and the Reviewer Agent can always proceed.
 */
@Service
public class RuleExtractorService {

    private static final Logger log = LoggerFactory.getLogger(RuleExtractorService.class);

    private static final String CONTRIBUTING_PATH = "CONTRIBUTING.md";

    private final RuleExtractorAiService ruleExtractorAiService;
    private final GitHubClient gitHubClient;

    public RuleExtractorService(RuleExtractorAiService ruleExtractorAiService,
                                GitHubClient gitHubClient) {
        this.ruleExtractorAiService = ruleExtractorAiService;
        this.gitHubClient           = gitHubClient;
    }

    // ------------------------------------------------------------------ //
    // Public API                                                           //
    // ------------------------------------------------------------------ //

    /**
     * Extracts governance constraints for the given repository.
     *
     * <p>Fetches {@code CONTRIBUTING.md} from the repository. If the file is absent,
     * empty, or blank, returns {@link #defaultConstraints()}. Otherwise calls the
     * LLM to parse the content into a {@link ConstraintJson}.
     *
     * @param repoFullName the GitHub "owner/repo" identifier
     * @return a non-null {@link ConstraintJson} — never throws to caller
     */
    public ConstraintJson extract(String repoFullName) {
        String content = gitHubClient
                .getFileContent(repoFullName, CONTRIBUTING_PATH)
                .blockOptional()
                .orElse("");

        if (content == null || content.isBlank()) {
            log.info("RuleExtractor: no CONTRIBUTING.md found for {} — using defaults", repoFullName);
            return defaultConstraints();
        }

        log.info("RuleExtractor: fetched CONTRIBUTING.md for {} ({} chars)", repoFullName, content.length());

        ConstraintJson constraints = ruleExtractorAiService.extractRules(content);

        log.debug("RuleExtractor: extracted constraints for {} — requiresTests={}, jdk={}, " +
                        "requiresDco={}, buildCommand={}, forbiddenPatterns={}",
                repoFullName,
                constraints.requiresTests(),
                constraints.jdkVersion(),
                constraints.requiresDco(),
                constraints.buildCommand(),
                constraints.forbiddenPatterns());

        return constraints;
    }

    // ------------------------------------------------------------------ //
    // Private helpers                                                      //
    // ------------------------------------------------------------------ //

    /**
     * Safe default constraints used when no CONTRIBUTING.md is present.
     * {@code requiresTests=true} is the conservative default — assume tests are required.
     */
    static ConstraintJson defaultConstraints() {
        return new ConstraintJson(
                null,           // checkstyleConfig — none known
                false,          // requiresDco
                true,           // requiresTests — conservative default
                "unknown",      // jdkVersion
                "mvn test",     // buildCommand
                List.of()       // forbiddenPatterns
        );
    }
}
