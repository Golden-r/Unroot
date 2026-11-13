package org.uspace.core.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Everything needed to start an application: one executable, its fixed arguments, and the directory
 * to start it from.
 *
 * <p>Paths are absolute and normalized at construction so that equality is meaningful and so that a
 * relative path can never be silently reinterpreted against a different working directory at launch
 * time. Existence and executability are deliberately <em>not</em> checked here: construction must
 * stay free of IO so the UI can build candidates optimistically; probing belongs to {@code system}
 * and is surfaced through {@code Health}.
 *
 * @param executable       absolute path to the file to execute
 * @param arguments        fixed arguments; user-supplied arguments are appended at launch
 * @param workingDirectory directory to start in, or {@code null} for the executable's parent
 */
public record LaunchCommand(Path executable, List<String> arguments, Path workingDirectory) {

    public LaunchCommand {
        Objects.requireNonNull(executable, "executable");
        if (!executable.isAbsolute()) {
            throw new IllegalArgumentException("executable must be absolute: " + executable);
        }
        executable = executable.normalize();
        arguments = List.copyOf(arguments);
        if (workingDirectory != null) {
            if (!workingDirectory.isAbsolute()) {
                throw new IllegalArgumentException("working directory must be absolute: " + workingDirectory);
            }
            workingDirectory = workingDirectory.normalize();
        }
    }

    public static LaunchCommand of(Path executable) {
        return new LaunchCommand(executable, List.of(), null);
    }

    /** Working directory actually used, derived when unset. */
    public Path effectiveWorkingDirectory() {
        return workingDirectory != null ? workingDirectory : executable.getParent();
    }
}
