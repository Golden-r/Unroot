package org.uspace.config;

import static org.uspace.test.Assertions.assertEquals;
import static org.uspace.test.Assertions.assertFalse;
import static org.uspace.test.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.uspace.core.model.ApplicationId;
import org.uspace.test.Fixtures;
import org.uspace.test.TestRunner;
import org.uspace.test.TestSupport;

/**
 * File-safety guarantees: atomic replacement, no leftover temp files, permissions, and what happens
 * when the write cannot be completed.
 *
 * <p>Lives in {@code org.uspace.config} so it can exercise the package-private move fallback that is
 * normally reached only on filesystems without atomic rename support.
 */
public final class FileSafetyTests {

    private static final Set<PosixFilePermission> PRIVATE_FILE =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);

    private FileSafetyTests() {
    }

    public static void register(TestRunner runner) {
        runner.section("File safety");

        runner.run("metadata is 0600 and its directories 0700", () -> {
            TestSupport.requireNonRoot();
            Path temp = TestSupport.tempDir("perms");
            try {
                ApplicationStore store = new ApplicationStore(temp.resolve("apps"));
                store.save(Fixtures.minimal("tool"));

                Path file = store.metadataFile(Fixtures.id("tool"));
                assertEquals(PRIVATE_FILE, Files.getPosixFilePermissions(file), "metadata file mode");
                assertEquals(PRIVATE_DIRECTORY, Files.getPosixFilePermissions(store.directoryFor(Fixtures.id("tool"))),
                        "application directory mode");
                assertEquals(PRIVATE_DIRECTORY, Files.getPosixFilePermissions(store.appsDir()),
                        "applications directory mode");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("an unwritable directory fails the write, keeps the old file and leaves no temp file", () -> {
            TestSupport.requireNonRoot();
            Path temp = TestSupport.tempDir("readonly");
            try {
                ApplicationStore store = new ApplicationStore(temp.resolve("apps"));
                store.save(Fixtures.minimal("tool"));
                Path file = store.metadataFile(Fixtures.id("tool"));
                Path directory = store.directoryFor(Fixtures.id("tool"));
                byte[] before = Files.readAllBytes(file);

                Files.setPosixFilePermissions(directory,
                        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
                try {
                    ConfigException failure = org.uspace.test.Assertions.assertThrows(ConfigException.class,
                            () -> store.save(Fixtures.minimal("tool").withMetadata("Changed", "")),
                            "writing into a read-only directory must fail");
                    assertTrue(failure.getMessage().contains("cannot write"),
                            "the failure must name the operation: " + failure.getMessage());
                } finally {
                    Files.setPosixFilePermissions(directory, PRIVATE_DIRECTORY);
                }

                assertEquals(new String(before, StandardCharsets.UTF_8),
                        Files.readString(file, StandardCharsets.UTF_8),
                        "the previous content must be intact after a failed write");
                try (Stream<Path> entries = Files.list(directory)) {
                    List<String> names = entries.map(path -> path.getFileName().toString()).sorted().toList();
                    assertEquals(List.of(ApplicationStore.METADATA_FILE), names,
                            "no temporary file may be left behind");
                }
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("a successful write leaves no temporary file", () -> {
            Path temp = TestSupport.tempDir("clean");
            try {
                ApplicationStore store = new ApplicationStore(temp.resolve("apps"));
                store.save(Fixtures.minimal("tool"));
                try (Stream<Path> entries = Files.list(store.directoryFor(Fixtures.id("tool")))) {
                    assertEquals(List.of(ApplicationStore.METADATA_FILE),
                            entries.map(path -> path.getFileName().toString()).toList(),
                            "only the metadata file may remain");
                }
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("saving unchanged data does not rewrite the file", () -> {
            Path temp = TestSupport.tempDir("stable");
            try {
                ApplicationStore store = new ApplicationStore(temp.resolve("apps"));
                store.save(Fixtures.full());
                Path file = store.metadataFile(Fixtures.id("media-player"));
                String first = Files.readString(file, StandardCharsets.UTF_8);
                java.nio.file.attribute.FileTime firstModified = Files.getLastModifiedTime(file);

                Thread.sleep(20);
                store.save(Fixtures.full());

                assertEquals(first, Files.readString(file, StandardCharsets.UTF_8),
                        "the content must not change when the data does not");
                assertEquals(firstModified, Files.getLastModifiedTime(file),
                        "an unchanged application must not be rewritten at all");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("the non-atomic fallback replaces an existing file", () -> {
            Path temp = TestSupport.tempDir("fallback");
            try {
                Path target = temp.resolve("target.properties");
                Files.writeString(target, "old=content\n", StandardCharsets.UTF_8);
                Path staged = temp.resolve("staged.properties");
                Files.writeString(staged, "new=content\n", StandardCharsets.UTF_8);

                PropertiesFile.moveReplacingWithoutAtomicMove(staged, target);

                assertEquals("new=content\n", Files.readString(target, StandardCharsets.UTF_8),
                        "the fallback must replace the file");
                assertFalse(Files.exists(staged), "the staged file must be gone");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("a target inside a regular file is reported, not thrown raw", () -> {
            Path temp = TestSupport.tempDir("badparent");
            try {
                Path blocked = temp.resolve("blocked");
                Files.writeString(blocked, "not a directory", StandardCharsets.UTF_8);
                Path target = blocked.resolve("apps").resolve("meta.properties");

                org.uspace.test.Assertions.assertThrows(ConfigException.class,
                        () -> PropertiesFile.write(target, new java.util.Properties(), ApplicationCodec.COMMENT),
                        "an impossible path must surface as a ConfigException");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("reading a missing file is a ConfigException with the path", () -> {
            Path temp = TestSupport.tempDir("nofile");
            try {
                ConfigException failure = org.uspace.test.Assertions.assertThrows(ConfigException.class,
                        () -> PropertiesFile.read(temp.resolve("absent.properties")),
                        "reading a missing file must fail");
                assertTrue(failure.getMessage().contains("absent.properties"),
                        "the failure must name the file: " + failure.getMessage());
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("codec refuses to encode inconsistent data", () -> {
            ApplicationId id = Fixtures.id("portable");
            org.uspace.test.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new org.uspace.core.model.Application(id, "Broken", "",
                            org.uspace.core.model.AppType.PORTABLE,
                            org.uspace.core.model.LaunchCommand.of(Path.of("/home/user/bin/x")),
                            null, null, List.of(), Fixtures.REGISTERED_AT, "manual"),
                    "a PORTABLE application without an install root must be rejected by the model");
        });
    }
}
