package org.uspace.config;

/**
 * Metadata that was read successfully as a property file but does not describe a valid
 * application: a required key is missing, a value has the wrong shape, or the file was written by a
 * newer schema.
 *
 * <p>Separate from {@link ConfigException} so callers can tell "I could not read the bytes" from
 * "I read them and they make no sense", and so a future repair action can target the second case.
 */
public class MalformedMetadataException extends ConfigException {

    private static final long serialVersionUID = 1L;

    private final String key;

    public MalformedMetadataException(String key, String message) {
        super(key == null ? message : message + " (key '" + key + "')");
        this.key = key;
    }

    /** The property at fault, or {@code null} when the problem is not attributable to one key. */
    public String key() {
        return key;
    }
}
