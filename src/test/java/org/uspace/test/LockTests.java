package org.uspace.test;

import static org.uspace.test.Assertions.assertEquals;
import static org.uspace.test.Assertions.assertFalse;
import static org.uspace.test.Assertions.assertNotEquals;
import static org.uspace.test.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.uspace.system.FileMutex;

/** Re-entrancy, release accounting and lock-file hygiene. Cross-process behaviour lives in {@link ConcurrencyTests}. */
public final class LockTests {

    private LockTests() {
    }

    public static void register(TestRunner runner) {
        runner.section("Lock");

        runner.run("the same path yields the same mutex instance", () -> {
            Path temp = TestSupport.tempDir("lock-shared");
            FileMutex first = FileMutex.forFile(temp.resolve("lock"));
            FileMutex second = FileMutex.forFile(temp.resolve("sub/../lock"));
            try {
                assertEquals(first, second, "one instance per path, so re-entry cannot collide");
            } finally {
                first.close();
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("tryLock is re-entrant and unlock counts down", () -> {
            Path temp = TestSupport.tempDir("lock-reentrant");
            FileMutex mutex = FileMutex.forFile(temp.resolve("lock"));
            try {
                assertTrue(mutex.tryLock(), "first acquisition must succeed");
                assertTrue(mutex.tryLock(), "second acquisition from the same process must succeed");
                assertTrue(mutex.isHeld(), "must still be held");
                mutex.unlock();
                assertTrue(mutex.isHeld(), "one level remains");
                mutex.unlock();
                assertFalse(mutex.isHeld(), "all levels released");
            } finally {
                mutex.close();
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("lock() returns immediately when free and times out when held elsewhere", () -> {
            Path temp = TestSupport.tempDir("lock-timeout");
            Path lockFile = temp.resolve("lock");
            FileMutex mutex = FileMutex.forFile(lockFile);
            try {
                long start = System.nanoTime();
                mutex.lock(1_000);
                long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
                assertTrue(elapsedMillis < 500, "a free lock must be taken without polling delay");
                mutex.unlock();
            } finally {
                mutex.close();
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("the lock file is private and carries the owner", () -> {
            TestSupport.requireNonRoot();
            Path temp = TestSupport.tempDir("lock-file");
            Path lockFile = temp.resolve("state").resolve("lock");
            FileMutex mutex = FileMutex.forFile(lockFile);
            try {
                assertTrue(mutex.tryLock(), "acquisition must succeed");
                assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                        Files.getPosixFilePermissions(lockFile), "lock file mode");
                String owner = FileMutex.ownerDescription(lockFile).orElseThrow();
                Assertions.assertContains(owner, "pid=", "the owner description must name the pid");
                Assertions.assertContains(owner, String.valueOf(ProcessHandle.current().pid()),
                        "the pid must be this process");
            } finally {
                mutex.close();
                TestSupport.deleteRecursively(temp);
            }
        });

        runner.run("close releases the lock and forgets the shared instance", () -> {
            Path temp = TestSupport.tempDir("lock-close");
            Path lockFile = temp.resolve("lock");
            FileMutex first = FileMutex.forFile(lockFile);
            try {
                assertTrue(first.tryLock(), "acquisition must succeed");
                first.close();
                assertFalse(first.isHeld(), "close must release everything");
                FileMutex second = FileMutex.forFile(lockFile);
                assertNotEquals(first, second, "a closed mutex must not stay in the shared table");
                assertTrue(second.tryLock(), "the lock must be takeable again");
                second.close();
            } finally {
                first.close();
                TestSupport.deleteRecursively(temp);
            }
        });
    }
}
