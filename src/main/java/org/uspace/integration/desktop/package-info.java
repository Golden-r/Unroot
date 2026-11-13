/**
 * Desktop Entry generation for {@code $XDG_DATA_HOME/applications/userspace-<id>.desktop}, plus icon
 * deployment to {@code $XDG_DATA_HOME/userspace/icons/}.
 *
 * <p>Escaping rules for {@code Exec} (double quotes, escaped {@code "} {@code `} {@code $} {@code \},
 * field codes outside quotes) are implemented once, in {@code util}, and tested against
 * {@code desktop-file-validate}. The generated command line is kept deliberately trivial — one
 * absolute path to our own wrapper plus a field code — because a wrapper script is a far safer place
 * for arguments, environment variables and a working directory than a single escaped line.
 *
 * <p>{@code TryExec} points at the wrapper so that a deleted application disappears from the menu
 * instead of producing a launcher that fails silently. {@code update-desktop-database} is optional
 * and only useful for {@code MimeType} handlers; it is never a prerequisite.
 */
package org.uspace.integration.desktop;
