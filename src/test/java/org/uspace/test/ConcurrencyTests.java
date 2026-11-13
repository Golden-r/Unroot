package org.uspace.test;

import static org.uspace.test.Assertions.assertTrue;
import static org.uspace.test.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.uspace.config.ApplicationStore;
import org.uspace.core.registry.ApplicationRegistry;
import org.uspace.system.FileMutex;

/**
 * Cross-process proof for the write lock.
 *
 * <p>Threads in one JVM cannot demonstrate a file lock's purpose, so every case here starts real
 * child JVMs through {@link Child}. The strongest case is the ordering log: each child writes
 * {@code ENTER}/{@code EXIT} around its critical section, and the parent asserts the resulting
 * sequence never nests — that is mutual exclusion, observed rather than assumed.
 */
public final class ConcurrencyTests {

    private ConcurrencyTests() {
    }

    public static void register(TestRunner runner) {
        runner.section("Concurrency (separate processes)");

        runner.run("another process holding the lock makes tryLock and lock(timeout) fail", () -> {
            Path temp = TestSupport.tempDir("mp-busy");
            Path lockFile = temp.resolve("lock");
            Path log = temp.resolve("log");
            Process child = spawn("hold", lockFile.toString(), "A", "900", log.toString());
            try {
                awaitLog(log, "ENTER A", 15_000);

                FileMutex mutex = FileMutex.forFile(lockFile);
                try {
                    Assertions.assertFalse(mutex.tryLock(), "the lock must not be available while another process holds it");
                    Assertions.assertThrows(FileMutex.LockBusyException.class, () -> mutex.lock(200),
                            "a timed wait must fail with a message instead of hanging");
                } finally {
                    mutex.close();
                }

                assertTrue(child.waitFor(15, TimeUnit.SECONDS), "the child must finish");
                Assertions.assertEquals(0, child.exitValue(), "the child must not report a conflict");

                FileMutex after = FileMutex.forFile(lockFile);
                try {
                    assertTrue(after.tryLock(), "the lock must be free once the child exits");
                } finally {
                    after.close();
                }
            } finally {
                child.destroyForcibly();
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("critical sections from three processes never overlap", () -> {
            Path temp = TestSupport.tempDir("mp-order");
            Path lockFile = temp.resolve("lock");
            Path log = temp.resolve("log");
            List<Process> children = new ArrayList<>();
            try {
                children.add(spawn("hold", lockFile.toString(), "one", "150", log.toString()));
                children.add(spawn("hold", lockFile.toString(), "two", "150", log.toString()));
                children.add(spawn("hold", lockFile.toString(), "three", "150", log.toString()));
                for (Process child : children) {
                    assertTrue(child.waitFor(20, TimeUnit.SECONDS), "every child must finish");
                    Assertions.assertEquals(0, child.exitValue(), "no child may report a conflict");
                }

                List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8).stream()
                        .filter(line -> !line.isBlank())
                        .toList();
                Assertions.assertEquals(6, lines.size(), "three children, two events each: " + lines);

                boolean inside = false;
                Set<String> labels = new HashSet<>();
                for (String line : lines) {
                    String[] parts = line.split(" ", 2);
                    if (parts[0].equals("ENTER")) {
                        if (inside) {
                            fail("two processes were inside the critical section at once: " + lines);
                        }
                        inside = true;
                        labels.add(parts[1]);
                    } else {
                        assertTrue(inside, "EXIT without a matching ENTER: " + lines);
                        inside = false;
                    }
                }
                Assertions.assertFalse(inside, "the last critical section must be closed");
                Assertions.assertEquals(Set.of("one", "two", "three"), labels,
                        "all three children must have taken the lock");
            } finally {
                for (Process child : children) {
                    child.destroyForcibly();
                }
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("registrations from three processes all survive", () -> {
            Path temp = TestSupport.tempDir("mp-register");
            Path appsDir = temp.resolve("apps");
            Path lockFile = temp.resolve("lock");
            Path log = temp.resolve("log");
            Path barrier = temp.resolve("barrier");
            List<Process> children = new ArrayList<>();
            try {
                children.add(spawn("register", lockFile.toString(), appsDir.toString(), "alpha", "5", "alpha",
                        log.toString(), barrier.toString()));
                children.add(spawn("register", lockFile.toString(), appsDir.toString(), "beta", "5", "beta",
                        log.toString(), barrier.toString()));
                children.add(spawn("register", lockFile.toString(), appsDir.toString(), "gamma", "5", "gamma",
                        log.toString(), barrier.toString()));
                releaseWhenReady(log, 3, barrier);

                int registered = 0;
                for (Process child : children) {
                    assertTrue(child.waitFor(20, TimeUnit.SECONDS), "every child must finish");
                    String output = readAll(child);
                    Assertions.assertEquals(0, child.exitValue(), "child output: " + output);
                    registered += countOccurrences(output, " REGISTERED");
                }
                Assertions.assertEquals(15, registered, "every registration must be accepted");

                ApplicationRegistry registry = new ApplicationRegistry(
                        new ApplicationStore(appsDir), FileMutex.forFile(lockFile));
                Assertions.assertEquals(15, registry.size(), "all registrations must be on disk");
                Assertions.assertTrue(registry.loadIssues().isEmpty(),
                        "no registration may be corrupt: " + registry.loadIssues());
                for (String prefix : List.of("alpha", "beta", "gamma")) {
                    for (int i = 0; i < 5; i++) {
                        Assertions.assertTrue(registry.find(Fixtures.id(prefix + "-" + i)).isPresent(),
                                "missing registration " + prefix + "-" + i);
                    }
                }
                try (Stream<Path> paths = Files.walk(appsDir)) {
                    List<Path> leftovers = paths.filter(path -> path.getFileName().toString().endsWith(".tmp")).toList();
                    Assertions.assertTrue(leftovers.isEmpty(), "temp files left behind: " + leftovers);
                }
            } finally {
                for (Process child : children) {
                    child.destroyForcibly();
                }
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("the same id cannot be registered by two processes", () -> {
            Path temp = TestSupport.tempDir("mp-dup");
            Path appsDir = temp.resolve("apps");
            Path lockFile = temp.resolve("lock");
            Path log = temp.resolve("log");
            Path barrier = temp.resolve("barrier");
            List<Process> children = new ArrayList<>();
            try {
                children.add(spawn("register", lockFile.toString(), appsDir.toString(), "x", "5", "shared",
                        log.toString(), barrier.toString()));
                children.add(spawn("register", lockFile.toString(), appsDir.toString(), "y", "5", "shared",
                        log.toString(), barrier.toString()));
                children.add(spawn("register", lockFile.toString(), appsDir.toString(), "z", "5", "shared",
                        log.toString(), barrier.toString()));
                releaseWhenReady(log, 3, barrier);

                String all = "";
                for (Process child : children) {
                    assertTrue(child.waitFor(20, TimeUnit.SECONDS), "every child must finish");
                    String output = readAll(child);
                    Assertions.assertEquals(0, child.exitValue(), "child output: " + output);
                    all += output;
                }
                Assertions.assertEquals(5, countOccurrences(all, " REGISTERED"),
                        "exactly one process may win each id:\n" + all);
                Assertions.assertEquals(10, countOccurrences(all, " DUPLICATE_ID"),
                        "the losing processes must be told they lost:\n" + all);

                ApplicationRegistry registry = new ApplicationRegistry(
                        new ApplicationStore(appsDir), FileMutex.forFile(lockFile));
                Assertions.assertEquals(5, registry.size(), "exactly five registrations expected");
            } finally {
                for (Process child : children) {
                    child.destroyForcibly();
                }
                TestSupport.deleteRecursively(temp);
            }
        });
    }

    /**
     * Waits until every child has loaded its registry, then opens the barrier so all of them start
     * writing at the same moment. Without this, the children serialize naturally and the test would
     * pass even with no lock at all.
     */
    private static void releaseWhenReady(Path log, int expected, Path barrier) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(log)) {
                long ready = Files.readAllLines(log, StandardCharsets.UTF_8).stream()
                        .filter(line -> line.startsWith("READY "))
                        .count();
                if (ready >= expected) {
                    Files.writeString(barrier, "go", StandardCharsets.UTF_8);
                    return;
                }
            }
            Thread.sleep(20);
        }
        fail("only " + Files.readAllLines(log, StandardCharsets.UTF_8).size()
                + " children reached the barrier, expected " + expected);
    }

    private static Process spawn(String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(Child.class.getName());
        command.addAll(List.of(args));
        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }

    private static String readAll(Process process) throws IOException {
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String awaitLog(Path log, String needle, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(log)) {
                String content = Files.readString(log, StandardCharsets.UTF_8);
                if (content.contains(needle)) {
                    return content;
                }
            }
            Thread.sleep(20);
        }
        fail("timed out waiting for '" + needle + "' in " + log);
        return "";
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
