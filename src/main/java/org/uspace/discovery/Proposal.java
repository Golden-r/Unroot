package org.uspace.discovery;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.uspace.core.model.AppType;

/**
 * What a single file or directory looked like, before it gets an identity.
 *
 * <p>Identity belongs to the scan, not to the classifier: only the scan knows which ids are already
 * taken, which root a candidate came from, and when the walk happened. Keeping that split is what
 * lets the classifier stay a pure "look at these bytes and say what this is" step, with no slug
 * allocation and no clock reads.
 *
 * <p>Package-private on purpose — {@link DiscoveryCandidate} is the public shape.
 */
record Proposal(
        AppType type,
        Path executable,
        List<String> arguments,
        Path workingDirectory,
        Path installRoot,
        Path icon,
        String displayName,
        Confidence confidence,
        String reason) {

    Proposal {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(reason, "reason");
        arguments = List.copyOf(arguments);
        if (type.ownsInstallRoot() && installRoot == null) {
            throw new IllegalArgumentException(type + " requires an install root: " + executable);
        }
        if (!type.ownsInstallRoot() && installRoot != null) {
            throw new IllegalArgumentException(type + " must not claim an install root: " + installRoot);
        }
    }
}
