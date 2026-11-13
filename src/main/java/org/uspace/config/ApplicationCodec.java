package org.uspace.config;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import org.uspace.core.model.AppType;
import org.uspace.core.model.Application;
import org.uspace.core.model.ApplicationId;
import org.uspace.core.model.EnvVariable;
import org.uspace.core.model.LaunchCommand;

/**
 * Translates {@link Application} to and from {@link Properties}.
 *
 * <p>Encoding into a <em>caller-supplied</em> {@code Properties} instance rather than a fresh one is
 * what preserves unknown keys: a metadata file written by a newer version keeps its extra keys when
 * an older version updates the name. Only keys this codec owns are removed from the target before
 * writing, and repeated collection keys ({@code launch.argument.}, {@code env.}) are cleared first so
 * that shortening a list cannot leave an orphan behind.
 *
 * <p>Decoding is strict about required scalars and lenient only where forward compatibility demands
 * it: an absent {@code schema.version} is treated as version 1, a version above
 * {@link #SCHEMA_VERSION} is refused rather than guessed at. Every failure is a
 * {@link MalformedMetadataException} naming the key, because "application 3 will not load" is not
 * actionable and "application 3: invalid 'type' value 'appy'" is.
 */
public final class ApplicationCodec {

    /** Bumped whenever the on-disk key layout changes incompatibly. */
    public static final int SCHEMA_VERSION = 1;

    /** Constant header: with a timestamp here, rewriting identical data would change the file. */
    public static final String COMMENT = "UserSpace application metadata";

    static final String KEY_SCHEMA = "schema.version";
    static final String KEY_ID = "id";
    static final String KEY_NAME = "name";
    static final String KEY_DESCRIPTION = "description";
    static final String KEY_TYPE = "type";
    static final String KEY_EXECUTABLE = "launch.executable";
    static final String KEY_ARGUMENT_COUNT = "launch.argument.count";
    /**
     * Plural on purpose: the entry prefix must not also match {@link #KEY_ARGUMENT_COUNT}, or an
     * empty argument list would look like orphaned data. The same applies to {@link #KEY_ENV} versus
     * {@link #KEY_ENV_COUNT}.
     */
    static final String KEY_ARGUMENT = "launch.arguments.";
    static final String KEY_WORKING_DIRECTORY = "launch.workingDirectory";
    static final String KEY_INSTALL_ROOT = "install.root";
    static final String KEY_ICON = "icon";
    static final String KEY_REGISTERED_AT = "registered.at";
    static final String KEY_ORIGIN = "origin";
    static final String KEY_ENV_COUNT = "environment.count";
    static final String KEY_ENV = "env.";

    private static final Set<String> FIXED_KEYS = Set.of(
            KEY_SCHEMA, KEY_ID, KEY_NAME, KEY_DESCRIPTION, KEY_TYPE, KEY_EXECUTABLE,
            KEY_ARGUMENT_COUNT, KEY_WORKING_DIRECTORY, KEY_INSTALL_ROOT, KEY_ICON,
            KEY_REGISTERED_AT, KEY_ORIGIN, KEY_ENV_COUNT);

    private static final int MAX_COLLECTION_SIZE = 1000;

    private ApplicationCodec() {
    }

    /**
     * Writes {@code application} into {@code target}, leaving properties this codec does not own
     * untouched.
     */
    public static Properties encode(Application application, Properties target) {
        for (String key : FIXED_KEYS) {
            target.remove(key);
        }
        removePrefixed(target, KEY_ARGUMENT);
        removePrefixed(target, KEY_ENV);

        target.setProperty(KEY_SCHEMA, Integer.toString(SCHEMA_VERSION));
        target.setProperty(KEY_ID, application.id().value());
        target.setProperty(KEY_NAME, application.name());
        target.setProperty(KEY_DESCRIPTION, application.description());
        target.setProperty(KEY_TYPE, application.type().name());
        target.setProperty(KEY_EXECUTABLE, application.launch().executable().toString());
        target.setProperty(KEY_REGISTERED_AT, application.registeredAt().toString());
        target.setProperty(KEY_ORIGIN, application.origin());
        setOrRemove(target, KEY_WORKING_DIRECTORY, application.launch().workingDirectory());
        setOrRemove(target, KEY_INSTALL_ROOT, application.installRoot());
        setOrRemove(target, KEY_ICON, application.icon());

        List<String> arguments = application.launch().arguments();
        target.setProperty(KEY_ARGUMENT_COUNT, Integer.toString(arguments.size()));
        for (int i = 0; i < arguments.size(); i++) {
            target.setProperty(KEY_ARGUMENT + i, arguments.get(i));
        }

        List<EnvVariable> environment = application.environment();
        target.setProperty(KEY_ENV_COUNT, Integer.toString(environment.size()));
        for (int i = 0; i < environment.size(); i++) {
            EnvVariable variable = environment.get(i);
            target.setProperty(KEY_ENV + i + ".name", variable.name());
            target.setProperty(KEY_ENV + i + ".value", variable.value());
        }
        return target;
    }

    /** Reconstructs an application, enforcing every {@link Application} invariant. */
    public static Application decode(Properties source) throws MalformedMetadataException {
        requireSupportedSchema(source);

        String idValue = require(source, KEY_ID);
        ApplicationId id;
        try {
            id = new ApplicationId(idValue);
        } catch (IllegalArgumentException e) {
            throw new MalformedMetadataException(KEY_ID, e.getMessage());
        }

        String name = require(source, KEY_NAME);
        AppType type = parseType(require(source, KEY_TYPE));
        Path executable = parsePath(source, KEY_EXECUTABLE);
        Path workingDirectory = optionalPath(source, KEY_WORKING_DIRECTORY);
        Path installRoot = optionalPath(source, KEY_INSTALL_ROOT);
        Path icon = optionalPath(source, KEY_ICON);
        Instant registeredAt = parseInstant(require(source, KEY_REGISTERED_AT));

        List<String> arguments = readArguments(source);
        List<EnvVariable> environment = readEnvironment(source);

        try {
            return new Application(id, name, source.getProperty(KEY_DESCRIPTION, ""), type,
                    new LaunchCommand(executable, arguments, workingDirectory), installRoot, icon,
                    environment, registeredAt, source.getProperty(KEY_ORIGIN, "manual"));
        } catch (IllegalArgumentException e) {
            // The model rejects the combination, e.g. a PORTABLE app with no install root.
            throw new MalformedMetadataException(null, "inconsistent metadata: " + e.getMessage());
        }
    }

    private static void requireSupportedSchema(Properties source) throws MalformedMetadataException {
        String raw = source.getProperty(KEY_SCHEMA);
        if (raw == null) {
            // Written before versioning existed, or by hand. Everything it can contain is version 1.
            return;
        }
        int version;
        try {
            version = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new MalformedMetadataException(KEY_SCHEMA, "not a number: '" + raw + "'");
        }
        if (version < 1) {
            throw new MalformedMetadataException(KEY_SCHEMA, "invalid version: " + version);
        }
        if (version > SCHEMA_VERSION) {
            throw new MalformedMetadataException(KEY_SCHEMA,
                    "written by a newer UserSpace (schema " + version + " > " + SCHEMA_VERSION + ")");
        }
    }

    private static List<String> readArguments(Properties source) throws MalformedMetadataException {
        int count = readCount(source, KEY_ARGUMENT_COUNT);
        if (count == 0 && hasPrefixedKey(source, KEY_ARGUMENT)) {
            throw new MalformedMetadataException(KEY_ARGUMENT_COUNT,
                    "arguments are present but the count is missing or zero");
        }
        List<String> arguments = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            arguments.add(require(source, KEY_ARGUMENT + i));
        }
        return arguments;
    }

    private static List<EnvVariable> readEnvironment(Properties source) throws MalformedMetadataException {
        int count = readCount(source, KEY_ENV_COUNT);
        if (count == 0 && hasPrefixedKey(source, KEY_ENV)) {
            throw new MalformedMetadataException(KEY_ENV_COUNT,
                    "environment entries are present but the count is missing or zero");
        }
        List<EnvVariable> environment = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String name = require(source, KEY_ENV + i + ".name");
            String value = require(source, KEY_ENV + i + ".value");
            try {
                environment.add(new EnvVariable(name, value));
            } catch (IllegalArgumentException e) {
                throw new MalformedMetadataException(KEY_ENV + i + ".name", e.getMessage());
            }
        }
        return environment;
    }

    private static int readCount(Properties source, String key) throws MalformedMetadataException {
        String raw = source.getProperty(key);
        if (raw == null) {
            return 0;
        }
        int count;
        try {
            count = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new MalformedMetadataException(key, "not a number: '" + raw + "'");
        }
        if (count < 0 || count > MAX_COLLECTION_SIZE) {
            throw new MalformedMetadataException(key, "out of range: " + count);
        }
        return count;
    }

    private static AppType parseType(String raw) throws MalformedMetadataException {
        try {
            return AppType.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new MalformedMetadataException(KEY_TYPE, "unknown application type: '" + raw + "'");
        }
    }

    private static Instant parseInstant(String raw) throws MalformedMetadataException {
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new MalformedMetadataException(KEY_REGISTERED_AT, "not an ISO-8601 instant: '" + raw + "'");
        }
    }

    private static Path parsePath(Properties source, String key) throws MalformedMetadataException {
        String raw = require(source, key);
        return toAbsolutePath(raw, key);
    }

    private static Path optionalPath(Properties source, String key) throws MalformedMetadataException {
        String raw = source.getProperty(key);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        return toAbsolutePath(raw, key);
    }

    private static Path toAbsolutePath(String raw, String key) throws MalformedMetadataException {
        Path path;
        try {
            path = Path.of(raw);
        } catch (InvalidPathException e) {
            throw new MalformedMetadataException(key, "not a valid path: '" + raw + "'");
        }
        if (!path.isAbsolute()) {
            throw new MalformedMetadataException(key, "not an absolute path: '" + raw + "'");
        }
        return path.normalize();
    }

    private static String require(Properties source, String key) throws MalformedMetadataException {
        String value = source.getProperty(key);
        if (value == null) {
            throw new MalformedMetadataException(key, "required value is missing");
        }
        return value;
    }

    private static void setOrRemove(Properties target, String key, Path value) {
        if (value == null) {
            target.remove(key);
        } else {
            target.setProperty(key, value.toString());
        }
    }

    private static void removePrefixed(Properties target, String prefix) {
        for (String key : new TreeSet<>(target.stringPropertyNames())) {
            if (key.startsWith(prefix)) {
                target.remove(key);
            }
        }
    }

    private static boolean hasPrefixedKey(Properties source, String prefix) {
        for (Map.Entry<Object, Object> entry : source.entrySet()) {
            if (entry.getKey() instanceof String key && key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
