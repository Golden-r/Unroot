/**
 * Command-line integration: generated wrapper scripts in
 * {@code $XDG_DATA_HOME/userspace/bin/<id>}, user-level {@code PATH} links, and environment
 * drop-ins.
 *
 * <p>Invariants:
 * <ul>
 *   <li>Manually maintained shell configuration is never edited as a side effect. Aliases and
 *       exports go to {@code $XDG_CONFIG_HOME/userspace/env.sh} with a printed {@code source}
 *       instruction.</li>
 *   <li>Only an explicit opt-in appends a delimited {@code # >>> userspace >>>} block to
 *       {@code ~/.profile}, after a journaled backup, and only that block is ever removed.</li>
 *   <li>Whether {@code ~/.local/bin} is on {@code PATH} is probed and reported, never assumed.</li>
 * </ul>
 *
 * <p>Wrappers exist so that quoting, working directory and environment are handled in {@code sh}
 * rather than in a desktop-entry string, and so that removing an application means deleting one
 * file per channel.
 */
package org.uspace.integration.shell;
