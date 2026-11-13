package org.uspace.core.registry;

import java.util.Objects;
import org.uspace.core.model.ApplicationId;

/**
 * Outcome of a registry mutation, as a value rather than an exception.
 *
 * <p>Duplicates are ordinary user input, not failures: the UI must show "already registered as
 * <id>" next to the form, so every expected outcome is a value the caller is forced to look at.
 *
 * @param outcome what happened
 * @param id      the application the call was about
 * @param message detail for the user, empty when there is nothing to add
 */
public record RegistryResult(Outcome outcome, ApplicationId id, String message) {

    public enum Outcome {
        REGISTERED,
        UPDATED,
        REMOVED,
        /** Another application already uses this id. */
        DUPLICATE_ID,
        /** Another application is already registered for the same executable. */
        DUPLICATE_EXECUTABLE,
        /** The id is not registered. */
        NOT_FOUND,
        /** Another process holds the write lock beyond the timeout; nothing was changed. */
        LOCK_UNAVAILABLE,
        /** The in-memory state was not changed because the metadata could not be written. */
        STORE_FAILURE
    }

    public RegistryResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(id, "id");
        message = message == null ? "" : message;
    }

    static RegistryResult of(Outcome outcome, ApplicationId id) {
        return new RegistryResult(outcome, id, "");
    }

    static RegistryResult of(Outcome outcome, ApplicationId id, String message) {
        return new RegistryResult(outcome, id, message);
    }

    /** True for the outcomes that changed persistent state. */
    public boolean isSuccess() {
        return outcome == Outcome.REGISTERED || outcome == Outcome.UPDATED || outcome == Outcome.REMOVED;
    }

    @Override
    public String toString() {
        return outcome + " " + id + (message.isEmpty() ? "" : ": " + message);
    }
}
