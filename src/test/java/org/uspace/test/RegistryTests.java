package org.uspace.test;

import static org.uspace.test.Assertions.assertEquals;
import static org.uspace.test.Assertions.assertFalse;
import static org.uspace.test.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.uspace.config.ApplicationStore;
import org.uspace.core.model.Application;
import org.uspace.core.registry.ApplicationRegistry;
import org.uspace.core.registry.RegistryResult;
import org.uspace.system.FileMutex;

/** Register, duplicate rejection, update, remove, and the persist-before-mutate rule. */
public final class RegistryTests {

    private RegistryTests() {
    }

    private static FileMutex mutex(Path temp) {
        return FileMutex.forFile(temp.resolve("lock"));
    }

    public static void register(TestRunner runner) {
        runner.section("Registry");

        runner.run("registration persists and is visible to a new registry instance", () -> {
            Path temp = TestSupport.tempDir("reg");
            try {
                ApplicationStore store = new ApplicationStore(temp.resolve("apps"));
                ApplicationRegistry registry = new ApplicationRegistry(store, mutex(temp));
                RegistryResult result = registry.register(Fixtures.minimal("tool"));
                assertEquals(RegistryResult.Outcome.REGISTERED, result.outcome(), "registration must succeed");
                assertEquals(1, registry.size(), "one application expected");

                ApplicationRegistry reloaded = new ApplicationRegistry(store, mutex(temp));
                assertEquals(1, reloaded.size(), "the registration must survive a restart");
                assertEquals("Minimal", reloaded.find(Fixtures.id("tool")).orElseThrow().name(),
                        "the reloaded application must match");
                assertTrue(reloaded.loadIssues().isEmpty(), "a clean load must report no issues");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("duplicate id is rejected", () -> {
            Path temp = TestSupport.tempDir("dupid");
            try {
                ApplicationRegistry registry = new ApplicationRegistry(new ApplicationStore(temp.resolve("apps")), mutex(temp));
                registry.register(Fixtures.minimal("tool"));
                RegistryResult result = registry.register(Fixtures.minimal("tool"));
                assertEquals(RegistryResult.Outcome.DUPLICATE_ID, result.outcome(), "same id must be refused");
                assertEquals(1, registry.size(), "the registry must be unchanged");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("duplicate executable under another id is rejected", () -> {
            Path temp = TestSupport.tempDir("dupexec");
            try {
                Path executable = temp.resolve("tool");
                Files.writeString(executable, "#!/bin/sh\n");
                ApplicationRegistry registry = new ApplicationRegistry(new ApplicationStore(temp.resolve("apps")), mutex(temp));
                registry.register(Fixtures.named("first", "First", executable));
                RegistryResult result = registry.register(Fixtures.named("second", "Second", executable));
                assertEquals(RegistryResult.Outcome.DUPLICATE_EXECUTABLE, result.outcome(),
                        "the same executable must not register twice");
                assertEquals(1, registry.size(), "the registry must be unchanged");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("a symlink to a registered executable is a duplicate", () -> {
            Path temp = TestSupport.tempDir("symlink");
            try {
                Path executable = temp.resolve("tool");
                Files.writeString(executable, "#!/bin/sh\n");
                Path link = temp.resolve("tool-link");
                try {
                    Files.createSymbolicLink(link, executable);
                } catch (UnsupportedOperationException | java.io.IOException e) {
                    throw new TestSupport.Skip("symlinks unavailable: " + e.getMessage());
                }
                ApplicationRegistry registry = new ApplicationRegistry(new ApplicationStore(temp.resolve("apps")), mutex(temp));
                registry.register(Fixtures.named("first", "First", executable));
                RegistryResult result = registry.register(Fixtures.named("second", "Second", link));
                assertEquals(RegistryResult.Outcome.DUPLICATE_EXECUTABLE, result.outcome(),
                        "a symlink to the same file must be detected");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("metadata update is persisted", () -> {
            Path temp = TestSupport.tempDir("update");
            try {
                ApplicationStore store = new ApplicationStore(temp.resolve("apps"));
                ApplicationRegistry registry = new ApplicationRegistry(store, mutex(temp));
                Application original = Fixtures.minimal("tool");
                registry.register(original);

                RegistryResult result = registry.update(original.withMetadata("Renamed", "Now described"));
                assertEquals(RegistryResult.Outcome.UPDATED, result.outcome(), "update must succeed");

                ApplicationRegistry reloaded = new ApplicationRegistry(store, mutex(temp));
                Application after = reloaded.find(Fixtures.id("tool")).orElseThrow();
                assertEquals("Renamed", after.name(), "name must be persisted");
                assertEquals("Now described", after.description(), "description must be persisted");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("update to an executable owned by another application is rejected", () -> {
            Path temp = TestSupport.tempDir("update-dup");
            try {
                Path a = temp.resolve("a");
                Path b = temp.resolve("b");
                Files.writeString(a, "a");
                Files.writeString(b, "b");
                ApplicationRegistry registry = new ApplicationRegistry(new ApplicationStore(temp.resolve("apps")), mutex(temp));
                registry.register(Fixtures.named("one", "One", a));
                registry.register(Fixtures.named("two", "Two", b));

                Application stolen = Fixtures.named("two", "Two", a);
                RegistryResult result = registry.update(stolen);
                assertEquals(RegistryResult.Outcome.DUPLICATE_EXECUTABLE, result.outcome(),
                        "stealing another application's executable must be refused");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("updating or removing an unknown id reports NOT_FOUND", () -> {
            Path temp = TestSupport.tempDir("notfound");
            try {
                ApplicationRegistry registry = new ApplicationRegistry(new ApplicationStore(temp.resolve("apps")), mutex(temp));
                assertEquals(RegistryResult.Outcome.NOT_FOUND,
                        registry.update(Fixtures.minimal("nope")).outcome(), "update of unknown id");
                assertEquals(RegistryResult.Outcome.NOT_FOUND,
                        registry.remove(Fixtures.id("nope")).outcome(), "removal of unknown id");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("removal clears memory and disk", () -> {
            Path temp = TestSupport.tempDir("remove");
            try {
                ApplicationStore store = new ApplicationStore(temp.resolve("apps"));
                ApplicationRegistry registry = new ApplicationRegistry(store, mutex(temp));
                registry.register(Fixtures.minimal("tool"));

                RegistryResult result = registry.remove(Fixtures.id("tool"));
                assertEquals(RegistryResult.Outcome.REMOVED, result.outcome(), "removal must succeed");
                assertEquals(0, registry.size(), "memory must be cleared");
                assertFalse(Files.exists(store.metadataFile(Fixtures.id("tool"))), "disk must be cleared");
                assertEquals(0, new ApplicationRegistry(store, mutex(temp)).size(), "a restart must see no applications");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("a failed write leaves the registry unchanged", () -> {
            TestSupport.requireNonRoot();
            Path temp = TestSupport.tempDir("readonly-registry");
            Path appsDir = temp.resolve("apps");
            try {
                Files.createDirectories(appsDir);
                ApplicationRegistry registry = new ApplicationRegistry(new ApplicationStore(appsDir), mutex(temp));
                Files.setPosixFilePermissions(appsDir,
                        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
                try {
                    RegistryResult result = registry.register(Fixtures.minimal("tool"));
                    assertEquals(RegistryResult.Outcome.STORE_FAILURE, result.outcome(),
                            "an unwritable store must be reported, not thrown");
                } finally {
                    Files.setPosixFilePermissions(appsDir, Set.of(PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
                }
                assertEquals(0, registry.size(), "memory must not claim an application that was not written");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("one corrupt registration does not hide the others", () -> {
            Path temp = TestSupport.tempDir("partial");
            try {
                ApplicationStore store = new ApplicationStore(temp.resolve("apps"));
                store.save(Fixtures.minimal("good"));
                Path broken = store.metadataFile(Fixtures.id("broken"));
                Files.createDirectories(broken.getParent());
                Files.writeString(broken, "schema.version=1\n", java.nio.charset.StandardCharsets.UTF_8);

                ApplicationRegistry registry = new ApplicationRegistry(store, mutex(temp));
                assertEquals(1, registry.size(), "the valid application must still load");
                assertEquals(1, registry.loadIssues().size(), "the broken one must be reported");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("all() is ordered by display name", () -> {
            Path temp = TestSupport.tempDir("order");
            try {
                ApplicationRegistry registry = new ApplicationRegistry(new ApplicationStore(temp.resolve("apps")), mutex(temp));
                registry.register(Fixtures.named("zebra", "zebra", temp.resolve("z")));
                registry.register(Fixtures.named("apple", "Apple", temp.resolve("a")));
                registry.register(Fixtures.named("mango", "mango", temp.resolve("m")));
                assertEquals(java.util.List.of("Apple", "mango", "zebra"),
                        registry.all().stream().map(Application::name).toList(),
                        "the table must not jump between refreshes");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });
    }
}
