package org.uspace.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeSet;

/**
 * Reads and writes {@code $XDG_CONFIG_HOME/userspace/settings.properties}.
 *
 * <p>Same format rules as application metadata — UTF-8, atomic replacement, {@code 0600},
 * {@code schema.version}, unknown keys preserved — with one deliberate difference:
 * <b>{@link #load()} never fails.</b> Preferences that cannot be read produce defaults plus issues;
 * refusing to start over a bad preference line would be a worse outcome than starting with the safe
 * defaults. Writes still fail loudly, because silently not saving a change the user made is worse.
 *
 * <p>The count key is {@code scan.roots.count} while entries are {@code scan.root.<i>}: distinct
 * prefixes, after an earlier layout where a count key matched its own entry prefix and made an empty
 * list look like orphaned data.
 */
public final class SettingsStore {

    public static final String FILE_NAME = "settings.properties";
    public static final String COMMENT = "UserSpace settings";
    public static final int SCHEMA_VERSION = 1;

    static final String KEY_SCHEMA = "schema.version";
    static final String KEY_SCAN_COUNT = "scan.roots.count";
    static final String KEY_SCAN_ROOT = "scan.root.";
    static final String KEY_CONFIRM = "confirm.beforeWriting";
    static final String KEY_SHELL = "shell";

    private static final int MAX_SCAN_ROOTS = 100;

    private final Path file;

    public SettingsStore(Path configDir) {
        this.file = Objects.requireNonNull(configDir, "configDir").toAbsolutePath().normalize()
                .resolve(FILE_NAME);
    }

    public Path file() {
        return file;
    }

    /** Reads the settings, falling back to defaults for anything missing or unusable. */
    public SettingsLoad load() {
        Properties properties;
        try {
            properties = PropertiesFile.readIfExists(file).orElse(null);
        } catch (ConfigException e) {
            return new SettingsLoad(Settings.defaults(), List.of(new MetadataIssue(file, e.getMessage())));
        }
        if (properties == null) {
            return SettingsLoad.defaults();
        }

        List<MetadataIssue> issues = new ArrayList<>();
        if (!schemaSupported(properties, issues)) {
            return new SettingsLoad(Settings.defaults(), issues);
        }

        List<Path> roots = readScanRoots(properties, issues);
        boolean confirm = readBoolean(properties, KEY_CONFIRM, true, issues);
        String shell = readShell(properties, issues);
        return new SettingsLoad(new Settings(roots, confirm, shell), issues);
    }

    /**
     * Writes the settings, preserving properties this version does not understand and skipping the
     * write entirely when nothing changed.
     */
    public void save(Settings settings) throws ConfigException {
        Objects.requireNonNull(settings, "settings");
        Properties existing = PropertiesFile.readIfExists(file).orElseGet(Properties::new);
        Properties previous = new Properties();
        previous.putAll(existing);

        Properties encoded = encode(settings, existing);
        if (previous.equals(encoded)) {
            return;
        }
        PropertiesFile.write(file, encoded, COMMENT);
    }

    static Properties encode(Settings settings, Properties target) {
        for (String key : List.of(KEY_SCHEMA, KEY_SCAN_COUNT, KEY_CONFIRM, KEY_SHELL)) {
            target.remove(key);
        }
        for (String key : new TreeSet<>(target.stringPropertyNames())) {
            if (key.startsWith(KEY_SCAN_ROOT)) {
                target.remove(key);
            }
        }

        target.setProperty(KEY_SCHEMA, Integer.toString(SCHEMA_VERSION));
        target.setProperty(KEY_CONFIRM, Boolean.toString(settings.confirmBeforeWriting()));
        target.setProperty(KEY_SCAN_COUNT, Integer.toString(settings.scanRoots().size()));
        for (int i = 0; i < settings.scanRoots().size(); i++) {
            target.setProperty(KEY_SCAN_ROOT + i, settings.scanRoots().get(i).toString());
        }
        if (settings.preferredShell().isEmpty()) {
            target.remove(KEY_SHELL);
        } else {
            target.setProperty(KEY_SHELL, settings.preferredShell());
        }
        return target;
    }

    private boolean schemaSupported(Properties properties, List<MetadataIssue> issues) {
        String raw = properties.getProperty(KEY_SCHEMA);
        if (raw == null) {
            return true;
        }
        try {
            int version = Integer.parseInt(raw.trim());
            if (version > SCHEMA_VERSION) {
                issues.add(new MetadataIssue(file, "written by a newer UserSpace (schema " + version
                        + " > " + SCHEMA_VERSION + "); using defaults and leaving the file untouched"));
                return false;
            }
            return true;
        } catch (NumberFormatException e) {
            issues.add(new MetadataIssue(file, "invalid schema.version '" + raw + "'; using defaults"));
            return false;
        }
    }

    private List<Path> readScanRoots(Properties properties, List<MetadataIssue> issues) {
        int count;
        try {
            count = Integer.parseInt(properties.getProperty(KEY_SCAN_COUNT, "0").trim());
        } catch (NumberFormatException e) {
            issues.add(new MetadataIssue(file, "invalid " + KEY_SCAN_COUNT + "; no scan roots will be used"));
            return List.of();
        }
        if (count < 0 || count > MAX_SCAN_ROOTS) {
            issues.add(new MetadataIssue(file, "out of range " + KEY_SCAN_COUNT + "=" + count));
            return List.of();
        }

        List<Path> roots = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String key = KEY_SCAN_ROOT + i;
            String raw = properties.getProperty(key);
            if (raw == null) {
                issues.add(new MetadataIssue(file, "missing " + key));
                continue;
            }
            Path root;
            try {
                root = Path.of(raw);
            } catch (RuntimeException e) {
                issues.add(new MetadataIssue(file, "invalid path in " + key + ": '" + raw + "'"));
                continue;
            }
            if (!root.isAbsolute()) {
                issues.add(new MetadataIssue(file, "ignoring relative scan root in " + key + ": '" + raw + "'"));
                continue;
            }
            Path normalized = root.normalize();
            if (!roots.contains(normalized)) {
                roots.add(normalized);
            }
        }
        return roots;
    }

    private boolean readBoolean(Properties properties, String key, boolean fallback, List<MetadataIssue> issues) {
        String raw = properties.getProperty(key);
        if (raw == null) {
            return fallback;
        }
        String value = raw.trim();
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        issues.add(new MetadataIssue(file, "invalid " + key + " '" + raw + "'; using " + fallback));
        return fallback;
    }

    private String readShell(Properties properties, List<MetadataIssue> issues) {
        String raw = properties.getProperty(KEY_SHELL);
        if (raw == null || raw.isBlank()) {
            return "";
        }
        Path shell;
        try {
            shell = Path.of(raw.trim());
        } catch (RuntimeException e) {
            issues.add(new MetadataIssue(file, "invalid " + KEY_SHELL + " '" + raw + "'"));
            return "";
        }
        if (!shell.isAbsolute()) {
            issues.add(new MetadataIssue(file, "ignoring relative " + KEY_SHELL + ": '" + raw + "'"));
            return "";
        }
        return shell.normalize().toString();
    }
}
