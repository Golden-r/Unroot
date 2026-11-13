package org.uspace.test;

import static org.uspace.test.Assertions.assertEquals;
import static org.uspace.test.Assertions.assertFalse;
import static org.uspace.test.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.uspace.config.ApplicationCodec;
import org.uspace.config.ApplicationStore;
import org.uspace.config.LoadReport;
import org.uspace.config.PropertiesFile;
import org.uspace.core.model.Application;

/** Loading, corruption tolerance, schema versions and removal. */
public final class StoreTests {

    private StoreTests() {
    }

    public static void register(TestRunner runner) {
        runner.section("Registry persistence");

        runner.run("missing metadata file is reported and other applications still load", () -> {
            Path temp = TestSupport.tempDir("missing");
            try {
                ApplicationStore store = new ApplicationStore(temp.resolve("apps"));
                store.save(Fixtures.minimal("good"));
                Files.createDirectories(store.directoryFor(Fixtures.id("ghost")));

                LoadReport report = store.loadAll();
                assertEquals(1, report.applications().size(), "the valid application must load");
                assertEquals(1, report.issues().size(), "the empty directory must be reported");
                Assertions.assertContains(report.issues().get(0).message(), "missing",
                        "the issue must say the metadata file is missing");
                assertEquals(Fixtures.id("good"), report.applications().get(0).id(), "wrong application loaded");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("corrupted metadata is reported, not thrown", () -> {
            Path temp = TestSupport.tempDir("corrupt");
            try {
                ApplicationStore store = new ApplicationStore(temp.resolve("apps"));
                store.save(Fixtures.minimal("good"));

                Path garbage = store.metadataFile(Fixtures.id("garbage"));
                Files.createDirectories(garbage.getParent());
                Files.write(garbage, new byte[] {(byte) 0xFF, (byte) 0xFE, 0x00, 0x01});

                Path wrongType = store.metadataFile(Fixtures.id("wrongtype"));
                Files.createDirectories(wrongType.getParent());
                Files.writeString(wrongType, """
                        schema.version=1
                        id=wrongtype
                        name=Wrong
                        type=NOT_A_TYPE
                        launch.executable=/home/user/bin/x
                        registered.at=2026-02-03T04:05:06Z
                        """, StandardCharsets.UTF_8);

                LoadReport report = store.loadAll();
                assertEquals(1, report.applications().size(), "only the valid application may load");
                assertEquals(2, report.issues().size(), "both broken directories must be reported");
                String messages = report.issues().toString();
                Assertions.assertContains(messages, "type", "the bad type must be named in the issue");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("invalid directory name and id mismatch are reported", () -> {
            Path temp = TestSupport.tempDir("mismatch");
            try {
                ApplicationStore store = new ApplicationStore(temp.resolve("apps"));

                Path invalid = temp.resolve("apps").resolve("Not A Slug").resolve(ApplicationStore.METADATA_FILE);
                Files.createDirectories(invalid.getParent());
                Files.writeString(invalid, "id=not-a-slug\n", StandardCharsets.UTF_8);

                Path mismatched = store.metadataFile(Fixtures.id("other"));
                Files.createDirectories(mismatched.getParent());
                Properties props = ApplicationCodec.encode(Fixtures.minimal("alpha"), new Properties());
                PropertiesFile.write(mismatched, props, ApplicationCodec.COMMENT);

                LoadReport report = store.loadAll();
                assertEquals(0, report.applications().size(), "neither directory may load");
                assertEquals(2, report.issues().size(), "both inconsistencies must be reported");
                String messages = report.issues().toString();
                Assertions.assertContains(messages, "not a valid application id", "invalid directory name not reported");
                Assertions.assertContains(messages, "does not match directory", "id mismatch not reported");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("schema version 1 loads, a newer version is refused, a missing version is treated as 1", () -> {
            Path temp = TestSupport.tempDir("schema");
            try {
                ApplicationStore store = new ApplicationStore(temp.resolve("apps"));
                store.save(Fixtures.minimal("current"));

                Path newer = store.metadataFile(Fixtures.id("future"));
                Files.createDirectories(newer.getParent());
                Files.writeString(newer, """
                        schema.version=999
                        id=future
                        name=Future
                        type=BINARY
                        launch.executable=/home/user/bin/x
                        registered.at=2026-02-03T04:05:06Z
                        """, StandardCharsets.UTF_8);

                Path legacy = store.metadataFile(Fixtures.id("legacy"));
                Files.createDirectories(legacy.getParent());
                Files.writeString(legacy, """
                        id=legacy
                        name=Legacy
                        type=BINARY
                        launch.executable=/home/user/bin/legacy
                        registered.at=2026-02-03T04:05:06Z
                        """, StandardCharsets.UTF_8);

                LoadReport report = store.loadAll();
                assertEquals(2, report.applications().size(), "current and unversioned metadata must load");
                assertEquals(1, report.issues().size(), "the future schema must be reported");
                Assertions.assertContains(report.issues().get(0).message(), "newer UserSpace",
                        "the issue must explain the schema problem");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("unknown properties survive an update", () -> {
            Path temp = TestSupport.tempDir("unknown");
            try {
                ApplicationStore store = new ApplicationStore(temp.resolve("apps"));
                Application original = Fixtures.minimal("keep");
                store.save(original);

                Path file = store.metadataFile(Fixtures.id("keep"));
                Properties props = PropertiesFile.read(file);
                props.setProperty("future.key", "keep me");
                props.setProperty("integration.desktop", "/home/user/.local/share/applications/userspace-keep.desktop");
                PropertiesFile.write(file, props, ApplicationCodec.COMMENT);

                store.save(original.withMetadata("Renamed", "new description"));

                Properties after = PropertiesFile.read(file);
                assertEquals("keep me", after.getProperty("future.key"), "unknown key must be preserved");
                Assertions.assertNotNull(after.getProperty("integration.desktop"),
                        "another version's key must be preserved");
                assertEquals("Renamed", after.getProperty("name"), "known key must be updated");
                assertEquals(original.launch().executable().toString(), after.getProperty("launch.executable"),
                        "unchanged fields must be rewritten consistently");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("removal deletes our metadata and leaves foreign files alone", () -> {
            Path temp = TestSupport.tempDir("remove");
            try {
                ApplicationStore store = new ApplicationStore(temp.resolve("apps"));
                store.save(Fixtures.minimal("gone"));
                Path directory = store.directoryFor(Fixtures.id("gone"));
                Path foreign = directory.resolve("user-notes.txt");
                Files.writeString(foreign, "mine", StandardCharsets.UTF_8);

                assertTrue(store.delete(Fixtures.id("gone")), "delete must report that it removed metadata");
                assertFalse(Files.exists(store.metadataFile(Fixtures.id("gone"))), "metadata file must be gone");
                assertTrue(Files.exists(foreign), "a file we did not create must be kept");
                assertTrue(Files.isDirectory(directory), "a non-empty directory must be kept");

                assertFalse(store.delete(Fixtures.id("gone")), "deleting twice must report nothing removed");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("loading an absent applications directory is not an error", () -> {
            Path temp = TestSupport.tempDir("absent");
            try {
                ApplicationStore store = new ApplicationStore(temp.resolve("does-not-exist"));
                LoadReport report = store.loadAll();
                assertEquals(0, report.applications().size(), "no applications expected");
                assertEquals(0, report.issues().size(), "an absent directory is a first run, not an issue");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });
    }
}
