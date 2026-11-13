/**
 * The in-memory index of registered applications plus the rules for adding, updating and removing
 * them.
 *
 * <p>Responsibilities: uniqueness (by id, by canonical executable path), lookup, and persisting
 * {@code core.model} instances through {@code config}. Removing an application here means removing
 * its metadata directory only; generated artifacts are handled by {@code core.service} +
 * {@code integration}.
 *
 * <p>Every mutation runs inside a {@code system.FileMutex}: persist first, then mutate memory, and
 * repeat the id check on disk inside the critical section so two processes cannot both claim the same
 * id. Reads take no lock — metadata files are installed by atomic rename.
 *
 * <p>Must not: render desktop entries, scan directories, or know about Swing.
 */
package org.uspace.core.registry;
