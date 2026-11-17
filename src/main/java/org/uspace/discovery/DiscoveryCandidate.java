package org.uspace.discovery;

import java.nio.file.Path;
import java.util.Objects;
import org.uspace.core.model.Application;
import org.uspace.core.model.ApplicationId;

/**
 * One application the scan believes it found, offered for review.
 *
 * <p>It wraps a fully built {@link Application} instead of a half-filled form: the user can register
 * it unchanged, or edit it, and either way nothing about the candidate is recomputed later. The
 * confidence and the reason are part of the value because "why is this in the list?" is the question
 * a review screen has to answer, and a boolean 'isAppImage' cannot.
 *
 * @param application the proposal, with an id unique within this scan
 * @param confidence  how much was verified
 * @param reason      one sentence naming the evidence
 */
public record DiscoveryCandidate(Application application, Confidence confidence, String reason) {

    public DiscoveryCandidate {
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("candidate reason must not be blank");
        }
    }

    public ApplicationId id() {
        return application.id();
    }

    public Path executable() {
        return application.launch().executable();
    }
}
