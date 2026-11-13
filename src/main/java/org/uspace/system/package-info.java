/**
 * The boundary to the Linux environment: XDG directory resolution, process execution, executable and
 * path probing, the cross-process {@link org.uspace.system.FileMutex}, and the privilege check.
 *
 * <p>Every external command goes through one {@code CommandRunner} that takes an argument list and
 * never a shell string. Nothing here knows what an "application" is, which keeps this package easy to
 * test with temporary directories and keeps process and lock handling auditable in one file.
 *
 * <p>Versioned state files are written by {@code config}; this package only supplies the primitives
 * (a lock file, atomic-friendly paths, permission-aware directory creation) and never decides what
 * to store.
 */
package org.uspace.system;
