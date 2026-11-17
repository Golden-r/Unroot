package org.uspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.uspace.config.ApplicationStore;
import org.uspace.config.ConfigException;
import org.uspace.config.MetadataIssue;
import org.uspace.config.Settings;
import org.uspace.config.SettingsLoad;
import org.uspace.config.SettingsStore;
import org.uspace.core.model.Application;
import org.uspace.core.registry.ApplicationRegistry;
import org.uspace.discovery.DiscoveryCandidate;
import org.uspace.discovery.DiscoveryIssue;
import org.uspace.discovery.DiscoveryResult;
import org.uspace.discovery.DiscoveryScanner;
import org.uspace.system.FileMutex;
import org.uspace.system.XdgDirectories;

/**
 * Composition root and entry point.
 *
 * <p>Milestones M0–M3: verify privileges, prepare the layout, load settings, take the single-instance
 * lock, load the registry, and — only when asked with {@code --scan} — walk the configured roots and
 * print what discovery proposes. The Swing window and the integration pipeline are wired here in
 * later milestones; this class is the single place allowed to know about every package.
 *
 * <p>Discovery is opt-in rather than part of startup: a scan of a real home directory can take
 * seconds, and a program that searches the disk every time it is launched is a program people stop
 * launching.
 *
 * <p>Exit codes: {@code 0} success, {@code 1} layout or IO failure, {@code 2} run as root, {@code 3}
 * another instance already holds the lock, {@code 64} unknown command-line option.
 */
public final class Main {

    private static final int EXIT_IO = 1;
    private static final int EXIT_ROOT = 2;
    private static final int EXIT_ALREADY_RUNNING = 3;
    private static final int EXIT_USAGE = 64;

    private Main() {
    }

    public static void main(String[] args) {
        if (runningAsRoot()) {
            System.err.println("""
                    UserSpace must run as your normal user, not as root.
                    It manages only files inside your home directory; running it as root would create \
                    root-owned files that you could no longer manage as yourself.""");
            System.exit(EXIT_ROOT);
        }

        boolean scanRequested = false;
        for (String argument : args) {
            switch (argument) {
                case "--scan" -> scanRequested = true;
                case "--help", "-h" -> {
                    printUsage();
                    return;
                }
                default -> {
                    System.err.println("Unknown option: " + argument);
                    printUsage();
                    System.exit(EXIT_USAGE);
                    return;
                }
            }
        }

        XdgDirectories dirs = XdgDirectories.current();
        List<Path> required = List.of(
                dirs.configDir(),
                dirs.appsDir(),
                dirs.iconsDir(),
                dirs.binDir(),
                dirs.desktopEntriesDir(),
                dirs.journalDir(),
                dirs.logDir(),
                dirs.cacheDir());
        try {
            for (Path dir : required) {
                createPrivateDirectories(dir);
            }
        } catch (IOException e) {
            System.err.println("Cannot prepare the UserSpace layout under " + dirs.dataDir() + ": " + e.getMessage());
            System.exit(EXIT_IO);
            return;
        }

        // The lock comes before anything that could write, and is held for the life of the process.
        FileMutex mutex = FileMutex.forFile(dirs.lockFile());
        try {
            if (!mutex.tryLock()) {
                String owner = FileMutex.ownerDescription(dirs.lockFile()).orElse("unknown process");
                System.err.println("Another UserSpace instance is already running (" + owner + ").");
                System.err.println("Exiting instead of letting two instances write the same files.");
                System.exit(EXIT_ALREADY_RUNNING);
                return;
            }
        } catch (IOException e) {
            System.err.println("Cannot create the UserSpace lock " + dirs.lockFile() + ": " + e.getMessage());
            System.exit(EXIT_IO);
            return;
        }

        SettingsLoad settingsLoad = new SettingsStore(dirs.configDir()).load();
        Settings settings = settingsLoad.settings();

        ApplicationRegistry registry;
        try {
            registry = new ApplicationRegistry(new ApplicationStore(dirs.appsDir()), mutex);
        } catch (ConfigException e) {
            System.err.println("Cannot read the registry: " + e.getMessage());
            System.exit(EXIT_IO);
            return;
        }

        System.out.println("UserSpace — layout, settings and registry loaded (no UI yet)");
        System.out.printf("  settings    %s%n", new SettingsStore(dirs.configDir()).file());
        System.out.printf("  registry    %s/ <%s>/meta.properties%n", dirs.appsDir(), "<id>");
        System.out.printf("  icons       %s%n", dirs.iconsDir());
        System.out.printf("  wrappers    %s%n", dirs.binDir());
        System.out.printf("  launchers   %s%n", dirs.desktopEntriesDir());
        System.out.printf("  journal     %s%n", dirs.journalDir());
        System.out.printf("  logs        %s%n", dirs.logDir());
        System.out.printf("  cache       %s%n", dirs.cacheDir());
        System.out.printf("  lock        %s (held by this process)%n", dirs.lockFile());

        System.out.println("Settings");
        System.out.printf("  scan roots  %s%n",
                settings.scanRoots().isEmpty() ? "(none configured)" : settings.scanRoots().toString());
        System.out.printf("  confirm     %s before writing files outside UserSpace%n",
                settings.confirmBeforeWriting() ? "ask" : "do not ask");
        System.out.printf("  shell       %s%n",
                settings.preferredShell().isEmpty() ? "(not chosen yet)" : settings.preferredShell());
        reportIssues(settingsLoad.issues());

        System.out.printf("Registered applications: %d%n", registry.size());
        for (Application application : registry.all()) {
            System.out.printf("  %-20s %-9s %s%n", application.id(), application.type(), application.launch().executable());
        }
        if (!registry.loadIssues().isEmpty()) {
            System.out.println("Unreadable registrations (left untouched):");
            reportIssues(registry.loadIssues());
        }

        if (scanRequested) {
            runDiscovery(dirs, settings, registry);
        }
        System.out.println("See ARCHITECTURE.md for the package plan, dependency map and roadmap.");
    }

    /**
     * Runs M3 discovery and prints what it proposes. Nothing is registered: the output is a review
     * list, and the registry is only read to avoid proposing what is already there.
     */
    private static void runDiscovery(XdgDirectories dirs, Settings settings, ApplicationRegistry registry) {
        System.out.println();
        if (settings.scanRoots().isEmpty()) {
            System.out.println("Discovery — no scan roots configured.");
            System.out.printf("  add directories to %s (scan.root.0=…) and run --scan again.%n",
                    new SettingsStore(dirs.configDir()).file());
            return;
        }

        // The JVM running this process is the interpreter recorded for discovered jars. Inside the
        // packaged app-image that is the bundled runtime, which is exactly what we want to ship.
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java");
        Set<Path> known = registry.all().stream()
                .map(application -> application.launch().executable())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        long started = System.nanoTime();
        DiscoveryResult result = new DiscoveryScanner(dirs, javaExecutable)
                .scan(settings.scanRoots(), known, () -> false);
        long millis = (System.nanoTime() - started) / 1_000_000;

        System.out.printf("Discovery — %d root(s), %d file(s) inspected in %d director(ies), %d ms%n",
                settings.scanRoots().size(), result.filesInspected(), result.directoriesVisited(), millis);
        if (result.truncated()) {
            System.out.printf("  ! candidate list stopped at %d; scan a narrower root%n", result.candidateCount());
        }
        for (DiscoveryCandidate candidate : result.candidates()) {
            System.out.printf("  %-24s %-9s %-6s %s%n",
                    candidate.id(), candidate.application().type(), candidate.confidence().label(),
                    candidate.executable());
            System.out.printf("      why: %s%n", candidate.reason());
        }
        if (result.candidates().isEmpty() && result.issues().isEmpty()) {
            System.out.println("  nothing found in those roots");
        }
        if (!result.issues().isEmpty()) {
            System.out.printf("  %d path(s) could not be used:%n", result.issues().size());
            for (DiscoveryIssue issue : result.issues()) {
                System.out.printf("  ! %s%n", issue);
            }
        }
        System.out.println("Nothing was registered: these are proposals to review.");
    }

    private static void printUsage() {
        System.out.println("""
                usage: userspace [--scan] [--help]

                  (no option)  report the layout, settings and registered applications
                  --scan       scan the configured roots and list proposed applications
                  --help       this text

                A scan never registers anything; candidates are proposals for review.""");
    }

    private static void reportIssues(List<MetadataIssue> issues) {
        for (MetadataIssue issue : issues) {
            System.out.printf("  ! %s%n", issue);
        }
    }

    /**
     * Creates {@code dir} and its parents with {@code 0700}, which is what stops other local users
     * from reading wrapper scripts and metadata. Existing directories keep their permissions: this
     * tool does not tighten directories the user created.
     */
    private static void createPrivateDirectories(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            return;
        }
        Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE)));
    }

    /**
     * {@code id -u} rather than the {@code user.name} property, which a user can override with
     * {@code -Duser.name=}. Falls back to {@code user.name} only if the command cannot be started.
     */
    private static boolean runningAsRoot() {
        try {
            Process process = new ProcessBuilder("id", "-u")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(2, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return true;
            }
            String output = new String(process.getInputStream().readAllBytes()).trim();
            return "0".equals(output);
        } catch (IOException e) {
            return "root".equals(System.getProperty("user.name"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }
}
