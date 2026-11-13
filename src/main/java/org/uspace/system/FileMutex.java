package org.uspace.system;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * An exclusive lock on a file, shared by every thread and process that wants to write the same data.
 *
 * <p>Why an OS file lock rather than a PID file or a sentinel directory: the kernel releases it when
 * the process dies, so a crash can never leave a stale lock that requires manual cleanup. The lock
 * file itself is permanent and its content is informational only — it says which pid holds it, never
 * <em>whether</em> the lock is held.
 *
 * <p>Two properties matter and are both tested:
 * <ul>
 *   <li><b>Re-entrant within the process.</b> Instances are shared per path, so a component that
 *       already holds the lock for its lifetime (the GUI instance) can call {@link #lock} from a
 *       worker thread without deadlocking against itself or throwing
 *       {@link OverlappingFileLockException}.</li>
 *   <li><b>Exclusive across processes.</b> {@link #tryLock()} returns {@code false} rather than
 *       waiting, so a second instance can report "someone else is writing" instead of hanging.</li>
 * </ul>
 *
 * <p>Blocking acquisition polls {@code tryLock} instead of calling {@code lock()}: a blocking
 * {@code FileLock} cannot be interrupted, so a timed wait is the only way to fail with a message.
 */
public final class FileMutex implements AutoCloseable {

    /** Thrown when the lock cannot be acquired within the caller's budget. */
    public static final class LockBusyException extends IOException {
        private static final long serialVersionUID = 1L;

        LockBusyException(String message) {
            super(message);
        }
    }

    private static final long POLL_MILLIS = 25;
    private static final Map<Path, FileMutex> SHARED = new HashMap<>();
    private static final FileAttribute<Set<PosixFilePermission>> LOCK_FILE_ATTRIBUTES = lockAttributes();
    private static final FileAttribute<Set<PosixFilePermission>> LOCK_DIRECTORY_ATTRIBUTES = directoryAttributes();

    private final Path file;
    private FileChannel channel;
    private FileLock lock;
    private int holdCount;

    private FileMutex(Path file) {
        this.file = file;
    }

    /**
     * The single mutex instance for {@code lockFile} in this process.
     *
     * <p>Sharing by path is what makes re-entry work: two objects holding two channels on the same
     * file would collide in {@link OverlappingFileLockException} even in the same thread.
     */
    public static synchronized FileMutex forFile(Path lockFile) {
        Path key = Objects.requireNonNull(lockFile, "lockFile").toAbsolutePath().normalize();
        return SHARED.computeIfAbsent(key, FileMutex::new);
    }

    public Path file() {
        return file;
    }

    /** True when this process holds the lock (possibly several levels deep). */
    public synchronized boolean isHeld() {
        return holdCount > 0;
    }

    /**
     * Acquires the lock without waiting.
     *
     * @return true if this process holds it afterwards — including when it already did
     * @throws IOException if the lock file cannot be opened
     */
    public synchronized boolean tryLock() throws IOException {
        if (holdCount > 0) {
            holdCount++;
            return true;
        }
        if (acquireNow()) {
            holdCount = 1;
            return true;
        }
        return false;
    }

    /**
     * Acquires the lock, waiting up to {@code timeoutMillis}.
     *
     * @throws LockBusyException when another process keeps it longer than the budget, or the thread
     *                           is interrupted while waiting
     */
    public synchronized void lock(long timeoutMillis) throws IOException {
        if (holdCount > 0) {
            holdCount++;
            return;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));
        while (true) {
            if (acquireNow()) {
                holdCount = 1;
                return;
            }
            if (System.nanoTime() >= deadline) {
                throw new LockBusyException("another program is using UserSpace's data (" + file + ")");
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LockBusyException("interrupted while waiting for " + file);
            }
        }
    }

    /** Releases one level of the hold; the OS lock is dropped when the count reaches zero. */
    public synchronized void unlock() {
        if (holdCount == 0) {
            return;
        }
        holdCount--;
        if (holdCount == 0) {
            releaseNow();
        }
    }

    /** Releases every level and forgets the shared instance. Used at shutdown and in tests. */
    @Override
    public synchronized void close() {
        holdCount = 0;
        releaseNow();
        synchronized (FileMutex.class) {
            SHARED.remove(file, this);
        }
    }

    /** Best-effort description of the current holder, for "already running" messages. */
    public static Optional<String> ownerDescription(Path lockFile) {
        try {
            String content = Files.readString(lockFile, StandardCharsets.UTF_8).trim();
            return content.isEmpty() ? Optional.empty() : Optional.of(content.replace('\n', ' '));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private boolean acquireNow() throws IOException {
        if (channel == null) {
            channel = open();
        }
        try {
            FileLock candidate = channel.tryLock();
            if (candidate == null) {
                return false;
            }
            lock = candidate;
            recordOwner();
            return true;
        } catch (OverlappingFileLockException e) {
            // Another FileChannel in this JVM holds it. Only reachable if the shared-instance
            // invariant is broken; treated as busy so callers still get a clean answer.
            return false;
        }
    }

    private FileChannel open() throws IOException {
        Path parent = file.getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            Files.createDirectories(parent, asArray(LOCK_DIRECTORY_ATTRIBUTES));
        }
        return FileChannel.open(file,
                Set.of(StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE),
                asArray(LOCK_FILE_ATTRIBUTES));
    }

    /** Informational only: whoever reads it must still test the lock, never trust the file. */
    private void recordOwner() {
        String owner = "pid=" + ProcessHandle.current().pid() + "\nsince=" + Instant.now() + "\n";
        try {
            channel.truncate(0);
            channel.write(ByteBuffer.wrap(owner.getBytes(StandardCharsets.UTF_8)), 0);
            channel.force(false);
        } catch (IOException ignored) {
            // Diagnostics only; failing to write it must never fail the acquisition.
        }
    }

    private void releaseNow() {
        if (lock != null) {
            try {
                lock.release();
            } catch (IOException ignored) {
                // Closing the channel below releases it as well.
            }
            lock = null;
        }
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // Nothing useful to do; the OS closes the descriptor at exit.
            }
            channel = null;
        }
    }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private static FileAttribute<Set<PosixFilePermission>> lockAttributes() {
        return PosixFilePermissions.asFileAttribute(Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    }

    private static FileAttribute<Set<PosixFilePermission>> directoryAttributes() {
        return PosixFilePermissions.asFileAttribute(Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
    }

    /**
     * Attribute arrays cannot be {@code null}, so on a non-POSIX filesystem only the default
     * permissions are used. Linux targets are POSIX; this exists so the class does not throw on an
     * exotic mount.
     */
    private static FileAttribute<?>[] asArray(FileAttribute<Set<PosixFilePermission>> attribute) {
        return posix() ? new FileAttribute<?>[] {attribute} : new FileAttribute<?>[0];
    }
}
