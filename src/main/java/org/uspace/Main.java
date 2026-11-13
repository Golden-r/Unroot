package org.uspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.uspace.config.ApplicationStore;
import org.uspace.config.ConfigException;
import org.uspace.config.MetadataIssue;
import org.uspace.config.Settings;
import org.uspace.config.SettingsLoad;
import org.uspace.config.SettingsStore;
import org.uspace.core.model.Application;
import org.uspace.core.registry.ApplicationRegistry;
import org.uspace.system.FileMutex;
import org.uspace.system.XdgDirectories;

/**
 * Composition root and entry point.
 *
 * <p>Milestones M0–M2: verify privileges, prepare the layout, load settings, take the single-instance
 * lock and load the registry, then report what was found. The Swing window, discovery and the
 * integration pipeline are wired here in later milestones — this class is the single place allowed to
 * know about every package.
 *
 * <p>Exit codes: {@code 0} success, {@code 1} layout or IO failure, {@code 2} run as root, {@code 3}
 * another instance already holds the lock.
 */
public final class Main {

    private static final int EXIT_IO = 1;
    private static final int EXIT_ROOT = 2;
    private static final int EXIT_ALREADY_RUNNING = 3;

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
        System.out.println("See ARCHITECTURE.md for the package plan, dependency map and roadmap.");
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
