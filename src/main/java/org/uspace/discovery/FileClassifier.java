package org.uspace.discovery;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.uspace.core.model.AppType;

/**
 * Recognises an application from what is on disk, without deciding anything about registration.
 *
 * <p>The rules are deliberately few and evidence-based, because a scanner that guesses produces a
 * review list nobody trusts:
 *
 * <ul>
 *   <li><b>AppImage</b> — an ELF file with the AppImage magic ({@code AI\x02} at offset 8). A file
 *       merely named {@code *.AppImage} is still proposed, but at medium confidence and saying why,
 *       since a truncated download is a real thing users have.</li>
 *   <li><b>ELF binary</b> — the {@code \x7fELF} header and the executable bit. An ELF without the
 *       bit is a shared library far more often than a broken program, so it is ignored rather than
 *       reported; reporting every {@code .so} would drown the real issues.</li>
 *   <li><b>Extracted tree</b> — a directory containing {@code AppRun}, the one file the AppDir
 *       convention guarantees. The directory becomes the install root, and the walk does not descend
 *       into it: its contents are the application, not more applications.</li>
 *   <li><b>Runnable jar</b> — a jar whose manifest has a {@code Main-Class}. A jar without one is
 *       reported as an issue, because "why wasn't my jar found" is otherwise unanswerable.</li>
 *   <li><b>{@code .desktop} file</b> — imported only when {@code Exec} is a single unquoted absolute
 *       path. Anything needing quoting, field codes or shell syntax is reported instead of being
 *       reinvented: guessing there is how launchers end up starting the wrong program.</li>
 * </ul>
 *
 * <p>Nothing here checks whether a candidate collides with an existing registration: that needs the
 * registry, which this package must not depend on.
 */
final class FileClassifier {

    /** Enough for the ELF header and the AppImage magic at offset 8. */
    private static final int HEAD_BYTES = 16;

    private static final byte[] ELF_MAGIC = {0x7f, 'E', 'L', 'F'};
    private static final int APPIMAGE_MAGIC_OFFSET = 8;
    private static final byte APPIMAGE_MAGIC_A = 'A';
    private static final byte APPIMAGE_MAGIC_I = 'I';

    private static final String APPIMAGE_SUFFIX = ".appimage";
    private static final String JAR_SUFFIX = ".jar";
    private static final String DESKTOP_SUFFIX = ".desktop";
    private static final String APPRUN = "AppRun";

    private final Path javaExecutable;

    FileClassifier(Path javaExecutable) {
        this.javaExecutable = Objects.requireNonNull(javaExecutable, "javaExecutable");
        if (!javaExecutable.isAbsolute()) {
            throw new IllegalArgumentException("java executable must be absolute: " + javaExecutable);
        }
    }

    /** Classifies one regular file; unreadable or unusable files become issues, never exceptions. */
    Optional<Proposal> classifyFile(Path file, Consumer<DiscoveryIssue> issues) {
        String name = file.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        try {
            if (lower.endsWith(DESKTOP_SUFFIX)) {
                return classifyDesktopEntry(file, issues);
            }
            if (lower.endsWith(JAR_SUFFIX)) {
                return classifyJar(file, issues);
            }
            if (!Files.isExecutable(file)) {
                return Optional.empty();
            }
            return classifyExecutable(file, name, lower);
        } catch (NotAnApplicationException e) {
            // The name promised something the bytes do not support; that is worth telling the user.
            issues.accept(new DiscoveryIssue(file, e.getMessage()));
            return Optional.empty();
        } catch (AccessDeniedException e) {
            issues.accept(new DiscoveryIssue(file, "permission denied"));
            return Optional.empty();
        } catch (NoSuchFileException e) {
            issues.accept(new DiscoveryIssue(file, "disappeared while being scanned"));
            return Optional.empty();
        } catch (IOException e) {
            issues.accept(new DiscoveryIssue(file, "cannot be read: " + describe(e)));
            return Optional.empty();
        }
    }

    /** Classifies a directory as an extracted application tree, if {@code AppRun} is in it. */
    Optional<Proposal> classifyTree(Path dir, Consumer<DiscoveryIssue> issues) {
        Path appRun = dir.resolve(APPRUN);
        if (!Files.exists(appRun)) {
            return Optional.empty();
        }
        String name = dir.getFileName() == null ? APPRUN : dir.getFileName().toString();
        Path icon = Files.exists(dir.resolve(".DirIcon")) ? dir.resolve(".DirIcon") : null;
        if (!Files.isExecutable(appRun)) {
            issues.accept(new DiscoveryIssue(appRun,
                    "AppRun is not executable, so the tree is proposed but will not start as-is"));
            return Optional.of(new Proposal(AppType.PORTABLE, appRun, List.of(), dir, dir, icon, name,
                    Confidence.MEDIUM, "extracted application tree (AppRun present but not executable)"));
        }
        return Optional.of(new Proposal(AppType.PORTABLE, appRun, List.of(), dir, dir, icon, name,
                Confidence.HIGH, "extracted application tree (AppRun)"));
    }

    private Optional<Proposal> classifyExecutable(Path file, String name, String lower) throws IOException {
        boolean appImageSuffix = lower.endsWith(APPIMAGE_SUFFIX);
        byte[] head = readHead(file);
        if (!hasMagic(head, ELF_MAGIC, 0)) {
            if (appImageSuffix) {
                // Executable but not ELF: a shell-based AppImage needs --appimage-extract-and-run,
                // and calling it an application would produce a launcher that fails at click time.
                throw new NotAnApplicationException("named " + name + " but it is not an ELF file");
            }
            return Optional.empty();
        }
        String displayName = appImageSuffix ? stripSuffix(name, APPIMAGE_SUFFIX) : name;
        if (hasAppImageMagic(head)) {
            return Optional.of(new Proposal(AppType.APPIMAGE, file, List.of(), null, null, null, displayName,
                    Confidence.HIGH, "AppImage (ELF file carrying the AppImage magic)"));
        }
        if (appImageSuffix) {
            return Optional.of(new Proposal(AppType.APPIMAGE, file, List.of(), null, null, null, displayName,
                    Confidence.MEDIUM,
                    "named " + name + " but the AppImage magic is missing (probably a truncated download)"));
        }
        return Optional.of(new Proposal(AppType.BINARY, file, List.of(), null, null, null, displayName,
                Confidence.HIGH, "ELF executable"));
    }

    private Optional<Proposal> classifyJar(Path file, Consumer<DiscoveryIssue> issues) {
        String mainClass;
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry entry = zip.getEntry("META-INF/MANIFEST.MF");
            if (entry == null) {
                issues.accept(new DiscoveryIssue(file, "jar has no META-INF/MANIFEST.MF; not an application"));
                return Optional.empty();
            }
            Manifest manifest;
            try (InputStream in = zip.getInputStream(entry)) {
                manifest = new Manifest(in);
            }
            mainClass = manifest.getMainAttributes().getValue("Main-Class");
        } catch (IOException e) {
            issues.accept(new DiscoveryIssue(file, "not a readable jar: " + describe(e)));
            return Optional.empty();
        }
        if (mainClass == null || mainClass.isBlank()) {
            issues.accept(new DiscoveryIssue(file, "jar has no Main-Class, so it cannot be started on its own"));
            return Optional.empty();
        }

        // A jar alone in its directory can own that directory; one among other files cannot, or
        // removing the application would take the user's other files with it.
        boolean alone = isAloneInDirectory(file);
        AppType type = alone ? AppType.JAVA_APP : AppType.CUSTOM;
        Path installRoot = alone ? file.getParent() : null;
        String displayName = stripSuffix(file.getFileName().toString(), JAR_SUFFIX);
        String reason = "runnable jar (Main-Class " + mainClass.trim() + ")"
                + (alone ? "" : "; its directory holds other files, so the directory is not claimed");
        return Optional.of(new Proposal(type, javaExecutable, List.of("-jar", file.toString()), null,
                installRoot, null, displayName, Confidence.HIGH, reason));
    }

    private Optional<Proposal> classifyDesktopEntry(Path file, Consumer<DiscoveryIssue> issues) {
        DesktopEntry entry;
        try {
            entry = DesktopEntry.parse(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            issues.accept(new DiscoveryIssue(file, "cannot be read: " + describe(e)));
            return Optional.empty();
        }
        if (entry.hidden() || entry.noDisplay()) {
            return Optional.empty();
        }
        if (entry.type() != null && !entry.type().equalsIgnoreCase("Application")) {
            return Optional.empty();
        }
        if (entry.exec() == null || entry.exec().isBlank()) {
            return Optional.empty();
        }
        if (!isSimpleExec(entry.exec())) {
            issues.accept(new DiscoveryIssue(file, "Exec \"" + entry.exec()
                    + "\" uses quoting, field codes or shell syntax; not imported automatically"));
            return Optional.empty();
        }
        Path target = Path.of(entry.exec());
        if (!target.isAbsolute()) {
            issues.accept(new DiscoveryIssue(file, "Exec is not an absolute path: \"" + entry.exec() + "\""));
            return Optional.empty();
        }
        if (!Files.exists(target)) {
            issues.accept(new DiscoveryIssue(file, "its Exec target does not exist: " + target));
        }
        Path icon = entry.icon() != null && Path.of(entry.icon()).isAbsolute() ? Path.of(entry.icon()) : null;
        String displayName = entry.name() == null || entry.name().isBlank()
                ? stripSuffix(file.getFileName().toString(), DESKTOP_SUFFIX)
                : entry.name();
        return Optional.of(new Proposal(AppType.CUSTOM, target, List.of(), null, null, icon, displayName,
                Confidence.LOW, "imported from " + file.getFileName()
                        + "; arguments, working directory and environment were not reproduced"));
    }

    /**
     * True only for one unquoted absolute path with no field code. Everything else is left to the
     * user: the {@code Exec} grammar is a quoting minefield and a wrong guess starts the wrong program.
     */
    private static boolean isSimpleExec(String exec) {
        String value = exec.strip();
        if (value.isEmpty() || value.startsWith("\"") || value.contains("'")) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ' ' || c == '\t' || c == '\\' || c == '%' || c == '$' || c == '`') {
                return false;
            }
        }
        return true;
    }

    private static boolean isAloneInDirectory(Path file) {
        Path parent = file.getParent();
        if (parent == null) {
            return false;
        }
        try (var entries = Files.list(parent)) {
            return entries.count() == 1;
        } catch (IOException e) {
            return false;
        }
    }

    private static byte[] readHead(Path file) throws IOException {
        byte[] head = new byte[HEAD_BYTES];
        int read;
        try (InputStream in = Files.newInputStream(file)) {
            read = in.readNBytes(head, 0, HEAD_BYTES);
        }
        if (read < HEAD_BYTES) {
            return java.util.Arrays.copyOf(head, Math.max(read, 0));
        }
        return head;
    }

    private static boolean hasMagic(byte[] data, byte[] magic, int offset) {
        if (data.length < offset + magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (data[offset + i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasAppImageMagic(byte[] data) {
        return data.length >= APPIMAGE_MAGIC_OFFSET + 3
                && data[APPIMAGE_MAGIC_OFFSET] == APPIMAGE_MAGIC_A
                && data[APPIMAGE_MAGIC_OFFSET + 1] == APPIMAGE_MAGIC_I;
    }

    private static String stripSuffix(String name, String suffix) {
        return name.length() > suffix.length() && name.toLowerCase(Locale.ROOT).endsWith(suffix)
                ? name.substring(0, name.length() - suffix.length())
                : name;
    }

    private static String describe(IOException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /** A file whose name promises one thing and whose bytes say another; reported, not proposed. */
    static final class NotAnApplicationException extends IOException {
        private static final long serialVersionUID = 1L;

        NotAnApplicationException(String message) {
            super(message);
        }
    }

    /**
     * The four keys discovery needs from a {@code .desktop} file.
     *
     * <p>Hand-parsed rather than reusing the launcher writer from M4: that class will <em>emit</em>
     * properly escaped entries, and a parser that shares code with the emitter happily accepts what
     * the emitter produces and nothing else. Discovery has to read what other programs wrote.
     */
    record DesktopEntry(String type, String name, String exec, String icon, boolean noDisplay, boolean hidden) {

        static DesktopEntry parse(List<String> lines) {
            String type = null;
            String name = null;
            String exec = null;
            String icon = null;
            boolean noDisplay = false;
            boolean hidden = false;
            boolean inMainGroup = false;
            for (String raw : lines) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    inMainGroup = line.equals("[Desktop Entry]");
                    continue;
                }
                if (!inMainGroup) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).strip();
                String value = line.substring(eq + 1).strip();
                switch (key) {
                    case "Type" -> type = value;
                    case "Name" -> name = value;
                    case "Exec" -> exec = value;
                    case "Icon" -> icon = value;
                    case "NoDisplay" -> noDisplay = value.equalsIgnoreCase("true");
                    case "Hidden" -> hidden = value.equalsIgnoreCase("true");
                    default -> {
                        // Unknown keys, including localized Name[xx] entries, are ignored.
                    }
                }
            }
            return new DesktopEntry(type, name, exec, icon, noDisplay, hidden);
        }
    }
}
