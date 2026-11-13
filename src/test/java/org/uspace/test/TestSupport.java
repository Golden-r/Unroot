package org.uspace.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Temporary directories and environment helpers shared by the test classes. */
public final class TestSupport {

    /** Thrown to mark a case as not applicable in the current environment. */
    public static final class Skip extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Skip(String reason) {
            super(reason);
        }
    }

    private TestSupport() {
    }

    /** Creates a temporary directory that the caller must delete with {@link #deleteRecursively}. */
    public static Path tempDir(String prefix) throws IOException {
        return Files.createTempDirectory("uspace-" + prefix + "-");
    }

    public static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> sorted = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path path : sorted) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort: a leftover temp directory never fails a test run.
                }
            }
        } catch (IOException ignored) {
            // Same.
        }
    }

    /** Permission checks are meaningless as root; such cases are skipped rather than faked. */
    public static void requireNonRoot() {
        if ("root".equals(System.getProperty("user.name"))) {
            throw new Skip("running as root: filesystem permission failures cannot be provoked");
        }
    }

    public static boolean isRoot() {
        return "root".equals(System.getProperty("user.name"));
    }
}
