package org.uspace.core.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One environment variable set for an application, applied by its launcher rather than by
 * modifying shell configuration files.
 */
public record EnvVariable(String name, String value) {

    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public EnvVariable {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        if (!VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid environment variable name: '" + name + "'");
        }
        if (value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("environment value may not contain NUL");
        }
    }

    /** Serialized form used by wrappers and by {@code meta.properties}. */
    public String assignment() {
        return name + "=" + value;
    }
}
