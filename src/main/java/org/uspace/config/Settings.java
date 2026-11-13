package org.uspace.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * User preferences, deliberately small: only settings that later milestones actually consume.
 *
 * <p>Preferences are not data. A malformed or unreadable settings file must never stop the
 * application from starting — it falls back to {@link #defaults()} and reports the problem — whereas
 * a malformed registration is reported and skipped but never silently replaced.
 *
 * @param scanRoots           directories discovery may walk (M3); absolute
 * @param confirmBeforeWriting ask before touching files outside our own data directory; defaults to
 *                            {@code true} because the safe answer when the setting is missing is "ask"
 * @param preferredShell      shell to integrate with (M5), or empty to detect later
 */
public record Settings(List<Path> scanRoots, boolean confirmBeforeWriting, String preferredShell) {

    public Settings {
        Objects.requireNonNull(scanRoots, "scanRoots");
        Objects.requireNonNull(preferredShell, "preferredShell");
        for (Path root : scanRoots) {
            if (!root.isAbsolute()) {
                throw new IllegalArgumentException("scan root must be absolute: " + root);
            }
        }
        scanRoots = List.copyOf(scanRoots);
    }

    /** Empty scan roots, asking before writing, no shell chosen. */
    public static Settings defaults() {
        return new Settings(List.of(), true, "");
    }
}
