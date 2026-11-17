package org.uspace.discovery;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Something the scan could not turn into a candidate, with the reason.
 *
 * <p>Discovery's contract is that it reports rather than drops: an unreadable directory, a jar
 * without a {@code Main-Class}, an executable that is already registered. Without this list the only
 * visible outcome of those cases would be a smaller number, which is indistinguishable from a bug.
 *
 * @param path    the path the issue is about
 * @param message what was wrong, phrased for a person
 */
public record DiscoveryIssue(Path path, String message) {

    public DiscoveryIssue {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(message, "message");
        if (message.isBlank()) {
            throw new IllegalArgumentException("issue message must not be blank");
        }
    }

    @Override
    public String toString() {
        return path + " — " + message;
    }
}
