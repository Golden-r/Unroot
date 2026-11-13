package org.uspace.config;

/**
 * A persistence problem that the caller is expected to handle: unreadable file, unwritable
 * directory, or metadata this version cannot interpret.
 *
 * <p>Checked on purpose. Losing an application registration is a user-visible event that must be
 * reported, not logged and swallowed, and a registry load must be able to continue with the
 * remaining applications.
 */
public class ConfigException extends Exception {

    private static final long serialVersionUID = 1L;

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
