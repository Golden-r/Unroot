package org.uspace.discovery;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.uspace.core.model.Application;
import org.uspace.core.model.ApplicationId;
import org.uspace.core.model.LaunchCommand;
import org.uspace.system.XdgDirectories;
import org.uspace.util.Slug;

/**
 * Walks the configured scan roots and proposes applications, without registering anything.
 *
 * <p>Four limits make the walk safe to run on a real home directory:
 *
 * <ul>
 *   <li><b>Depth.</b> A root is not a licence to crawl the whole filesystem; the default is
 *       {@value #DEFAULT_MAX_DEPTH} levels below each root. Deeper files are simply not examined, and
 *       {@link DiscoveryResult#filesInspected()} is what tells a user the scan was shallow. The
 *       separate {@link DiscoveryResult#truncated()} flag means the candidate cap was reached, which
 *       is a different failure: the walk finished but the list was cut.</li>
 *   <li><b>Symlinks are never followed.</b> Following them is how a scan of {@code ~/Applications}
 *       ends up walking {@code /}, and a link back to a parent would loop forever. A directory link
 *       is reported so the user knows it was skipped.</li>
 *   <li><b>Our own directories are excluded.</b> Scanning {@code $XDG_DATA_HOME/userspace} would find
 *       the generated wrappers as executables and propose them as applications.</li>
 *   <li><b>Cancellation.</b> Checked once per file and directory, so a stop request takes effect
 *       within one entry even on a huge tree.</li>
 * </ul>
 *
 * <p>Duplicate detection against the registry happens here but the registry itself is not imported:
 * the caller passes the executables already registered, because {@code discovery} may depend on
 * {@code core.model} and {@code system} only. A skipped duplicate is reported — "already registered"
 * is information, and silently returning one candidate fewer looks like a scan bug.
 */
public final class DiscoveryScanner {

    /** Deep enough for {@code ~/Applications/Name/Name-1.2/bin/AppRun}, shallow enough to stay fast. */
    public static final int DEFAULT_MAX_DEPTH = 6;

    /** Stops a runaway scan from building an unbounded list in memory. */
    private static final int MAX_CANDIDATES = 200;

    /** Matches {@code ApplicationId}'s grammar so every generated id is legal. */
    private static final int MAX_ID_LENGTH = 64;

    /** Directories that never contain applications but hold thousands of files. */
    private static final Set<String> SKIPPED_DIRECTORY_NAMES = Set.of(".git", "node_modules", "__pycache__");

    private final FileClassifier classifier;
    private final int maxDepth;
    private final List<Path> excludedTrees;

    /** Uses the JVM running the scan as the interpreter for discovered jars. */
    public DiscoveryScanner(XdgDirectories dirs, Path javaExecutable) {
        this(dirs, javaExecutable, DEFAULT_MAX_DEPTH);
    }

    public DiscoveryScanner(XdgDirectories dirs, Path javaExecutable, int maxDepth) {
        Objects.requireNonNull(dirs, "dirs");
        if (maxDepth < 0) {
            throw new IllegalArgumentException("max depth must not be negative: " + maxDepth);
        }
        this.maxDepth = maxDepth;
        this.classifier = new FileClassifier(javaExecutable);
        this.excludedTrees = List.of(
                dirs.configDir().toAbsolutePath().normalize(),
                dirs.dataDir().toAbsolutePath().normalize(),
                dirs.stateDir().toAbsolutePath().normalize(),
                dirs.cacheDir().toAbsolutePath().normalize());
    }

    /** Scans without duplicate knowledge and without a way to stop: the simple call for a CLI. */
    public DiscoveryResult scan(List<Path> roots) {
        return scan(roots, Set.of(), () -> false);
    }

    /**
     * Scans {@code roots}, skipping executables the caller already has.
     *
     * @param knownExecutables executables already registered; compared by real path
     * @param cancelled        polled once per file and directory
     */
    public DiscoveryResult scan(List<Path> roots, Set<Path> knownExecutables, BooleanSupplier cancelled) {
        Objects.requireNonNull(roots, "roots");
        Objects.requireNonNull(knownExecutables, "knownExecutables");
        Objects.requireNonNull(cancelled, "cancelled");

        State state = new State(identities(knownExecutables), cancelled);
        for (Path root : roots) {
            if (state.cancelled) {
                break;
            }
            scanRoot(root.toAbsolutePath().normalize(), state);
        }
        return state.result();
    }

    private void scanRoot(Path root, State state) {
        if (isExcluded(root)) {
            state.issues.add(new DiscoveryIssue(root, "inside UserSpace's own directories; not scanned"));
            return;
        }
        if (!Files.isDirectory(root)) {
            state.issues.add(new DiscoveryIssue(root,
                    Files.exists(root) ? "scan root is not a directory" : "scan root does not exist"));
            return;
        }
        try {
            Files.walkFileTree(root, Set.of(), maxDepth, new Visitor(root, state));
        } catch (IOException e) {
            state.issues.add(new DiscoveryIssue(root, "the walk stopped early: " + describe(e)));
        }
    }

    /** The walk itself; one instance per root so the depth check stays relative to that root. */
    private final class Visitor extends SimpleFileVisitor<Path> {

        private final Path root;
        private final State state;

        Visitor(Path root, State state) {
            this.root = root;
            this.state = state;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            if (state.checkCancelled()) {
                return FileVisitResult.TERMINATE;
            }
            state.directories++;
            if (!dir.equals(root)) {
                if (isExcluded(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Path name = dir.getFileName();
                if (name != null && SKIPPED_DIRECTORY_NAMES.contains(name.toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
            }
            // An extracted tree is one application, not a directory of them. A scan root can itself be
            // one, which is why this is not inside the exclusion branch above.
            Optional<Proposal> tree = classifier.classifyTree(dir, state.issues::add);
            if (tree.isPresent()) {
                state.add(tree.get(), root);
                return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (state.checkCancelled()) {
                return FileVisitResult.TERMINATE;
            }
            state.files++;
            if (attrs.isSymbolicLink()) {
                if (Files.isDirectory(file)) {
                    state.issues.add(new DiscoveryIssue(file, "symlink to a directory; not followed"));
                }
                return FileVisitResult.CONTINUE;
            }
            if (!attrs.isRegularFile()) {
                return FileVisitResult.CONTINUE;
            }
            try {
                classifier.classifyFile(file, state.issues::add).ifPresent(proposal -> state.add(proposal, root));
            } catch (RuntimeException e) {
                // One pathological file must not abort a scan that is already 3000 files in.
                state.issues.add(new DiscoveryIssue(file, "could not be classified: " + e));
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException e) {
            state.issues.add(new DiscoveryIssue(file, "cannot be read: " + describe(e)));
            return FileVisitResult.CONTINUE;
        }
    }

    /** Mutable accumulator for one scan; keeps the visitor honest about what it saw. */
    private final class State {

        private final Set<Path> known;
        private final BooleanSupplier cancelledSignal;
        private final List<DiscoveryCandidate> candidates = new ArrayList<>();
        private final List<DiscoveryIssue> issues = new ArrayList<>();
        private final Set<Path> seen = new HashSet<>();
        private final Set<String> usedIds = new HashSet<>();

        private int files;
        private int directories;
        private boolean cancelled;
        private boolean truncated;
        State(Set<Path> known, BooleanSupplier cancelledSignal) {
            this.known = known;
            this.cancelledSignal = cancelledSignal;
        }

        boolean checkCancelled() {
            if (!cancelled && cancelledSignal.getAsBoolean()) {
                cancelled = true;
            }
            return cancelled;
        }

        void add(Proposal proposal, Path root) {
            // The tree could have been deleted between classification and here.
            if (proposal.type().ownsInstallRoot() && !Files.isDirectory(proposal.installRoot())) {
                issues.add(new DiscoveryIssue(proposal.installRoot(),
                        "the application directory disappeared during the scan"));
                return;
            }
            Path identity = identity(proposal.executable());
            if (known.contains(identity)) {
                issues.add(new DiscoveryIssue(proposal.executable(), "already registered; not proposed again"));
                return;
            }
            if (!seen.add(identity)) {
                return;
            }
            if (candidates.size() >= MAX_CANDIDATES) {
                truncated = true;
                return;
            }
            String id = uniqueId(Slug.from(proposal.displayName()), usedIds);
            Application application = new Application(
                    new ApplicationId(id),
                    proposal.displayName(),
                    "",
                    proposal.type(),
                    new LaunchCommand(proposal.executable(), proposal.arguments(), proposal.workingDirectory()),
                    proposal.installRoot(),
                    proposal.icon(),
                    List.of(),
                    Instant.now(),
                    "discovered:" + root);
            candidates.add(new DiscoveryCandidate(application, proposal.confidence(), proposal.reason()));
        }

        DiscoveryResult result() {
            List<DiscoveryCandidate> sorted = candidates.stream()
                    .sorted(Comparator.comparing((DiscoveryCandidate candidate) -> candidate.application().name(),
                                    String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(candidate -> candidate.id().value()))
                    .toList();
            return new DiscoveryResult(sorted, issues, files, directories, cancelled, truncated);
        }
    }

    /**
     * Adds {@code base}, or the first free {@code base-n}, to {@code used}.
     *
     * <p>Truncation keeps room for the suffix: two applications whose names differ only after the
     * 64th character must still get different ids.
     */
    private static String uniqueId(String base, Set<String> used) {
        String candidate = base;
        int suffix = 2;
        while (!used.add(candidate)) {
            String tail = "-" + suffix++;
            int room = Math.max(1, MAX_ID_LENGTH - tail.length());
            candidate = (base.length() > room ? base.substring(0, room) : base) + tail;
        }
        return candidate;
    }

    private boolean isExcluded(Path path) {
        for (Path excluded : excludedTrees) {
            if (path.startsWith(excluded)) {
                return true;
            }
        }
        return false;
    }

    private static Set<Path> identities(Set<Path> executables) {
        Set<Path> identities = new HashSet<>(executables.size() * 2);
        for (Path executable : executables) {
            identities.add(identity(executable));
        }
        return identities;
    }

    /**
     * Canonical form for duplicate detection: the real path when the file exists, the normalized
     * absolute path otherwise.
     *
     * <p>Same rule as {@code ApplicationRegistry} uses today. M2b replaces both with
     * {@code (device, inode)} so hardlinks are caught; until then the two must agree, or a file could
     * be a duplicate for the registry and novel for the scan.
     */
    private static Path identity(Path executable) {
        try {
            return executable.toRealPath();
        } catch (IOException e) {
            return executable.toAbsolutePath().normalize();
        }
    }

    private static String describe(IOException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
