package org.uspace.test;

import static org.uspace.test.Assertions.assertEquals;
import static org.uspace.test.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.uspace.config.ApplicationStore;
import org.uspace.core.model.AppType;
import org.uspace.core.model.Application;
import org.uspace.core.model.EnvVariable;
import org.uspace.core.model.LaunchCommand;

/** Round-trip fidelity of {@link Application} through a real metadata file. */
public final class SerializationTests {

    private SerializationTests() {
    }

    public static void register(TestRunner runner) {
        runner.section("Serialization");

        runner.run("every field survives write and read", () -> {
            Path temp = TestSupport.tempDir("ser");
            try {
                ApplicationStore store = new ApplicationStore(temp.resolve("apps"));
                Application original = Fixtures.full();
                store.save(original);
                Application loaded = store.load(Fixtures.id("media-player")).orElseThrow();
                assertEquals(original, loaded, "decoded application must equal the original");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("unicode names and descriptions are stored literally", () -> {
            Path temp = TestSupport.tempDir("unicode");
            try {
                ApplicationStore store = new ApplicationStore(temp.resolve("apps"));
                Application original = new Application(
                        Fixtures.id("unicode-app"),
                        "Média Café 日本語 ✓",
                        "Ünïcödé — 説明 \"quoted\"",
                        AppType.BINARY,
                        LaunchCommand.of(Path.of("/home/user/bin/ünïcödé")),
                        null, null, List.of(), Fixtures.REGISTERED_AT, "manual");
                store.save(original);

                String raw = Files.readString(store.metadataFile(Fixtures.id("unicode-app")), StandardCharsets.UTF_8);
                Assertions.assertContains(raw, "Média Café 日本語 ✓", "file must contain the text as UTF-8, not \\u escapes");
                Assertions.assertFalse(raw.contains("\\u"), "no unicode escapes may appear in the file");

                assertEquals(original, store.load(Fixtures.id("unicode-app")).orElseThrow(),
                        "unicode must round-trip exactly");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("paths containing spaces round-trip", () -> {
            Path temp = TestSupport.tempDir("spaces");
            try {
                ApplicationStore store = new ApplicationStore(temp.resolve("apps"));
                Application original = new Application(
                        Fixtures.id("spaced"),
                        "Spaced",
                        "",
                        AppType.PORTABLE,
                        new LaunchCommand(Path.of("/home/user/My Apps/Best App v1.0/run me.sh"),
                                List.of("/home/user/My Apps/Best App v1.0/data dir"),
                                Path.of("/home/user/My Apps/Best App v1.0")),
                        Path.of("/home/user/My Apps/Best App v1.0"),
                        Path.of("/home/user/My Apps/Best App v1.0/icon one.png"),
                        List.of(), Fixtures.REGISTERED_AT, "manual");
                store.save(original);
                assertEquals(original, store.load(Fixtures.id("spaced")).orElseThrow(),
                        "paths with spaces must round-trip");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("values containing '=' round-trip", () -> {
            Path temp = TestSupport.tempDir("equals");
            try {
                ApplicationStore store = new ApplicationStore(temp.resolve("apps"));
                Application original = new Application(
                        Fixtures.id("equals"),
                        "Equals",
                        "fast=true&safe=false",
                        AppType.BINARY,
                        LaunchCommand.of(Path.of("/home/user/bin/tool")),
                        null, null,
                        List.of(new EnvVariable("JAVA_OPTS", "-Dfoo=bar -Dbaz=qux"),
                                new EnvVariable("EMPTY", "")),
                        Fixtures.REGISTERED_AT, "manual");
                store.save(original);
                Application loaded = store.load(Fixtures.id("equals")).orElseThrow();
                assertEquals(original, loaded, "values containing '=' must round-trip");
                assertEquals("-Dfoo=bar -Dbaz=qux", loaded.environment().get(0).value(),
                        "the value must not be truncated at the first '='");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("empty optional values stay absent", () -> {
            Path temp = TestSupport.tempDir("empty");
            try {
                ApplicationStore store = new ApplicationStore(temp.resolve("apps"));
                Application original = Fixtures.minimal("minimal");
                store.save(original);
                Application loaded = store.load(Fixtures.id("minimal")).orElseThrow();

                Assertions.assertNull(loaded.installRoot(), "install root must remain absent");
                Assertions.assertNull(loaded.icon(), "icon must remain absent");
                assertEquals("", loaded.description(), "description must be empty, not null");
                assertTrue(loaded.launch().arguments().isEmpty(), "arguments must remain empty");
                assertTrue(loaded.environment().isEmpty(), "environment must remain empty");
                assertEquals(original, loaded, "minimal application must round-trip");

                String raw = Files.readString(store.metadataFile(Fixtures.id("minimal")), StandardCharsets.UTF_8);
                Assertions.assertFalse(raw.contains("icon="), "absent optional keys must not be written");
                Assertions.assertFalse(raw.contains("install.root="), "absent optional keys must not be written");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("multiple environment variables keep their order", () -> {
            Path temp = TestSupport.tempDir("env");
            try {
                ApplicationStore store = new ApplicationStore(temp.resolve("apps"));
                Application original = new Application(
                        Fixtures.id("env-app"), "Env", "", AppType.BINARY,
                        LaunchCommand.of(Path.of("/home/user/bin/tool")), null, null,
                        List.of(new EnvVariable("Z_LAST", "3"),
                                new EnvVariable("A_FIRST", "1"),
                                new EnvVariable("M_MIDDLE", "2")),
                        Fixtures.REGISTERED_AT, "manual");
                store.save(original);
                Application loaded = store.load(Fixtures.id("env-app")).orElseThrow();
                assertEquals(List.of("Z_LAST=3", "A_FIRST=1", "M_MIDDLE=2"),
                        loaded.environmentAssignments(), "order of environment variables must be preserved");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("arguments with spaces and unicode round-trip in order", () -> {
            Path temp = TestSupport.tempDir("args");
            try {
                ApplicationStore store = new ApplicationStore(temp.resolve("apps"));
                Application original = new Application(
                        Fixtures.id("args"), "Args", "", AppType.JAVA_APP,
                        new LaunchCommand(Path.of("/home/user/lib/app.jar"),
                                List.of("--title", "Mon Application", "--dir", "/home/user/Mes Documents", ""),
                                Path.of("/home/user/lib")),
                        Path.of("/home/user/lib"), null, List.of(), Fixtures.REGISTERED_AT, "manual");
                store.save(original);
                assertEquals(original, store.load(Fixtures.id("args")).orElseThrow(),
                        "arguments must round-trip including empty and spaced values");
            } finally {
                TestSupport.deleteRecursively(temp);
            }
        });
    }
}
