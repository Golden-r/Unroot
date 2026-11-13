package org.uspace.core.registry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.uspace.config.ApplicationStore;
import org.uspace.config.ConfigException;
import org.uspace.config.LoadReport;
import org.uspace.config.MetadataIssue;
import org.uspace.core.model.Application;
import org.uspace.core.model.ApplicationId;
import org.uspace.system.FileMutex;

/**
 * The authoritative in-memory view of what is registered, backed by {@link ApplicationStore}.
 *
 * <p>Ordering rule that keeps memory honest: <b>persist first, then mutate memory.</b> A failed
 * write therefore leaves the registry exactly as it was, instead of showing the user an application
 * that will be gone after a restart.
 *
 * <p>Two duplicates are rejected: the same id, and the same executable. Executable identity is the
 * real path, so a symlink to an already registered binary is caught; a copy of the same file at a
 * different path is not, and pretending otherwise would need content hashing (a discovery concern,
 * not a registry one).
 *
 * <p>Loading is best-effort: unreadable or malformed directories become {@link MetadataIssue}s that
 * the UI can display, and never abort the load.
 *
 * <p>Every mutation runs inside a {@link FileMutex}, so two processes cannot interleave a
 * read-modify-write of the same registration. Reads take no lock: each metadata file is replaced by
 * an atomic rename, so a reader sees either the old or the new content, never a half-written one.
 * Duplicate detection is repeated on disk inside the critical section for ids, because a registry
 * loaded minutes ago cannot know what another process registered since.
 */
public final class ApplicationRegistry {

    /** How long a mutation waits for another process before reporting a conflict. */
    private static final long MUTATION_TIMEOUT_MILLIS = 5_000;

    private final ApplicationStore store;
    private final FileMutex mutex;
    private final Map<ApplicationId, Application> byId = new LinkedHashMap<>();
    private final Map<Path, ApplicationId> byExecutable = new HashMap<>();
    private final List<MetadataIssue> loadIssues = new ArrayList<>();

    /**
     * Loads every registration from {@code store}.
     *
     * @throws ConfigException only if the applications directory cannot be listed at all
     */
    public ApplicationRegistry(ApplicationStore store, FileMutex mutex) throws ConfigException {
        this.store = Objects.requireNonNull(store, "store");
        this.mutex = Objects.requireNonNull(mutex, "mutex");
        LoadReport report = store.loadAll();
        loadIssues.addAll(report.issues());
        for (Application application : report.applications()) {
            ApplicationId other = byExecutable.putIfAbsent(identity(application.launch().executable()), application.id());
            if (other != null && !other.equals(application.id())) {
                loadIssues.add(new MetadataIssue(store.metadataFile(application.id()),
                        "executable is also registered as '" + other + "'"));
            }
            byId.put(application.id(), application);
        }
    }

    public ApplicationStore store() {
        return store;
    }

    public FileMutex mutex() {
        return mutex;
    }

    /** Problems found while loading; empty after a clean load. */
    public List<MetadataIssue> loadIssues() {
        return List.copyOf(loadIssues);
    }

    public Optional<Application> find(ApplicationId id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** All registrations, ordered by display name so the table does not jump between refreshes. */
    public List<Application> all() {
        return byId.values().stream()
                .sorted(Comparator.comparing(Application::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(application -> application.id().value()))
                .toList();
    }

    public int size() {
        return byId.size();
    }

    /** Registers a new application, or explains why it cannot be registered. */
    public RegistryResult register(Application application) {
        Objects.requireNonNull(application, "application");
        ApplicationId id = application.id();
        if (byId.containsKey(id)) {
            return RegistryResult.of(RegistryResult.Outcome.DUPLICATE_ID, id,
                    "another application is already registered as '" + id + "'");
        }
        ApplicationId other = byExecutable.get(identity(application.launch().executable()));
        if (other != null) {
            return RegistryResult.of(RegistryResult.Outcome.DUPLICATE_EXECUTABLE, id,
                    "that executable is already registered as '" + other + "'");
        }
        Optional<RegistryResult> blocked = lockForWrite(id);
        if (blocked.isPresent()) {
            return blocked.get();
        }
        try {
            // Re-check on disk inside the critical section: this registry may have been loaded long
            // before another process registered the same id, and overwriting would destroy its data.
            if (Files.exists(store.metadataFile(id))) {
                return RegistryResult.of(RegistryResult.Outcome.DUPLICATE_ID, id,
                        "another program registered '" + id + "' while UserSpace was running");
            }
            store.save(application);
        } catch (ConfigException e) {
            return RegistryResult.of(RegistryResult.Outcome.STORE_FAILURE, id, e.getMessage());
        } finally {
            mutex.unlock();
        }
        byId.put(id, application);
        byExecutable.put(identity(application.launch().executable()), id);
        return RegistryResult.of(RegistryResult.Outcome.REGISTERED, id);
    }

    /**
     * Replaces the stored value for an existing id. Used for renames, icon changes and corrected
     * executable paths; the same duplicate rules apply to the new executable.
     */
    public RegistryResult update(Application updated) {
        Objects.requireNonNull(updated, "updated");
        Application existing = byId.get(updated.id());
        if (existing == null) {
            return RegistryResult.of(RegistryResult.Outcome.NOT_FOUND, updated.id(),
                    "no application is registered as '" + updated.id() + "'");
        }
        Path newIdentity = identity(updated.launch().executable());
        ApplicationId other = byExecutable.get(newIdentity);
        if (other != null && !other.equals(updated.id())) {
            return RegistryResult.of(RegistryResult.Outcome.DUPLICATE_EXECUTABLE, updated.id(),
                    "that executable is already registered as '" + other + "'");
        }
        Optional<RegistryResult> blocked = lockForWrite(updated.id());
        if (blocked.isPresent()) {
            return blocked.get();
        }
        try {
            store.save(updated);
        } catch (ConfigException e) {
            return RegistryResult.of(RegistryResult.Outcome.STORE_FAILURE, updated.id(), e.getMessage());
        } finally {
            mutex.unlock();
        }
        Path oldIdentity = identity(existing.launch().executable());
        if (!oldIdentity.equals(newIdentity)) {
            byExecutable.remove(oldIdentity, updated.id());
        }
        byExecutable.put(newIdentity, updated.id());
        byId.put(updated.id(), updated);
        return RegistryResult.of(RegistryResult.Outcome.UPDATED, updated.id());
    }

    /** Unregisters an application. Metadata is removed first so a failure changes nothing. */
    public RegistryResult remove(ApplicationId id) {
        Objects.requireNonNull(id, "id");
        Application existing = byId.get(id);
        if (existing == null) {
            return RegistryResult.of(RegistryResult.Outcome.NOT_FOUND, id,
                    "no application is registered as '" + id + "'");
        }
        Optional<RegistryResult> blocked = lockForWrite(id);
        if (blocked.isPresent()) {
            return blocked.get();
        }
        try {
            store.delete(id);
        } catch (ConfigException e) {
            return RegistryResult.of(RegistryResult.Outcome.STORE_FAILURE, id, e.getMessage());
        } finally {
            mutex.unlock();
        }
        byId.remove(id);
        byExecutable.remove(identity(existing.launch().executable()), id);
        return RegistryResult.of(RegistryResult.Outcome.REMOVED, id);
    }

    /**
     * Takes the write lock, or returns the failure the caller should hand back.
     */
    private Optional<RegistryResult> lockForWrite(ApplicationId id) {
        try {
            mutex.lock(MUTATION_TIMEOUT_MILLIS);
            return Optional.empty();
        } catch (FileMutex.LockBusyException e) {
            return Optional.of(RegistryResult.of(RegistryResult.Outcome.LOCK_UNAVAILABLE, id, e.getMessage()));
        } catch (IOException e) {
            return Optional.of(RegistryResult.of(RegistryResult.Outcome.STORE_FAILURE, id, e.getMessage()));
        }
    }

    /**
     * Canonical form of an executable for duplicate detection: the resolved real path when it exists,
     * the normalized absolute path otherwise (a not-yet-installed application may be registered).
     */
    private static Path identity(Path executable) {
        try {
            return executable.toRealPath();
        } catch (IOException e) {
            return executable.toAbsolutePath().normalize();
        }
    }
}
