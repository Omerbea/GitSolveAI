package com.gitsolve.agent.scout;

import com.gitsolve.model.GitRepository;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Pure-function velocity score calculator.
 * Normalizes commit, star, and fork counts across the candidate pool,
 * then combines them as a weighted score in [0, 100].
 *
 * Formula: (commits/maxCommits * 0.5) + (stars/maxStars * 0.3) + (forks/maxForks * 0.2)
 * Each component contributes at most its weight * 100 points.
 */
@Component
public class VelocityScoreCalculator {

    private static final double WEIGHT_COMMITS = 0.5;
    private static final double WEIGHT_STARS   = 0.3;
    private static final double WEIGHT_FORKS   = 0.2;

    /**
     * Computes a velocity score for a single candidate given the pool maximums.
     * Returns 0.0 if all maximums are 0.
     */
    public double compute(int recentCommits, int stars, int forks,
                          int maxCommits, int maxStars, int maxForks) {
        double commitScore = maxCommits > 0 ? (double) recentCommits / maxCommits : 0.0;
        double starScore   = maxStars   > 0 ? (double) stars          / maxStars   : 0.0;
        double forkScore   = maxForks   > 0 ? (double) forks          / maxForks   : 0.0;

        return (commitScore * WEIGHT_COMMITS
                + starScore   * WEIGHT_STARS
                + forkScore   * WEIGHT_FORKS) * 100.0;
    }

    /**
     * Normalizes across the candidate pool, assigns velocityScore to each repo,
     * sorts descending, and returns the top N.
     * Returns all candidates if topN >= candidates.size().
     */
    public List<GitRepository> rankAndFilter(List<GitRepository> candidates, int topN) {
        if (candidates.isEmpty()) return List.of();

        int maxCommits = candidates.stream().mapToInt(GitRepository::commitCount).max().orElse(0);
        int maxStars   = candidates.stream().mapToInt(GitRepository::starCount).max().orElse(0);
        int maxForks   = candidates.stream().mapToInt(GitRepository::forkCount).max().orElse(0);

        return candidates.stream()
                .map(repo -> {
                    double score = compute(
                            repo.commitCount(), repo.starCount(), repo.forkCount(),
                            maxCommits, maxStars, maxForks);
                    // Return a new record with the computed velocityScore
                    return new GitRepository(
                            repo.fullName(), repo.cloneUrl(), repo.htmlUrl(),
                            repo.starCount(), repo.forkCount(), repo.commitCount(),
                            score);
                })
                .sorted(Comparator.comparingDouble(GitRepository::velocityScore).reversed())
                .limit(topN)
                .toList();
    }
}
