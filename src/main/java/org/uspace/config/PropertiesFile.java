package org.uspace.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * The only place that reads and writes property files.
 *
 * <p>Three decisions are encoded here, and every caller inherits them:
 *
 * <ul>
 *   <li><b>UTF-8 {@code Reader}/{@code Writer}, never {@code InputStream}.</b> {@code
 *       Properties.load(InputStream)} decodes ISO-8859-1 and {@code store(OutputStream)} writes
 *       non-Latin-1 characters as unicode escapes, which produces unreadable files. Pairing
 *       {@code store(Writer)} with {@code load(Reader)} keeps accented names and CJK text literal and
 *       round-trips them exactly.</li>
 *   <li><b>Write to a temp file in the same directory, then rename.</b> A crash mid-write can then
 *       only ever lose the new content, never the old file. Temp files are created {@code 0600} so
 *       the final file inherits that mode without a window where it is world-readable.</li>
 *   <li><b>Atomic rename with a real fallback.</b> {@code ATOMIC_MOVE} is not supported on every
 *       filesystem (network homes, some FUSE mounts); the fallback is a plain {@code REPLACE_EXISTING}
 *       move, which is still a rename on the same filesystem but is not guaranteed to be atomic.</li>
 * </ul>
 */
public final class PropertiesFile {

    private static final boolean POSIX =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    private static final Set<PosixFilePermission> FILE_MODE =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> DIRECTORY_MODE =
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);
    private static final FileAttribute<?>[] NO_ATTRIBUTES = new FileAttribute<?>[0];

    private PropertiesFile() {
    }

    /** Reads {@code file} as UTF-8 properties. */
    public static Properties read(Path file) throws ConfigException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Properties properties = new Properties();
            properties.load(reader);
            return properties;
        } catch (NoSuchFileException e) {
            throw new ConfigException("missing file: " + file, e);
        } catch (IOException | IllegalArgumentException e) {
            // Properties.load throws IllegalArgumentException for a malformed unicode escape.
            throw new ConfigException("cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * Reads {@code file} if it exists. Used to carry unknown keys across an update: the file is
     * loaded, the known keys are overwritten, and everything else is written back untouched.
     */
    public static Optional<Properties> readIfExists(Path file) throws ConfigException {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        return Optional.of(read(file));
    }

    /**
     * Replaces {@code file} with {@code properties}, durably and (where the filesystem allows it)
     * atomically. The parent directory is created with mode {@code 0700} if missing; an existing
     * directory keeps its own mode, because this tool does not tighten directories it did not create.
     *
     * @param comment written as the file header; pass a constant rather than a timestamp so that
     *                rewriting identical data produces an identical file
     */
    public static void write(Path file, Properties properties, String comment) throws ConfigException {
        Path target = file.toAbsolutePath();
        Path directory = target.getParent();
        if (directory == null) {
            throw new ConfigException("target has no parent directory: " + file);
        }
        Path temp = null;
        try {
            Files.createDirectories(directory, directoryAttributes());
            temp = Files.createTempFile(directory, "." + target.getFileName(), ".tmp", fileAttributes());
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                properties.store(writer, comment);
            }
            forceToDisk(temp);
            moveReplacing(temp, target);
            temp = null;
        } catch (IOException e) {
            throw new ConfigException("cannot write " + target + ": " + e, e);
        } finally {
            deleteQuietly(temp);
        }
    }

    /** Atomic rename when the filesystem supports it, plain replacing move otherwise. */
    static void moveReplacing(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            moveReplacingWithoutAtomicMove(temp, target);
        }
    }

    /** The fallback path, reachable directly so its behaviour can be tested on a filesystem that does support atomic moves. */
    static void moveReplacingWithoutAtomicMove(Path temp, Path target) throws IOException {
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void forceToDisk(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static FileAttribute<?>[] fileAttributes() {
        return POSIX ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(FILE_MODE)} : NO_ATTRIBUTES;
    }

    private static FileAttribute<?>[] directoryAttributes() {
        return POSIX ? new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(DIRECTORY_MODE)} : NO_ATTRIBUTES;
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // The temp file is in a directory we own; a leftover is harmless and never trusted.
        }
    }
}
