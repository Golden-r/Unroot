package org.uspace.discovery;

import java.util.List;
import java.util.Objects;

/**
 * Everything one scan produced: what it found, what it could not use, and how far it got.
 *
 * <p>The counters are not decoration. {@code filesInspected} distinguishes "your scan root holds
 * nothing" from "your scan root was not looked at", {@code truncated} says the candidate limit was
 * hit, and {@code cancelled} says the numbers describe a partial walk — all three are states a
 * progress dialog has to show honestly instead of printing a bare count.
 *
 * @param candidates       proposals, ordered by display name
 * @param issues           paths that could not be used, with reasons
 * @param filesInspected   regular files examined
 * @param directoriesVisited directories entered
 * @param cancelled        true when the caller asked to stop
 * @param truncated        true when {@link DiscoveryScanner}'s candidate limit was reached
 */
public record DiscoveryResult(
        List<DiscoveryCandidate> candidates,
        List<DiscoveryIssue> issues,
        int filesInspected,
        int directoriesVisited,
        boolean cancelled,
        boolean truncated) {

    public DiscoveryResult {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(issues, "issues");
        candidates = List.copyOf(candidates);
        issues = List.copyOf(issues);
    }

    public static DiscoveryResult empty() {
        return new DiscoveryResult(List.of(), List.of(), 0, 0, false, false);
    }

    public int candidateCount() {
        return candidates.size();
    }
}
