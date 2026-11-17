package org.uspace.test;

import static org.uspace.test.Assertions.assertEquals;
import static org.uspace.test.Assertions.assertFalse;
import static org.uspace.test.Assertions.assertNull;
import static org.uspace.test.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.uspace.core.model.AppType;
import org.uspace.core.model.ApplicationId;
import org.uspace.discovery.Confidence;
import org.uspace.discovery.DiscoveryCandidate;
import org.uspace.discovery.DiscoveryResult;
import org.uspace.discovery.DiscoveryScanner;
import org.uspace.system.XdgDirectories;
import org.uspace.util.Slug;

/**
 * Discovery: what is recognised, what is refused, and what the walk refuses to do.
 *
 * <p>Every case builds its own tree in a temporary directory, so a rule about symlinks, depth or
 * permissions is exercised for real rather than described. The sandbox XDG layout lives inside the
 * same temporary directory, which is also how the "our own directories are excluded" case gets
 * something to exclude.
 */
public final class DiscoveryTests {

    private DiscoveryTests() {
    }

    public static void register(TestRunner runner) {
        runner.section("Discovery");

        runner.run("one of each supported kind is found, and nothing is registered", () -> {
            Path temp = TestSupport.tempDir("disc-kinds");
            try {
                Path root = Files.createDirectories(temp.resolve("apps"));
                writeAppImage(root.resolve("Tool.AppImage"));
                executable(root.resolve("Tool.AppImage"));
                writeElf(root.resolve("bintool"));
                executable(root.resolve("bintool"));
                writeTree(root.resolve("Tree"), true);
                Path jarDir = Files.createDirectories(root.resolve("jarapp"));
                writeJar(jarDir.resolve("standalone.jar"), "com.example.Main");

                DiscoveryResult result = scan(temp, List.of(root));

                assertEquals(List.of("bintool", "standalone", "tool", "tree"), ids(result),
                        "every kind must be proposed, ordered by display name");
                assertEquals(AppType.APPIMAGE, byId(result, "tool").application().type(), "AppImage type");
                assertEquals(Confidence.HIGH, byId(result, "tool").confidence(), "magic bytes are high confidence");
                assertEquals(AppType.BINARY, byId(result, "bintool").application().type(), "ELF binary type");
                assertEquals(AppType.PORTABLE, byId(result, "tree").application().type(), "AppRun tree type");
                assertEquals(AppType.JAVA_APP, byId(result, "standalone").application().type(),
                        "a jar alone in its directory may own the directory");
                assertEquals(root.resolve("Tree"), byId(result, "tree").application().installRoot(),
                        "the tree directory is the install root");
                assertTrue(byId(result, "standalone").application().environment().isEmpty(),
                        "discovery invents no environment");
                assertEquals("discovered:" + root, byId(result, "tool").application().origin(),
                        "the origin names the root that produced it");

                // "Never registers" is not an attitude, it is an observable: no apps directory exists.
                assertFalse(Files.exists(dirs(temp).appsDir()), "discovery must not create the registry");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("a jar among other files is not given an install root", () -> {
            Path temp = TestSupport.tempDir("disc-jar-shared");
            try {
                Path root = Files.createDirectories(temp.resolve("apps"));
                Path shared = Files.createDirectories(root.resolve("stuff"));
                writeJar(shared.resolve("tool.jar"), "com.example.Main");
                Files.writeString(shared.resolve("notes.txt"), "not an application");

                DiscoveryResult result = scan(temp, List.of(root));

                DiscoveryCandidate candidate = byId(result, "tool");
                assertEquals(AppType.CUSTOM, candidate.application().type(),
                        "a shared directory must not be claimed by one jar");
                assertNull(candidate.application().installRoot(), "no install root");
                assertEquals(List.of("-jar", shared.resolve("tool.jar").toString()),
                        candidate.application().launch().arguments(), "the jar is passed to the JVM");
                assertContainsText(candidate.reason(), "not claimed", "the reason must say why");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("a jar without Main-Class is reported, not proposed", () -> {
            Path temp = TestSupport.tempDir("disc-jar-nomain");
            try {
                Path root = Files.createDirectories(temp.resolve("apps"));
                writeJar(root.resolve("library.jar"), null);

                DiscoveryResult result = scan(temp, List.of(root));

                assertEquals(0, result.candidateCount(), "a library is not an application");
                assertContainsText(issues(result), "Main-Class", "the reason must name the missing key");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("an ELF file without the executable bit is ignored", () -> {
            Path temp = TestSupport.tempDir("disc-noexec");
            try {
                Path root = Files.createDirectories(temp.resolve("apps"));
                writeElf(root.resolve("libthing.so"));

                DiscoveryResult result = scan(temp, List.of(root));

                assertEquals(0, result.candidateCount(), "shared libraries are not proposed");
                assertEquals(0, result.issues().size(),
                        "reporting every non-executable file would bury the real issues");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("a file named .AppImage that is not ELF is reported", () -> {
            Path temp = TestSupport.tempDir("disc-fake-appimage");
            try {
                Path root = Files.createDirectories(temp.resolve("apps"));
                Path fake = root.resolve("Broken.AppImage");
                Files.writeString(fake, "#!/bin/sh\nexit 0\n");
                executable(fake);

                DiscoveryResult result = scan(temp, List.of(root));

                assertEquals(0, result.candidateCount(), "it cannot start as an AppImage");
                assertContainsText(issues(result), "not an ELF file", "the reason must say what is wrong");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("AppImage confidence follows the magic bytes, not the name", () -> {
            Path temp = TestSupport.tempDir("disc-appimage-magic");
            try {
                Path root = Files.createDirectories(temp.resolve("apps"));
                Path truncated = root.resolve("Truncated.AppImage");
                writeElf(truncated);
                executable(truncated);
                Path unsuffixed = root.resolve("mystery");
                writeAppImage(unsuffixed);
                executable(unsuffixed);

                DiscoveryResult result = scan(temp, List.of(root));

                assertEquals(Confidence.MEDIUM, byId(result, "truncated").confidence(),
                        "a name alone is not evidence");
                assertContainsText(byId(result, "truncated").reason(), "magic",
                        "the reason must mention the missing magic");
                assertEquals(AppType.APPIMAGE, byId(result, "mystery").application().type(),
                        "the magic is enough, the suffix is not required");
                assertEquals(Confidence.HIGH, byId(result, "mystery").confidence(), "magic is high confidence");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("a symlink loop does not escape the root and does not hang", () -> {
            Path temp = TestSupport.tempDir("disc-loop");
            try {
                Path root = Files.createDirectories(temp.resolve("apps"));
                Path loop = Files.createDirectories(root.resolve("loop"));
                writeElf(root.resolve("tool"));
                executable(root.resolve("tool"));
                try {
                    Files.createSymbolicLink(loop.resolve("back"), root);
                } catch (UnsupportedOperationException | IOException e) {
                    throw new TestSupport.Skip("symlinks unavailable: " + e.getMessage());
                }

                DiscoveryResult result = scan(temp, List.of(root));

                assertEquals(1, result.candidateCount(), "the real application is still found exactly once");
                assertContainsText(issues(result), "not followed", "the skipped link must be reported");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("an unreadable directory is reported and the scan continues", () -> {
            TestSupport.requireNonRoot();
            Path temp = TestSupport.tempDir("disc-denied");
            Path closed = null;
            try {
                Path root = Files.createDirectories(temp.resolve("apps"));
                closed = Files.createDirectories(root.resolve("private"));
                Files.writeString(closed.resolve("hidden"), "x");
                writeElf(root.resolve("tool"));
                executable(root.resolve("tool"));
                Files.setPosixFilePermissions(closed, Set.of());

                DiscoveryResult result = scan(temp, List.of(root));

                assertEquals(1, result.candidateCount(), "a locked directory must not hide the rest of the scan");
                assertContainsText(issues(result), "cannot be read", "the locked directory is reported");
            } finally {
                if (closed != null) {
                    Files.setPosixFilePermissions(closed,
                            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                                    PosixFilePermission.OWNER_EXECUTE));
                }
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("the depth limit keeps a deep application out of the list", () -> {
            Path temp = TestSupport.tempDir("disc-depth");
            try {
                Path root = Files.createDirectories(temp.resolve("apps"));
                Path deep = Files.createDirectories(root.resolve("a/b/c/d"));
                writeJar(deep.resolve("deepapp.jar"), "com.example.Main");

                DiscoveryResult shallow = new DiscoveryScanner(dirs(temp), javaExecutable(), 2)
                        .scan(List.of(root));
                assertEquals(0, shallow.candidateCount(), "beyond the depth limit nothing is proposed");

                DiscoveryResult full = scan(temp, List.of(root));
                assertEquals(1, full.candidateCount(), "the default depth reaches it");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("UserSpace's own directories are never scanned", () -> {
            Path temp = TestSupport.tempDir("disc-self");
            try {
                // The data home deliberately sits *inside* the scan root: a home-directory scan is the
                // realistic case, and it is the only way to exercise the per-directory exclusion rather
                // than the "this root is our own" check.
                Path root = Files.createDirectories(temp.resolve("apps"));
                XdgDirectories nested = XdgDirectories.from(Map.of(
                        "XDG_CONFIG_HOME", root.resolve(".config").toString(),
                        "XDG_DATA_HOME", root.resolve(".local/share").toString(),
                        "XDG_STATE_HOME", root.resolve(".local/state").toString(),
                        "XDG_CACHE_HOME", root.resolve(".cache").toString()), root);

                Path wrapper = Files.createDirectories(nested.binDir());
                writeElf(wrapper.resolve("generated-wrapper"));
                executable(wrapper.resolve("generated-wrapper"));
                writeElf(root.resolve("real"));
                executable(root.resolve("real"));

                DiscoveryResult result = new DiscoveryScanner(nested, javaExecutable()).scan(List.of(root));
                assertEquals(List.of("real"), ids(result),
                        "our own wrappers inside a scanned home must not be proposed as applications");

                DiscoveryResult selfRoot = new DiscoveryScanner(nested, javaExecutable())
                        .scan(List.of(nested.dataDir()));
                assertEquals(0, selfRoot.candidateCount(), "our data directory as a root yields nothing");
                assertContainsText(issues(selfRoot), "inside UserSpace's own directories",
                        "the refused root must be explained");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("the same executable found twice is proposed once", () -> {
            Path temp = TestSupport.tempDir("disc-twice");
            try {
                Path root = Files.createDirectories(temp.resolve("apps"));
                writeElf(root.resolve("tool"));
                executable(root.resolve("tool"));

                DiscoveryResult result = scan(temp, List.of(root, root));

                assertEquals(1, result.candidateCount(), "two overlapping roots must not duplicate a candidate");
                assertEquals(0, result.issues().size(), "overlap is not a problem worth reporting");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("an already registered executable is skipped and named", () -> {
            Path temp = TestSupport.tempDir("disc-known");
            try {
                Path root = Files.createDirectories(temp.resolve("apps"));
                writeElf(root.resolve("tool"));
                executable(root.resolve("tool"));
                writeElf(root.resolve("other"));
                executable(root.resolve("other"));

                DiscoveryResult result = new DiscoveryScanner(dirs(temp), javaExecutable())
                        .scan(List.of(root), Set.of(root.resolve("tool")), () -> false);

                assertEquals(List.of("other"), ids(result), "the registered application is not proposed again");
                assertContainsText(issues(result), "already registered", "the skip must be visible, not silent");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("two applications with the same name get different ids", () -> {
            Path temp = TestSupport.tempDir("disc-collision");
            try {
                Path one = Files.createDirectories(temp.resolve("one"));
                Path two = Files.createDirectories(temp.resolve("two"));
                writeElf(one.resolve("tool"));
                executable(one.resolve("tool"));
                writeElf(two.resolve("tool"));
                executable(two.resolve("tool"));

                DiscoveryResult result = scan(temp, List.of(one, two));

                assertEquals(List.of("tool", "tool-2"), ids(result),
                        "the second keeps the name and gets a suffix");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("cancellation stops the walk immediately", () -> {
            Path temp = TestSupport.tempDir("disc-cancel");
            try {
                Path root = Files.createDirectories(temp.resolve("apps"));
                writeElf(root.resolve("tool"));
                executable(root.resolve("tool"));

                AtomicInteger polls = new AtomicInteger();
                DiscoveryResult result = new DiscoveryScanner(dirs(temp), javaExecutable())
                        .scan(List.of(root), Set.of(), () -> polls.incrementAndGet() > 0);

                assertTrue(result.cancelled(), "the result must say it was cancelled");
                assertEquals(0, result.candidateCount(), "nothing may be proposed after a stop");
                assertTrue(polls.get() == 1, "the walk must stop at the first poll, not keep going: " + polls.get());
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("a simple .desktop file is imported at low confidence", () -> {
            Path temp = TestSupport.tempDir("disc-desktop");
            try {
                Path root = Files.createDirectories(temp.resolve("apps"));
                Path target = root.resolve("target-binary");
                writeElf(target);
                executable(target);
                Files.createDirectories(root.resolve("launchers"));
                writeDesktop(root.resolve("launchers/custom.desktop"),
                        "[Desktop Entry]",
                        "Type=Application",
                        "Name=Custom Tool",
                        "Exec=" + target,
                        "Icon=" + root.resolve("icon.png"));

                DiscoveryResult result = scan(temp, List.of(root));

                DiscoveryCandidate candidate = byId(result, "custom-tool");
                assertEquals(Confidence.LOW, candidate.confidence(), "an import is a reconstruction");
                assertEquals(AppType.CUSTOM, candidate.application().type(), "imported launchers are custom");
                assertEquals(target, candidate.executable(), "the Exec target is the executable");
                assertContainsText(candidate.reason(), "not reproduced", "the reason must admit what was lost");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("a .desktop file whose Exec needs quoting is reported instead", () -> {
            Path temp = TestSupport.tempDir("disc-desktop-quoted");
            try {
                Path root = Files.createDirectories(temp.resolve("apps"));
                writeDesktop(root.resolve("quoted.desktop"),
                        "[Desktop Entry]",
                        "Type=Application",
                        "Name=Quoted",
                        "Exec=\"/opt/My App/run.sh\" %U");

                DiscoveryResult result = scan(temp, List.of(root));

                assertEquals(0, result.candidateCount(), "guessing the quoting is how the wrong program starts");
                assertContainsText(issues(result), "quoting", "the reason must say why it was refused");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("hidden or non-application .desktop files are ignored silently", () -> {
            Path temp = TestSupport.tempDir("disc-desktop-hidden");
            try {
                Path root = Files.createDirectories(temp.resolve("apps"));
                writeDesktop(root.resolve("hidden.desktop"),
                        "[Desktop Entry]", "Type=Application", "Name=Hidden",
                        "Exec=/opt/whatever/run", "NoDisplay=true");
                writeDesktop(root.resolve("link.desktop"),
                        "[Desktop Entry]", "Type=Link", "Name=Link", "URL=https://example.invalid");

                DiscoveryResult result = scan(temp, List.of(root));

                assertEquals(0, result.candidateCount(), "neither is an application the user asked to see");
                assertEquals(0, result.issues().size(), "ignoring them is not an error");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("slugs are id-safe, accent-folded and never empty", () -> {
            assertEquals("media-cafe", Slug.from("Média Café"), "accents fold instead of becoming dashes");
            assertEquals("app", Slug.from("   "), "a blank name still yields a usable id");
            assertEquals("app", Slug.from("***"), "so does a name with no letters or digits");
            assertEquals("a-b", Slug.from("--a***b--"), "runs of separators collapse and edges are trimmed");

            String longName = "x".repeat(120);
            String slug = Slug.from(longName);
            assertEquals(64, slug.length(), "the id limit is enforced");
            new ApplicationId(slug); // must not throw: the result is always a legal id
        });
    }

    // --- fixtures ---------------------------------------------------------

    private static DiscoveryResult scan(Path temp, List<Path> roots) {
        return new DiscoveryScanner(dirs(temp), javaExecutable()).scan(roots);
    }

    private static XdgDirectories dirs(Path temp) {
        Path home = temp.resolve("home");
        return XdgDirectories.from(Map.of(
                "XDG_CONFIG_HOME", home.resolve("config").toString(),
                "XDG_DATA_HOME", home.resolve("data").toString(),
                "XDG_STATE_HOME", home.resolve("state").toString(),
                "XDG_CACHE_HOME", home.resolve("cache").toString()), home);
    }

    private static Path javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java");
    }

    private static void writeElf(Path file) throws IOException {
        Files.write(file, elfHeader(null));
    }

    /** ELF header with the AppImage magic ({@code AI\x02}) at offset 8, like a real type-2 AppImage. */
    private static void writeAppImage(Path file) throws IOException {
        Files.write(file, elfHeader(new byte[] {'A', 'I', 2}));
    }

    private static byte[] elfHeader(byte[] magicAt8) {
        byte[] bytes = new byte[64];
        bytes[0] = 0x7f;
        bytes[1] = 'E';
        bytes[2] = 'L';
        bytes[3] = 'F';
        bytes[4] = 2;
        bytes[5] = 1;
        bytes[6] = 1;
        if (magicAt8 != null) {
            System.arraycopy(magicAt8, 0, bytes, 8, magicAt8.length);
        }
        return bytes;
    }

    private static void writeTree(Path dir, boolean appRunExecutable) throws IOException {
        Files.createDirectories(dir);
        Path appRun = dir.resolve("AppRun");
        writeElf(appRun);
        if (appRunExecutable) {
            executable(appRun);
        }
    }

    private static void writeJar(Path file, String mainClass) throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            String manifest = "Manifest-Version: 1.0\r\n"
                    + (mainClass == null ? "" : "Main-Class: " + mainClass + "\r\n")
                    + "\r\n";
            zip.write(manifest.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private static void writeDesktop(Path file, String... lines) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, List.of(lines), StandardCharsets.UTF_8);
    }

    /** {@code setExecutable} rather than POSIX permissions, so the fixtures are not Linux-only. */
    private static void executable(Path file) {
        if (!file.toFile().setExecutable(true)) {
            throw new TestSupport.Skip("cannot set the executable bit on " + file);
        }
    }

    // --- assertions helpers ----------------------------------------------

    private static List<String> ids(DiscoveryResult result) {
        return result.candidates().stream().map(candidate -> candidate.id().value()).toList();
    }

    private static DiscoveryCandidate byId(DiscoveryResult result, String id) {
        return result.candidates().stream()
                .filter(candidate -> candidate.id().value().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no candidate '" + id + "' in " + ids(result) + "; issues: " + issues(result)));
    }

    private static String issues(DiscoveryResult result) {
        return result.issues().stream().map(Object::toString).collect(Collectors.joining("\n"));
    }

    private static void assertContainsText(String haystack, String needle, String message) {
        Assertions.assertContains(haystack, needle, message);
    }
}
