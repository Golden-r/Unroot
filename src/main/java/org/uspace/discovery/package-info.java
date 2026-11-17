/**
 * Filesystem scanning and classification: walk the user's scan roots, recognise AppImages,
 * extracted trees, ELF binaries and runnable jars, and propose {@code Application} candidates.
 *
 * <p>Discovery never registers anything by itself. It emits candidates with a confidence and a
 * reason so the UI can present a reviewable list, and duplicate detection stays in
 * {@code core.registry}, where the authoritative view of what is already registered exists. The
 * scanner therefore takes the already-registered executables as an argument instead of importing the
 * registry: the dependency direction is {@code discovery → core.model, system, util}, and the
 * reverse edge would let the walker mutate what it is supposed to describe.
 *
 * <p>Public surface is intentionally four types — {@link org.uspace.discovery.DiscoveryScanner},
 * {@link org.uspace.discovery.DiscoveryResult}, {@link org.uspace.discovery.DiscoveryCandidate} and
 * {@link org.uspace.discovery.DiscoveryIssue} — plus
 * {@link org.uspace.discovery.Confidence}. The per-file rules and the half-built proposal belong to
 * the package.
 *
 * <p>Two limits are policy, not implementation detail: symlinks are never followed (following them
 * is how a scan escapes its root, and a link to a parent loops forever), and the walk is bounded in
 * depth. Both are reported when they cut a scan short rather than silently narrowing the result.
 */
package org.uspace.discovery;
