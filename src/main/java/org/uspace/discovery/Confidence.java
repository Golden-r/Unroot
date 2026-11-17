package org.uspace.discovery;

/**
 * How much the scan actually verified about a candidate.
 *
 * <p>Three values, because the UI has to separate "this is certainly an application" from "this
 * looked like one". A candidate is never registered automatically regardless of confidence; the
 * value only decides how it should be presented, so a low-confidence entry is not hidden, it is
 * explained.
 */
public enum Confidence {

    /** Positive evidence: ELF header plus AppImage magic, or a jar manifest with a {@code Main-Class}. */
    HIGH,

    /** A strong convention or a broken variant of one, e.g. an {@code AppRun} that is not executable. */
    MEDIUM,

    /** Reconstructed from another launcher, where arguments and environment were not reproduced. */
    LOW;

    /** Lower-case name for logs and the CLI, so output does not read like an enum dump. */
    public String label() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
