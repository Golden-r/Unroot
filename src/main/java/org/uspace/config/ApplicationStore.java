package org.uspace.config;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;
import org.uspace.core.model.Application;
import org.uspace.core.model.ApplicationId;

/**
 * Persists one {@link Application} per directory: {@code <appsDir>/<id>/meta.properties}.
 *
 * <p>One file per application instead of a single registry file is the load-bearing decision here:
 * an update rewrites only that application's file, so a corrupt or half-written file costs one
 * registration instead of all of them, and two processes touching different applications do not
 * clobber each other.
 *
 * <p>This class is deliberately unaware of duplicates and of the in-memory index — those are
 * registry concerns. It only converts between directories and model objects, and reports every
 * directory it could not read instead of skipping it quietly.
 */
public final class ApplicationStore {

    /** Name of the metadata file inside each application directory. */
    public static final String METADATA_FILE = "meta.properties";

    private final Path appsDir;

    public ApplicationStore(Path appsDir) {
        this.appsDir = Objects.requireNonNull(appsDir, "appsDir").toAbsolutePath().normalize();
    }

    public Path appsDir() {
        return appsDir;
    }

    /** Directory holding everything about one application. */
    public Path directoryFor(ApplicationId id) {
        return appsDir.resolve(id.value());
    }

    public Path metadataFile(ApplicationId id) {
        return directoryFor(id).resolve(METADATA_FILE);
    }

    /**
     * Reads every application directory.
     *
     * @throws ConfigException only when the applications directory itself cannot be listed; a single
     *                         unreadable or malformed entry becomes a {@link MetadataIssue}
     */
    public LoadReport loadAll() throws ConfigException {
        if (!Files.isDirectory(appsDir)) {
            return LoadReport.empty();
        }
        List<Path> directories;
        try (Stream<Path> entries = Files.list(appsDir)) {
            directories = entries.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            throw new ConfigException("cannot list " + appsDir + ": " + e.getMessage(), e);
        }

        List<Application> applications = new ArrayList<>(directories.size());
        List<MetadataIssue> issues = new ArrayList<>();
        for (Path directory : directories) {
            loadOne(directory, applications, issues);
        }
        return new LoadReport(applications, issues);
    }

    private void loadOne(Path directory, List<Application> applications, List<MetadataIssue> issues) {
        String name = directory.getFileName().toString();
        ApplicationId directoryId;
        try {
            directoryId = new ApplicationId(name);
        } catch (IllegalArgumentException e) {
            issues.add(new MetadataIssue(directory, "directory name is not a valid application id"));
            return;
        }

        Path file = directory.resolve(METADATA_FILE);
        if (!Files.isRegularFile(file)) {
            issues.add(new MetadataIssue(directory, "missing " + METADATA_FILE));
            return;
        }

        Properties properties;
        try {
            properties = PropertiesFile.read(file);
        } catch (ConfigException e) {
            issues.add(new MetadataIssue(file, e.getMessage()));
            return;
        }

        Application application;
        try {
            application = ApplicationCodec.decode(properties);
        } catch (ConfigException e) {
            issues.add(new MetadataIssue(file, e.getMessage()));
            return;
        }

        if (!application.id().equals(directoryId)) {
            issues.add(new MetadataIssue(file,
                    "id '" + application.id() + "' does not match directory '" + name + "'"));
            return;
        }
        applications.add(application);
    }

    /**
     * Writes the application, preserving properties in the existing file that this version does not
     * understand, and creating the directory with mode {@code 0700} if needed.
     */
    public void save(Application application) throws ConfigException {
        Objects.requireNonNull(application, "application");
        Path file = metadataFile(application.id());
        Properties existing = PropertiesFile.readIfExists(file).orElseGet(Properties::new);
        Properties previous = new Properties();
        previous.putAll(existing);

        Properties encoded = ApplicationCodec.encode(application, existing);
        if (previous.equals(encoded)) {
            // Nothing changed. Rewriting would only churn the file: Properties.store always emits a
            // date comment, so identical data would still produce different bytes and a new mtime.
            return;
        }
        PropertiesFile.write(file, encoded, ApplicationCodec.COMMENT);
    }

    /**
     * Removes the registration: the metadata file, then the directory if that left it empty.
     *
     * <p>Files the user or a future integration put in the directory are never deleted, which is why
     * the directory removal is conditional rather than recursive.
     *
     * @return {@code true} if a metadata file was removed
     */
    public boolean delete(ApplicationId id) throws ConfigException {
        Path directory = directoryFor(id);
        Path file = directory.resolve(METADATA_FILE);
        boolean existed;
        try {
            existed = Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new ConfigException("cannot remove " + file + ": " + e.getMessage(), e);
        }
        try {
            Files.deleteIfExists(directory);
        } catch (DirectoryNotEmptyException ignored) {
            // Still holds something we did not create; leave it.
        } catch (IOException e) {
            throw new ConfigException("cannot remove " + directory + ": " + e.getMessage(), e);
        }
        return existed;
    }

    /** Convenience for diagnostics. */
    public Optional<Application> load(ApplicationId id) throws ConfigException {
        Path file = metadataFile(id);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(ApplicationCodec.decode(PropertiesFile.read(file)));
    }
}
