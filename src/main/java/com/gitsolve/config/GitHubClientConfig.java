package com.gitsolve.config;

import com.gitsolve.github.RateLimitRetryFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Configures the WebClient used by GitHubClient.
 * The bean name "gitHubWebClient" allows test overrides without ambiguity.
 */
@Configuration
public class GitHubClientConfig {

    @Bean("gitHubWebClient")
    public WebClient gitHubWebClient(GitSolveProperties props) {
        return WebClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader(HttpHeaders.AUTHORIZATION,
                        "Bearer " + props.github().appToken())
                .defaultHeader(HttpHeaders.ACCEPT,
                        "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .filter(new RateLimitRetryFilter())
                .build();
    }
}
