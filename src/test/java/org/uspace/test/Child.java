package org.uspace.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import org.uspace.config.ApplicationStore;
import org.uspace.core.model.AppType;
import org.uspace.core.model.Application;
import org.uspace.core.model.ApplicationId;
import org.uspace.core.model.LaunchCommand;
import org.uspace.core.registry.ApplicationRegistry;
import org.uspace.core.registry.RegistryResult;
import org.uspace.system.FileMutex;

/**
 * A separate JVM used by the concurrency tests. Two threads in one process cannot prove anything
 * about {@link FileMutex}: the interesting case is two operating-system processes, which is what this
 * harness provides.
 *
 * <p>Modes:
 * <ul>
 *   <li>{@code hold <lockFile> <label> <millis> <logFile>} — take the lock, log {@code ENTER},
 *       sleep, log {@code EXIT}, release. Exit code 2 when the lock stays busy.</li>
 *   <li>{@code register <lockFile> <appsDir> <label> <count> <idPrefix> <logFile> <barrierFile>} —
 *       load the registry, log {@code READY}, wait for the parent to create the barrier file, then
 *       register {@code count} applications, printing one {@code OUTCOME <id> <outcome>} line
 *       each. The barrier is what forces a real race: every process has already loaded the
 *       registry before any of them writes.</li>
 * </ul>
 */
public final class Child {

    private Child() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: Child hold|register ...");
            System.exit(64);
            return;
        }
        switch (args[0]) {
            case "hold" -> hold(args);
            case "register" -> register(args);
            default -> {
                System.err.println("unknown mode " + args[0]);
                System.exit(64);
            }
        }
    }

    private static void hold(String[] args) throws Exception {
        Path lockFile = Path.of(args[1]);
        String label = args[2];
        long holdMillis = Long.parseLong(args[3]);
        Path log = Path.of(args[4]);

        FileMutex mutex = FileMutex.forFile(lockFile);
        try {
            mutex.lock(10_000);
        } catch (FileMutex.LockBusyException e) {
            System.err.println("BUSY " + label + ": " + e.getMessage());
            System.exit(2);
            return;
        }
        append(log, "ENTER " + label);
        Thread.sleep(holdMillis);
        append(log, "EXIT " + label);
        mutex.unlock();
        System.out.println("OK " + label);
    }

    private static void register(String[] args) throws Exception {
        Path lockFile = Path.of(args[1]);
        Path appsDir = Path.of(args[2]);
        String label = args[3];
        int count = Integer.parseInt(args[4]);
        String idPrefix = args[5];
        Path log = Path.of(args[6]);
        Path barrier = Path.of(args[7]);

        ApplicationRegistry registry = new ApplicationRegistry(
                new ApplicationStore(appsDir), FileMutex.forFile(lockFile));
        append(log, "READY " + label);
        awaitBarrier(barrier);

        for (int i = 0; i < count; i++) {
            Application application = new Application(
                    new ApplicationId(idPrefix + "-" + i),
                    "App " + i + " from " + label,
                    "",
                    AppType.BINARY,
                    LaunchCommand.of(Path.of("/tmp/fake-" + label + "-" + i)),
                    null, null, List.of(), Instant.now(), "child:" + label);
            RegistryResult result = registry.register(application);
            System.out.println("OUTCOME " + application.id() + " " + result.outcome());
        }
        System.out.flush();
    }

    private static void awaitBarrier(Path barrier) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!Files.exists(barrier)) {
            if (System.currentTimeMillis() > deadline) {
                System.err.println("barrier timeout: " + barrier);
                System.exit(3);
                return;
            }
            Thread.sleep(10);
        }
    }

    private static void append(Path file, String line) throws IOException {
        Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
