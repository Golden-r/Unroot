package org.uspace.core.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stable, filename-safe identity of a registered application.
 *
 * <p>The value doubles as the directory name under {@code $XDG_DATA_HOME/userspace/apps/} and as
 * the suffix of every generated artifact ({@code userspace-<id>.desktop}, {@code bin/<id>}), so it
 * is restricted to lowercase ASCII words and dashes. Renaming an application never changes its id;
 * the display name lives in {@link Application#name()}.
 */
public record ApplicationId(String value) {

    private static final Pattern VALID = Pattern.compile("[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?");

    public ApplicationId {
        Objects.requireNonNull(value, "value");
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid application id: '" + value + "'");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
