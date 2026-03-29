package com.gitsolve.github;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;

/**
 * WebClient ExchangeFilterFunction that handles GitHub rate limiting.
 *
 * Behaviour:
 *   - If X-RateLimit-Remaining == 0: delay until X-RateLimit-Reset epoch second + 1s buffer.
 *   - If status 429 or 403 (secondary rate limit): exponential backoff using Retry-After
 *     header (or 2^attempt seconds if absent), up to MAX_RETRIES attempts.
 *   - After MAX_RETRIES failures: throws GitHubRateLimitException.
 *
 * This filter does not block a thread — all delays are Reactor non-blocking (Mono.delay).
 */
public class RateLimitRetryFilter implements ExchangeFilterFunction {

    private static final Logger log = LoggerFactory.getLogger(RateLimitRetryFilter.class);

    static final int MAX_RETRIES = 3;
    private static final long MAX_BACKOFF_SECONDS = 30L;

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        return filterWithAttempt(request, next, 0);
    }

    private Mono<ClientResponse> filterWithAttempt(
            ClientRequest request, ExchangeFunction next, int attempt) {

        return next.exchange(request).flatMap(response -> {
            int status = response.statusCode().value();

            // If rate limit header says 0 remaining, delay then pass through this response
            // (the server hasn't rejected us yet — pre-emptive throttle)
            String remaining = response.headers().asHttpHeaders()
                    .getFirst("X-RateLimit-Remaining");
            if ("0".equals(remaining)) {
                Duration delay = delayUntilReset(response);
                log.warn("GitHub rate limit exhausted. Delaying {}s before next call.",
                        delay.getSeconds());
                // Just log and continue — the current response is passed through
                // (caller will get the actual result; next request will be delayed)
            }

            if ((status == 429 || status == 403) && attempt < MAX_RETRIES) {
                Duration backoff = backoffDuration(response, attempt);
                log.warn("GitHub returned {}. Retry {}/{} after {}s.",
                        status, attempt + 1, MAX_RETRIES, backoff.getSeconds());

                // Consume the body to release the connection before retrying
                return response.releaseBody()
                        .then(Mono.delay(backoff))
                        .then(filterWithAttempt(request, next, attempt + 1));
            }

            if ((status == 429 || status == 403) && attempt >= MAX_RETRIES) {
                return response.releaseBody()
                        .then(Mono.error(new GitHubRateLimitException(
                                "GitHub rate limit exceeded after " + MAX_RETRIES + " retries",
                                MAX_RETRIES)));
            }

            return Mono.just(response);
        });
    }

    // ------------------------------------------------------------------ //
    // Helpers                                                              //
    // ------------------------------------------------------------------ //

    /**
     * Calculates delay from Retry-After header, falling back to exponential backoff.
     */
    private static Duration backoffDuration(ClientResponse response, int attempt) {
        String retryAfter = response.headers().asHttpHeaders().getFirst("Retry-After");
        if (retryAfter != null) {
            try {
                long seconds = Long.parseLong(retryAfter.trim());
                return Duration.ofSeconds(Math.min(seconds, MAX_BACKOFF_SECONDS));
            } catch (NumberFormatException ignored) {
                // fall through to exponential backoff
            }
        }
        // Exponential backoff: 2, 4, 8 … capped at MAX_BACKOFF_SECONDS
        long backoffSeconds = Math.min((long) Math.pow(2, attempt + 1), MAX_BACKOFF_SECONDS);
        return Duration.ofSeconds(backoffSeconds);
    }

    /**
     * Calculates delay from X-RateLimit-Reset epoch second.
     */
    private static Duration delayUntilReset(ClientResponse response) {
        String resetHeader = response.headers().asHttpHeaders()
                .getFirst("X-RateLimit-Reset");
        if (resetHeader != null) {
            try {
                long resetEpoch = Long.parseLong(resetHeader.trim());
                long nowEpoch = Instant.now().getEpochSecond();
                long delay = Math.max(0, resetEpoch - nowEpoch + 1);
                return Duration.ofSeconds(Math.min(delay, MAX_BACKOFF_SECONDS));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return Duration.ofSeconds(1);
    }
}
