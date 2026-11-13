package org.uspace.test;

import static org.uspace.test.Assertions.assertEquals;
import static org.uspace.test.Assertions.assertFalse;
import static org.uspace.test.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.uspace.config.ConfigException;
import org.uspace.config.MetadataIssue;
import org.uspace.config.PropertiesFile;
import org.uspace.config.Settings;
import org.uspace.config.SettingsLoad;
import org.uspace.config.SettingsStore;

/** Settings persistence: defaults, round-trip, fallbacks and file hygiene. */
public final class SettingsTests {

    private SettingsTests() {
    }

    public static void register(TestRunner runner) {
        runner.section("Settings");

        runner.run("an absent settings file yields defaults without an issue", () -> {
            Path temp = TestSupport.tempDir("settings-absent");
            try {
                SettingsLoad load = new SettingsStore(temp.resolve("config")).load();
                assertEquals(Settings.defaults(), load.settings(), "defaults expected");
                assertEquals(0, load.issues().size(), "a first run is not a problem");
                assertFalse(Files.exists(temp.resolve("config").resolve(SettingsStore.FILE_NAME)),
                        "loading must not create the file");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("settings round-trip", () -> {
            Path temp = TestSupport.tempDir("settings-roundtrip");
            try {
                SettingsStore store = new SettingsStore(temp.resolve("config"));
                Settings original = new Settings(
                        List.of(Path.of("/home/user/Applications"),
                                Path.of("/home/user/My Apps/Optimisés")),
                        false,
                        "/bin/bash");
                store.save(original);
                assertEquals(original, store.load().settings(), "settings must round-trip");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("empty scan roots are not mistaken for orphaned entries", () -> {
            Path temp = TestSupport.tempDir("settings-emptyroots");
            try {
                SettingsStore store = new SettingsStore(temp.resolve("config"));
                store.save(Settings.defaults());
                String raw = Files.readString(store.file(), StandardCharsets.UTF_8);
                Assertions.assertContains(raw, "scan.roots.count=0", "the count key must be written");
                SettingsLoad load = store.load();
                assertEquals(0, load.settings().scanRoots().size(), "no roots expected");
                assertEquals(0, load.issues().size(), "count=0 with no entries is valid");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("invalid values fall back to defaults and are reported", () -> {
            Path temp = TestSupport.tempDir("settings-invalid");
            try {
                Files.createDirectories(temp.resolve("config"));
                Path file = temp.resolve("config").resolve(SettingsStore.FILE_NAME);
                Files.writeString(file, """
                        schema.version=1
                        confirm.beforeWriting=maybe
                        shell=bin/sh
                        scan.roots.count=3
                        scan.root.0=/home/user/Apps
                        scan.root.1=relative/path
                        scan.root.2=/home/user/Apps
                        """, StandardCharsets.UTF_8);

                SettingsLoad load = new SettingsStore(temp.resolve("config")).load();
                assertTrue(load.settings().confirmBeforeWriting(), "confirm must fall back to the safe default");
                assertEquals("", load.settings().preferredShell(), "a relative shell must be ignored");
                assertEquals(List.of(Path.of("/home/user/Apps")), load.settings().scanRoots(),
                        "relative roots are skipped and duplicates collapsed");
                assertEquals(3, load.issues().size(), "all three problems must be reported");
                String messages = load.issues().toString();
                Assertions.assertContains(messages, "confirm.beforeWriting", "boolean issue must name the key");
                Assertions.assertContains(messages, "relative", "relative path issue must explain itself");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("a missing scan root entry is reported, not invented", () -> {
            Path temp = TestSupport.tempDir("settings-gap");
            try {
                Files.createDirectories(temp.resolve("config"));
                Files.writeString(temp.resolve("config").resolve(SettingsStore.FILE_NAME), """
                        schema.version=1
                        scan.roots.count=2
                        scan.root.0=/home/user/Apps
                        """, StandardCharsets.UTF_8);
                SettingsLoad load = new SettingsStore(temp.resolve("config")).load();
                assertEquals(1, load.settings().scanRoots().size(), "the present root must load");
                assertEquals(1, load.issues().size(), "the missing entry must be reported");
                Assertions.assertContains(load.issues().get(0).message(), "scan.root.1", "the key must be named");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("a newer schema is refused and the file left untouched", () -> {
            Path temp = TestSupport.tempDir("settings-schema");
            try {
                Files.createDirectories(temp.resolve("config"));
                Path file = temp.resolve("config").resolve(SettingsStore.FILE_NAME);
                String content = "schema.version=99\nconfirm.beforeWriting=false\n";
                Files.writeString(file, content, StandardCharsets.UTF_8);

                SettingsLoad load = new SettingsStore(temp.resolve("config")).load();
                assertEquals(Settings.defaults(), load.settings(), "defaults must be used");
                assertEquals(1, load.issues().size(), "the newer schema must be reported");
                assertEquals(content, Files.readString(file, StandardCharsets.UTF_8),
                        "the file must not be rewritten");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("unknown settings keys survive a save", () -> {
            Path temp = TestSupport.tempDir("settings-unknown");
            try {
                SettingsStore store = new SettingsStore(temp.resolve("config"));
                store.save(Settings.defaults());
                Properties properties = PropertiesFile.read(store.file());
                properties.setProperty("ui.scale", "1.25");
                PropertiesFile.write(store.file(), properties, SettingsStore.COMMENT);

                store.save(new Settings(List.of(Path.of("/home/user/Apps")), false, "/bin/zsh"));

                Properties after = PropertiesFile.read(store.file());
                assertEquals("1.25", after.getProperty("ui.scale"), "unknown key must be preserved");
                assertEquals("false", after.getProperty("confirm.beforeWriting"), "known key must be updated");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("saving unchanged settings does not rewrite the file", () -> {
            Path temp = TestSupport.tempDir("settings-stable");
            try {
                SettingsStore store = new SettingsStore(temp.resolve("config"));
                Settings settings = new Settings(List.of(Path.of("/home/user/Apps")), true, "/bin/bash");
                store.save(settings);
                FileTime first = Files.getLastModifiedTime(store.file());
                Thread.sleep(20);
                store.save(settings);
                assertEquals(first, Files.getLastModifiedTime(store.file()),
                        "unchanged settings must not be rewritten");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("settings file is 0600 inside a 0700 directory", () -> {
            TestSupport.requireNonRoot();
            Path temp = TestSupport.tempDir("settings-perms");
            try {
                SettingsStore store = new SettingsStore(temp.resolve("config"));
                store.save(Settings.defaults());
                assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                        Files.getPosixFilePermissions(store.file()), "settings file mode");
                assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE),
                        Files.getPosixFilePermissions(store.file().getParent()), "config directory mode");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("saving settings into an unwritable directory fails loudly", () -> {
            TestSupport.requireNonRoot();
            Path temp = TestSupport.tempDir("settings-readonly");
            Path configDir = temp.resolve("config");
            try {
                Files.createDirectories(configDir);
                SettingsStore store = new SettingsStore(configDir);
                Files.setPosixFilePermissions(configDir,
                        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
                try {
                    Assertions.assertThrows(ConfigException.class,
                            () -> store.save(Settings.defaults()),
                            "an unwritable config directory must fail the save, not pretend success");
                } finally {
                    Files.setPosixFilePermissions(configDir, Set.of(PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
                }
                assertFalse(Files.exists(store.file()), "no settings file may be created");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("an unreadable settings file falls back to defaults with an issue", () -> {
            TestSupport.requireNonRoot();
            Path temp = TestSupport.tempDir("settings-unreadable");
            try {
                Files.createDirectories(temp.resolve("config"));
                Path file = temp.resolve("config").resolve(SettingsStore.FILE_NAME);
                Files.writeString(file, "confirm.beforeWriting=false\n", StandardCharsets.UTF_8);
                Files.setPosixFilePermissions(file, Set.of());

                SettingsLoad load = new SettingsStore(temp.resolve("config")).load();
                assertEquals(Settings.defaults(), load.settings(), "defaults must be used");
                assertEquals(1, load.issues().size(), "the unreadable file must be reported");
                for (MetadataIssue issue : load.issues()) {
                    Assertions.assertNotNull(issue.message(), "the issue must carry a message");
                }
            } finally {
                Files.setPosixFilePermissions(temp.resolve("config").resolve(SettingsStore.FILE_NAME),
                        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
                TestSupport.deleteRecursively(temp);
            }
        });
    }
}
