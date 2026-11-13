package org.uspace.system;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Resolved XDG base directories and the UserSpace paths derived from them.
 *
 * <p>Every value comes from an environment variable, falling back to the specification default when
 * the variable is unset <em>or empty</em>, and ignoring values that are not absolute (the
 * specification requires absolute paths, and a relative one would otherwise scatter files into the
 * working directory).
 *
 * <p>Nothing in the project should build these paths by hand: a single place that knows the layout
 * is what makes "uninstall" and "undo" trustworthy.
 *
 * @param configHome {@code $XDG_CONFIG_HOME}, default {@code ~/.config}
 * @param dataHome   {@code $XDG_DATA_HOME}, default {@code ~/.local/share}
 * @param stateHome  {@code $XDG_STATE_HOME}, default {@code ~/.local/state}
 * @param cacheHome  {@code $XDG_CACHE_HOME}, default {@code ~/.cache}
 */
public record XdgDirectories(Path configHome, Path dataHome, Path stateHome, Path cacheHome) {

    public XdgDirectories {
        Objects.requireNonNull(configHome, "configHome");
        Objects.requireNonNull(dataHome, "dataHome");
        Objects.requireNonNull(stateHome, "stateHome");
        Objects.requireNonNull(cacheHome, "cacheHome");
    }

    /** Resolves the layout from the current process environment and {@code user.home}. */
    public static XdgDirectories current() {
        return from(System.getenv(), Path.of(System.getProperty("user.home", ".")));
    }

    /** Resolves the layout from an explicit environment; the seam used by tests. */
    public static XdgDirectories from(Map<String, String> env, Path userHome) {
        return new XdgDirectories(
                resolve(env, "XDG_CONFIG_HOME", userHome.resolve(".config")),
                resolve(env, "XDG_DATA_HOME", userHome.resolve(".local/share")),
                resolve(env, "XDG_STATE_HOME", userHome.resolve(".local/state")),
                resolve(env, "XDG_CACHE_HOME", userHome.resolve(".cache")));
    }

    private static Path resolve(Map<String, String> env, String variable, Path fallback) {
        String value = env.get(variable);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        Path candidate = Path.of(value);
        return candidate.isAbsolute() ? candidate.normalize() : fallback;
    }

    // --- UserSpace layout -------------------------------------------------

    /** Root of our own directory under each base directory. */
    public Path configDir() {
        return configHome.resolve("userspace");
    }

    public Path dataDir() {
        return dataHome.resolve("userspace");
    }

    public Path stateDir() {
        return stateHome.resolve("userspace");
    }

    public Path cacheDir() {
        return cacheHome.resolve("userspace");
    }

    /** One directory per registered application. */
    public Path appsDir() {
        return dataDir().resolve("apps");
    }

    /** Icon copies referenced by absolute path from the desktop entries. */
    public Path iconsDir() {
        return dataDir().resolve("icons");
    }

    /** Generated wrapper scripts. */
    public Path binDir() {
        return dataDir().resolve("bin");
    }

    /** User-level {@code .desktop} entries. Not a theme directory: launchers must live here. */
    public Path desktopEntriesDir() {
        return dataHome.resolve("applications");
    }

    /** Backups that make integration undoable. */
    public Path journalDir() {
        return stateDir().resolve("journal");
    }

    public Path logDir() {
        return stateDir().resolve("logs");
    }

    /** Single-instance lock, to stop two copies from racing on the registry. */
    public Path lockFile() {
        return stateDir().resolve("lock");
    }

    /** Where extracted AppImage icons are cached; safe to delete at any time. */
    public Path cacheIconsDir() {
        return cacheDir().resolve("icons");
    }
}
