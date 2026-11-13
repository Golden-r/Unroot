package org.uspace.core.model;

/**
 * How an application is packaged and therefore how it is launched, validated and removed.
 *
 * <p>Capability flags are preferred over a class hierarchy: the five types differ in a few
 * decisions (does it own an install root, is it self-contained, may it carry its own
 * environment), not in behaviour. Two {@code switch} expressions are cheaper to read and to
 * keep correct than five subclasses.
 */
public enum AppType {

    /** A single self-contained executable image (SquashFS). Needs no install root. */
    APPIMAGE,

    /** An extracted or portable tree with a launcher inside it (AppDir, tarball, .zip). */
    PORTABLE,

    /** A bare executable file living somewhere the user chose. */
    BINARY,

    /** A {@code .jar} run through the user's JVM. */
    JAVA_APP,

    /** Anything the user configures by hand: interpreter, script, Wine prefix, ... */
    CUSTOM;

    /** True when the application directory belongs to the app and may be removed with it. */
    public boolean ownsInstallRoot() {
        return this == PORTABLE || this == JAVA_APP;
    }

    /** True when the payload must be executable by the user before it can run. */
    public boolean requiresExecutableBit() {
        return this != JAVA_APP;
    }
}
