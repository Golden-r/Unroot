/**
 * Filesystem scanning and classification: walk the user's scan roots, recognise AppImages,
 * extracted trees, ELF binaries and runnable jars, and propose {@code Application} candidates.
 *
 * <p>Discovery never registers anything by itself. It emits candidates with a confidence and a
 * reason so the UI can present a reviewable list, and duplicate detection stays in
 * {@code core.registry}, where the authoritative view of what is already registered exists.
 */
package org.uspace.discovery;
