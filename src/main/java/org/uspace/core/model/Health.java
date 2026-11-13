package org.uspace.core.model;

import java.time.Instant;
import java.util.List;

/**
 * Result of probing an application against the filesystem: computed on demand, never persisted.
 *
 * <p>Keeping health out of {@link Application} means a stale registration cannot be mistaken for a
 * broken one, and a read-only scan never rewrites the registry.
 *
 * @param status   overall verdict
 * @param issues   human-readable problems, in the order they were detected
 * @param checked  when the probe ran
 */
public record Health(Status status, List<String> issues, Instant checked) {

    public enum Status {
        /** Executable exists, is executable, and required directories are present. */
        OK,
        /** Something is missing or wrong; the app will probably not start. */
        BROKEN,
        /** The registration itself is fine but part of the integration is out of date. */
        STALE
    }

    public Health {
        issues = List.copyOf(issues);
    }

    public static Health ok() {
        return new Health(Status.OK, List.of(), Instant.now());
    }

    public static Health broken(List<String> issues) {
        return new Health(Status.BROKEN, issues, Instant.now());
    }

    public boolean isOk() {
        return status == Status.OK;
    }
}
