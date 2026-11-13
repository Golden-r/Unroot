package org.uspace.core.model;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A registered application: the user's metadata about one installable program.
 *
 * <p>This type is pure data. It never reads the filesystem, never knows where artifacts were
 * generated, and carries no UI state. Generated integration files are tracked separately (see
 * {@code integration.properties} and {@code core.registry}), which is what makes "remove only what
 * we created" possible.
 *
 * @param id           stable, filename-safe identity
 * @param name         display name, freely editable
 * @param description  one-line description for the launcher, may be empty
 * @param type         packaging kind, drives launch/validation/removal rules
 * @param launch       executable, fixed arguments and working directory
 * @param installRoot  directory owned by the application, or {@code null} for bare executables
 * @param icon         icon file to copy into the launcher, or {@code null}
 * @param environment  variables applied by the generated launcher, in order
 * @param registeredAt when the user registered it
 * @param origin       provenance for the UI, e.g. {@code discovered:/opt/apps} or {@code manual}
 */
public record Application(
        ApplicationId id,
        String name,
        String description,
        AppType type,
        LaunchCommand launch,
        Path installRoot,
        Path icon,
        List<EnvVariable> environment,
        Instant registeredAt,
        String origin) {

    public Application {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(launch, "launch");
        Objects.requireNonNull(registeredAt, "registeredAt");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        description = description == null ? "" : description;
        origin = origin == null ? "manual" : origin;
        environment = List.copyOf(environment);
        if (installRoot != null) {
            if (!installRoot.isAbsolute()) {
                throw new IllegalArgumentException("install root must be absolute: " + installRoot);
            }
            installRoot = installRoot.normalize();
        } else if (type.ownsInstallRoot()) {
            throw new IllegalArgumentException(type + " requires an install root");
        }
        if (icon != null) {
            if (!icon.isAbsolute()) {
                throw new IllegalArgumentException("icon must be absolute: " + icon);
            }
            icon = icon.normalize();
        }
    }

    public boolean hasIcon() {
        return icon != null;
    }

    public boolean ownsInstallRoot() {
        return installRoot != null;
    }

    /** Same identity, different user-editable metadata. */
    public Application withMetadata(String newName, String newDescription) {
        return new Application(id, newName, newDescription, type, launch, installRoot, icon,
                environment, registeredAt, origin);
    }

    /** Environment variables in {@code NAME=VALUE} form, for wrappers and persistence. */
    public List<String> environmentAssignments() {
        return environment.stream().map(EnvVariable::assignment).toList();
    }
}
