package org.uspace.config;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One application that could not be loaded, and why.
 *
 * <p>Loading is deliberately best-effort: a single unreadable or corrupt directory must not hide the
 * other registrations, and the user needs to see exactly which file is at fault. Issues are reported
 * to the UI instead of being logged and forgotten.
 *
 * @param file    the metadata file, or the application directory when the file is absent
 * @param message short human-readable explanation
 */
public record MetadataIssue(Path file, String message) {

    public MetadataIssue {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(message, "message");
    }

    @Override
    public String toString() {
        return file + ": " + message;
    }
}
