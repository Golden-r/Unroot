# UserSpace — Architecture

Modular, rootless application manager for Debian-family Linux, Java + Swing.

Design target: **Java 17 bytecode** (Debian 12 ships 17, Ubuntu 24.04 ships 21). Building with
`--release 17` on a newer JDK keeps one artifact runnable everywhere.

Guiding rule for everything below: *the filesystem is the state*. Every change this tool makes is a
small, named, inspectable file, and every change is recorded so it can be undone. No databases, no
daemons, no frameworks.

---

## 1. Directory and package layout

```
src/main/java/org/uspace/
├── Main.java                    composition root (the only place that wires things)
├── ui/                          Swing only; no Files/Process calls
├── core/
│   ├── model/                   immutable domain types (pure, no IO)
│   ├── registry/                in-memory index + persistence of registered apps
│   └── service/                 use-case facade the UI talks to
├── discovery/                   scanning directories, classifying candidates
├── integration/
│   ├── desktop/                 .desktop entries, icons, menu refresh
│   └── shell/                   wrappers, PATH links, env drop-ins, aliases
├── system/                      ProcessBuilder, XDG dirs, probes, locks, atomic file ops
├── config/                      settings + per-app metadata serialization
└── util/                        genuinely reusable helpers (string escaping, slugs)
```

Physical package = one concern. No deeper nesting than one level under `integration`, because
desktop and shell integration are genuinely independent and each is ~4 classes.

On-disk layout (all inside `$HOME`, all rootless, XDG-respecting):

```
$XDG_DATA_HOME/userspace/apps/<slug>/meta.properties      registered metadata (user's data)
$XDG_DATA_HOME/userspace/apps/<slug>/integration.properties  what we generated (separate file!)
$XDG_DATA_HOME/userspace/icons/<slug>.png|svg             icon copies
$XDG_DATA_HOME/userspace/bin/<slug>                       generated wrapper scripts
$XDG_DATA_HOME/applications/userspace-<slug>.desktop      user-level launchers
$XDG_CONFIG_HOME/userspace/settings.properties            preferences, scan roots
$XDG_STATE_HOME/userspace/journal/<op-id>/                backups enabling undo
$XDG_STATE_HOME/userspace/logs/userspace.log              java.util.logging output
$XDG_CACHE_HOME/userspace/thumbs/                         extracted AppImage icons
```

`system/XdgDirectories` resolves `XDG_*` vars, falling back to `~/.config`, `~/.local/share`,
`~/.local/state`, `~/.cache` when unset **or empty**, and ignoring non-absolute values
([XDG Base Directory 0.8](https://whot.pages.freedesktop.org/xdg-specs/basedir-spec/latest/ar01s02.html)).
Hard-coding `~/.local/share` is a bug: desktop entries belong in `$XDG_DATA_HOME/applications`.

## 2. Package responsibilities

| Package | Owns | Explicitly does not |
|---|---|---|
| `ui` | `JFrame`, table model, dialogs, progress, EDT rules | touch `Path`, `Files`, `ProcessBuilder` |
| `core.model` | `Application`, `AppType`, `LaunchCommand`, `EnvVariable`, `Health` | know about files on disk |
| `core.registry` | `ApplicationRegistry`: uniqueness, add/remove/update, lookup, duplicate detection, write lock per mutation | write `.desktop` files, encode property values |
| `core.service` | one method per user intent (`register`, `scan`, `integrate`, `remove`, `launch`) | Swing types |
| `discovery` | walk scan roots, classify files, propose `Application` candidates | register anything itself |
| `integration.desktop` | `DesktopEntry` rendering, icon deployment, cache refresh | decide *whether* to integrate |
| `integration.shell` | wrapper scripts, PATH symlinks, `env.d` drop-ins | edit `.bashrc` unasked |
| `system` | `CommandRunner` (arg lists), probes, `FileMutex` (cross-process lock), path guards | domain decisions |
| `config` | `ApplicationStore`, `ApplicationCodec`, `PropertiesFile`, `SettingsStore`: atomic writes, schema versioning, per-app metadata, preferences | caching, UI state, duplicate policy |
| `util` | `Slug`, desktop-entry escaping, small predicates | anything with a domain meaning |

Dependency direction is strictly downward: `ui → service → {registry, discovery, integration, config}
→ {model, system} → util`. `Main` may see everything; nothing may see `Main`.

`integration` gets a **plan/apply** split (`IntegrationPlan`, `IntegrationPlanner`,
`IntegrationApplier`). That is the one deliberate pattern in the codebase, justified by requirement
6: predictability and reversibility require knowing *all* writes before performing any of them.

## 3. Domain model

```
ApplicationId (slug)          ── filename-safe identity, [a-z0-9-]{1,64}
Application
 ├─ id            ApplicationId
 ├─ name          String          display name
 ├─ description   String?
 ├─ type          AppType         APPIMAGE | PORTABLE | BINARY | JAVA_APP | CUSTOM
 ├─ launch        LaunchCommand   executable + args + workingDirectory
 ├─ installRoot   Path?           dir that belongs to the app (null for bare binaries)
 ├─ icon          Path?           user or extracted icon, normalized to absolute
 ├─ environment   List<EnvVariable>   ordered name=value, applied at launch
 ├─ registeredAt  Instant
 └─ origin        String          "discovered:/opt/apps" | "manual" | archive path
```

Relationships:

- `Application` **has one** `LaunchCommand` and **many** `EnvVariable`s.
- `Application` is **referenced by** zero or more `IntegrationArtifact`s (`kind` = DESKTOP_ENTRY,
  WRAPPER, PATH_LINK, ENV_DROPIN; `path`; `checksum`). Artifacts are persisted in
  `integration.properties`, never inside `meta.properties` — requirement 8 in practice. It also makes
  "the user deleted our .desktop file by hand" detectable rather than silently duplicated.
- `Health` is computed, never stored: `OK | MISSING_EXECUTABLE | NOT_EXECUTABLE | MISSING_ROOT |
  MISSING_ICON | BROKEN_ARTIFACT` plus messages. Computed on demand by `system` probes, cached only
  for the lifetime of one UI refresh.
- `AppType` carries capability flags instead of subclasses: `ownsInstallRoot()`,
  `requiresExecutableBit()`. Five types do **not** justify a class hierarchy; a `switch` on the enum in
  the two places that care is cheaper to read than five subclasses. `ownsInstallRoot()` is enforced by
  the model, which is why a `PORTABLE` application without an install root cannot be built — nor
  decoded from a hand-edited file.

Immutable `record`s, `List.copyOf`, and validation in compact constructors (absolute path, no `..`,
executable bit checked only at *probe* time — construction must stay IO-free so the UI can build
candidates optimistically).

## 4. Configuration & persistence (implemented in M1)

Three separate stores, matching the three different lifetimes:

1. **`settings.properties`** (Properties, stdlib) — scan roots, preferred shell, "always confirm
   before writing", UI scale. Small, user-editable, hand-written comments survive via
   load→write-through of known keys only.
2. **Per-app `meta.properties`** — one directory per app under `$XDG_DATA_HOME/userspace/apps/`.
   Chosen over a single `registry.json` because it: makes atomic per-app writes possible (no
   whole-registry rewrite race), lets a user diff/inspect/repair one app, and makes "delete the app
   = delete the directory" honest. `java.util.Properties` is ugly but zero-dependency, escaping-safe
   (`store`/`load` handles `=`, `:`, unicode), and diffable — unlike hand-rolled JSON, which is the
   classic place a no-dependency project grows a parser bug.
3. **`integration.properties`** — generated artifacts, checksums, timestamps. Disposable: deleting it
   never loses user data, only our knowledge of what we deployed.

Stores 1 and 2 are implemented (M1, M2); store 3 is described to keep the layout coherent and
arrives with the integration milestone.

### 4.1 What M1 actually ships

| Class | Responsibility |
|---|---|
| `config.PropertiesFile` | the only code that touches bytes: UTF-8 read/write, temp file, fsync, rename, permissions |
| `config.ApplicationCodec` | `Application` ↔ `Properties`, schema gate, key-level error messages |
| `config.ApplicationStore` | one directory per application: `loadAll`, `save`, `delete`, `load` |
| `config.ConfigException` | checked: unreadable file, unwritable directory |
| `config.MalformedMetadataException` | checked subtype carrying the offending key |
| `config.MetadataIssue`, `config.LoadReport` | best-effort load results: what loaded, what did not |
| `core.registry.ApplicationRegistry` | in-memory index, duplicate rules, persist-before-mutate |
| `core.registry.RegistryResult` | `REGISTERED / UPDATED / REMOVED / DUPLICATE_ID / DUPLICATE_EXECUTABLE / NOT_FOUND / STORE_FAILURE` |

`RegistryResult` returns these as values rather than throwing, because a duplicate is ordinary user
input that the UI has to render next to the form.

### 4.2 Key layout of `meta.properties`

```
schema.version=1
id=media-player                  # must match the directory name
name=Media Player
description=Plays things
type=PORTABLE                    # AppType name, validated on read
launch.executable=/abs/path      # required, must be absolute
launch.argument.count=1          # absent means 0
launch.arguments.0=--no-sandbox
launch.workingDirectory=/abs     # omitted when unset
install.root=/abs                # omitted when unset
icon=/abs                        # omitted when unset
environment.count=1
env.0.name=JAVA_HOME
env.0.value=/usr/lib/jvm/default-java
registered.at=2026-02-03T04:05:06.123456789Z
origin=discovered:/home/user/Apps
```

Two keys per environment variable instead of the `env.0=NAME=VALUE` form sketched earlier: values
legitimately contain `=`, and a single packed key forces every reader to guess where the name ends.
The entry prefixes (`launch.arguments.`, `env.`) are deliberately distinct from the count keys
(`launch.argument.count`, `environment.count`) — when they overlapped, a zero-length argument list was
misread as orphaned data. The test suite caught that; the naming is now load-bearing.

### 4.3 Encoding rules (verified, not assumed)

- **UTF-8 `Reader`/`Writer`, never `InputStream`/`OutputStream`.** `load(InputStream)` decodes
  ISO-8859-1 and `store(OutputStream)` writes non-Latin-1 text as unicode escapes. Pairing
  `store(Writer)` with `load(Reader)` keeps `Média Café 日本語 ✓` literal in the file, which is what
  makes the file hand-editable.
- `=` and `:` inside values are escaped by `Properties.store` and restored by `load`; so are newlines.
  Leading and trailing spaces survive the `Writer` path (a `Reader`-based load does not trim them).
- Empty strings, empty lists and absent optional paths are distinct: absent keys are removed rather
  than written empty, so `icon=` never appears for an application without an icon.

### 4.4 Write protocol

1. Create the parent directory if needed with mode `0700` (existing directories keep their mode).
2. Write to a temp file in the same directory, created `0600`, so no window exists where the final
   file is world-readable.
3. `FileChannel.force(true)`, then rename.
4. `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`; on `AtomicMoveNotSupportedException` fall back to
   `REPLACE_EXISTING`. The fallback is a rename on the same filesystem but is not guaranteed atomic —
   documented rather than hidden.
5. On any failure, delete the temp file and throw `ConfigException`. The previous file is untouched.

**No write when nothing changed.** `Properties.store` always emits a date comment, so re-encoding the
same application would still produce different bytes and a new mtime. `ApplicationStore.save` compares
the encoded result with the current file and returns without writing when they are equal.

### 4.5 Schema versioning

`schema.version` is `1`. A file without the key is treated as version 1 (it can only contain version-1
keys). A file with a **higher** version is refused with a `MalformedMetadataException`, reported as a
`MetadataIssue`, and **left on disk untouched** — an older build must never rewrite a newer file.

### 4.6 Unknown keys

`ApplicationCodec.encode` writes into the `Properties` instance read from disk instead of a fresh one,
clearing only the keys it owns. Keys added by a newer version — or by the user — therefore survive an
update. This is also where `integration.*` keys may later live without a format change.

### 4.7 Registry semantics

- **Persist, then mutate memory.** A failed write leaves the registry exactly as it was; the user is
  never shown an application that will be missing after a restart.
- Duplicate rules: same id, or same executable. Executable identity is `toRealPath()` when the file
  exists, so a symlink to an already registered binary is caught; a copy at another path is not.
- `all()` is sorted by display name (case-insensitive) so the table does not reorder between refreshes.
- Loading is best-effort: each application directory is decoded independently, and every failure
  becomes a `MetadataIssue` instead of aborting the load.
- `delete` removes `meta.properties` and then the directory **only if empty**. Files we did not create
  are never removed, so removal stays safe even before the integration layer exists.

### 4.8 Settings (M2)

`config.Settings` holds three values that later milestones actually consume: `scanRoots` (M3),
`confirmBeforeWriting` (defaults to **true**, because "ask" is the safe answer when the setting is
missing), and `preferredShell` (M5).

`config.SettingsStore` reuses the same file rules — UTF-8, atomic replace, `0600`, `schema.version`,
unknown keys preserved, no write when unchanged — with one deliberate asymmetry:

> **load never fails.** An unreadable or malformed settings file yields `Settings.defaults()` plus
> `MetadataIssue`s. Refusing to start over a bad preference line is a worse outcome than starting with
> safe defaults. Saving still fails loudly, and a file written by a **newer** schema is reported and
> left byte-identical, exactly like application metadata.

Keys: `schema.version`, `scan.roots.count`, `scan.root.<i>`, `confirm.beforeWriting`, `shell`. The count
key is `scan.roots` (plural) while entries are `scan.root.<i>` — the same prefix-separation rule as
4.2, for the same reason.

Invalid values are not silently coerced: `confirm.beforeWriting=maybe` becomes an issue naming the key
and falls back to `true`; a relative scan root is reported and skipped; duplicate roots collapse.

### 4.9 Cross-process write lock (M2)

`system.FileMutex` wraps an OS advisory lock (`FileChannel.tryLock`) on
`$XDG_STATE_HOME/userspace/lock`, created `0600`:

- **Held for the process lifetime** by `Main`. If it is already held, the second instance prints the
  owner (`pid=… since=…` from the lock file's informational content) and exits with code `3`.
- **Taken per mutation** by `ApplicationRegistry.register/update/remove`, with a 5 s budget, so tools
  and test processes that do not hold the lifetime lock still cannot interleave a
  read-modify-write. Re-entrant within the process: when `Main` already holds it, mutation locking is
  a counter increment, never a second OS lock (which would throw `OverlappingFileLockException`).
- **Blocking acquisition polls `tryLock`** rather than calling `lock()`: a blocking `FileLock` cannot
  be interrupted, so a timed wait is the only way to fail with a message instead of hanging.
- **The lock file is never a liveness indicator.** The kernel drops the lock when the process dies, so
  a crash cannot leave a stale lock; the pid written into the file is for humans and is never trusted.
- **Reads take no lock.** Each metadata file is installed by an atomic rename, so a reader sees the
  old or the new content, never a partial file.

Duplicate detection is repeated **on disk inside the critical section** for ids
(`Files.exists(meta.properties)`), because a registry loaded minutes ago cannot know what another
process registered since. Without that check, two processes racing on the same id both save and one
registry silently overwrites the other — proven by a negative-control run recorded in
`VERIFICATION-M2.md`.

### 4.10 Still absent

`integration.properties`, the journal and undo (M7). Cross-process duplicate detection by
**executable path** is also still in-memory only: it would need a full re-scan of `apps/` inside every
write, which is not worth it while a single instance holds the lifetime lock. Both are scheduled
(M2b, M7) rather than forgotten — `current step.md` tracks them.

**Reversibility:** before any integration write, `IntegrationApplier` copies each pre-image into
`journal/<op-id>/` with a manifest. "Undo last change" replays it backwards. Journal dirs are pruned
by age on startup. This is also the migration path if a future version mishandles a user's existing
file.

## 5. Main execution flow

Startup (`Main`):

1. Refuse to run as root (`euid == 0` → explain and exit). Root-owned files in `$HOME` are a support
   nightmare and nothing here needs privileges. **Implemented.**
2. Resolve XDG dirs, create missing ones with `0700`. **Implemented.**
3. Take the single-instance lock (`$XDG_STATE_HOME/userspace/lock`). If another process holds it,
   print the owner and exit `3`. **Implemented** — verified end to end with a child process holding it.
4. Load `Settings`, falling back to defaults and reporting issues. **Implemented.**
5. Load the registry: parse every `apps/*/meta.properties`, tolerate and report malformed ones instead
   of failing the start. **Implemented** — `Main` prints the count and each issue.
6. Show the window with the loaded list; health checks run on a `SwingWorker` and repaint rows as they
   resolve. *Not implemented (M6 for the checks, UI milestone for the window).*
7. Log to `$XDG_STATE_HOME/userspace/logs/`, never to stdout only. *Not implemented.*

Package-launch → `Application` (slug), then:

```
UI: Register app wizard ─┐
UI: Discover scan ───────┼─> core.service  (validates input, no IO on EDT)
UI: Integrate / Remove ──┘
```

`ApplicationService.register(candidate)` pipeline:

```
validate (absolute, exists, executable, realPath containment)
  -> duplicate check (canonical path + inode, registry lookup)
  -> Application
  -> registry.put(app)                 (atomic meta.properties)
  -> integration: plan -> journal -> apply -> verify
```

`ApplicationService.integrate(app)` is the interesting one:

1. **Plan** — `IntegrationPlanner` produces an ordered, immutable list of ops:
   `WRITE_ICON`, `WRITE_WRAPPER`, `WRITE_DESKTOP_ENTRY`, `LINK_INTO_LOCAL_BIN`, `ENV_DROPIN`.
   Nothing is executed while planning, so the UI can show "this will create 4 files" before consent.
2. **Journal** — back up pre-images of any existing file we are about to replace.
3. **Apply** — ops execute in dependency order (wrapper before the `.desktop` that points at it),
   each through `system.AtomicFiles`, each wrapped in a per-op try/catch that records failures rather
   than aborting the batch.
4. **Verify** — re-read the `.desktop` file, confirm the wrapper is executable, confirm the icon
   resolves. Any failure → report exactly which artifact is wrong and offer undo.

`ApplicationService.launch(app)` uses `ProcessBuilder` with an argument list, `redirectErrorStream`,
and a small log ring buffer; it is a convenience, not the main path (the wrapper/launcher is).

Removal is the integration pipeline in reverse: delete artifacts (only those listed in
`integration.properties`), then the app directory. Files we did not create are never touched.

## 6. Dependency map

```
             ┌──────── Main (composition root) ────────┐
             │                                          │
            ui                                         │
             │                                           │
        core.service  ──> core.registry ──> config ──> core.model
             │                 │                  │        │
        ┌────┴─────┐           │                  │        │
   discovery   integration ────┴──────────────────┴────────┤
        │           │                                      │
        └───────> system ──────────────────────────────────┘
                     │
                   util
```

Allowed edges, nothing else (a `jdeps` check in the build keeps this honest):

- `ui` → `core.model`, `core.service`
- `core.service` → `core.registry`, `core.model`, `discovery`, `integration`, `config`, `system`
- `core.registry` → `config`, `core.model`, `system` (for `FileMutex`)
- `integration` → `core.model`, `system`, `config`, `util`
- `discovery` → `core.model`, `system`, `util`
- `config` → `core.model` (settings and metadata are pure `Properties` work; no `system` dependency)
- `system` → `util`
- `core.model`, `util` → (JDK only)

The `FileMutex` edge is the one to watch: `system` must never grow a dependency back on `config` or
`core`, or the lock and the data it protects start owning each other.

Consequences worth stating: `ui` never imports `java.nio.file` or `ProcessBuilder`, which is what
makes requirement 1 checkable by grep; `core.model` imports nothing of ours, so it stays trivially
serializable and testable; `integration` never talks to `ui`, so it can be reused headless later.

## 7. Roadmap

| # | Milestone | Done when |
|---|---|---|
| M0 | Skeleton, `build.sh`, `XdgDirectories`, `Main` shell | compiles at release 17, starts, logs resolved paths, refuses root |
| M1 ✅ | Model + config round-trip + registry persistence | `Application` ↔ `meta.properties` survives a round-trip test with spaces/`=`/unicode; registry survives a restart; 33 tests green |
| M2 ✅ | Settings + cross-process write lock | `settings.properties` round-trips with fallbacks; a second process is refused or serialized; three processes racing on the same ids register each id exactly once |
| M2b | Inode equality | two paths to the same inode cannot register twice (currently real-path identity only) |
| M3 | Discovery | scans roots, finds AppImages + ELF binaries + `*.jar` with wrapper guess, reports candidates, registers zero duplicates |
| M4 | Desktop integration | `.desktop` passes `desktop-file-validate`, appears in the menu after logout/login, icon shows |
| M5 | Shell integration | wrapper + `~/.local/bin` symlink; PATH presence probed and reported, never assumed |
| M6 | Health checks + repair | broken registrations surface in the table with a repair action |
| M7 | Reversibility | undo restores pre-images byte-for-byte; journal pruned |
| M8 ✅ | Packaging | `./build.sh package` produces a relocatable app-image with a `java.base` runtime; nine checks run against the packaged launcher, including the exit-3 guard with the holder on the bundled runtime |

Each milestone is shippable and testable headless except M4's visual check.

**Packaging (M8):** `./build.sh package` runs `jlink --add-modules java.base` and hands the result to
`jpackage --type app-image`, so the artifact carries its own runtime and needs no JDK on the target
machine. It then verifies the artifact instead of trusting `jpackage`'s exit code: the launcher is
run under `env -i` with only `PATH`, `HOME` and the `XDG_*` variables (the environment a desktop
launcher provides), the settings path it echoes must be inside the sandbox, created directories must
be `0700`, and a second instance must exit `3` while another process holds the lock. That last check
executes the test harness on the **bundled** runtime, which is what keeps `RUNTIME_MODULES` honest —
a module missing from the image fails the build here rather than on a user's machine. The third
encoding option (`-Dstdout.encoding=UTF-8`) exists because a locale-less launcher otherwise mangles
the em dash in the banner.

`.deb` output stays optional and maintainer-built: it installs under `/opt/userspace` with root,
which contradicts the premise of a rootless tool, so it is a distribution channel and not the
recommended way to run UserSpace.

**Test strategy (as built):** `./build.sh test` compiles `src/main` and `src/test` and runs
`org.uspace.test.TestMain`, a ~120-line harness with named cases, assertion helpers and a skip
mechanism for cases that cannot run as root. No JUnit jar is downloaded, so the suite runs offline on
a machine with only a JDK, and every filesystem case uses a temporary directory. If a build tool is
introduced later, the same cases re-express on JUnit 5 without changing what they assert.

## 8. Decisions worth arguing about (and the risks)

**Wrapper scripts instead of `Exec=` soup.** The Desktop Entry `Exec` key is a quoting minefield:
reserved characters must be double-quoted, `"`, backtick, `$`, `\` escaped, a literal backslash ends
up as four of them, and field codes may not appear inside quotes
([Exec key](https://xdg.pages.freedesktop.org/xdg-specs/desktop-entry/latest/exec-variables.html)).
So we generate `$XDG_DATA_HOME/userspace/bin/<slug>` (a `#!/bin/sh` script doing `cd`, `export`,
`exec "$@"`) and keep `Exec=` to one path plus `%U`. One quoting problem, solved once, in a language
where quoting is comprehensible — and env vars/working dir land in the wrapper instead of in shell rc
files. `TryExec=` points at the same wrapper so dead launchers disappear on their own.

**Icons**: `Icon=` may be an absolute path to a PNG/SVG
([recognized keys](https://specifications.freedesktop.org/desktop-entry/latest/recognized-keys.html)),
so we copy to our own `icons/<slug>.png` rather than installing into a hicolor theme — no theme cache
invalidation, no collisions, trivially reversible. For AppImages, extract `.DirIcon` once into the
cache dir (the AppDir convention guarantees `AppRun`, a root `.desktop` and `.DirIcon`,
[AppDir spec](https://docs.appimage.org/reference/appdir.html)).

**`update-desktop-database` is not the "refresh menu" button** — it only builds `mimeinfo.cache` for
`MimeType=` handlers ([man page](https://manpages.debian.org/trixie/desktop-file-utils/update-desktop-database.1.en.html)).
It is therefore optional, invoked only if present, and never treated as a prerequisite.

**Never editing `.bashrc`/`.profile` by default.** `~/.local/bin` symlinks cover the PATH need, and
the app *probes* whether that directory is actually on `PATH` instead of assuming the distro wired it
up. Shell aliases are emitted into `$XDG_CONFIG_HOME/userspace/env.sh` with a printed one-line
`source` instruction. Only if the user explicitly opts in do we append a delimited
`# >>> userspace >>>` block to `.profile`, with a journaled backup and a removal path — the marker
block is what makes it removable without touching the user's own lines.

**Metadata vs generated files split** (`meta.properties` / `integration.properties`) is what makes
"uninstall" safe: we delete exactly what we recorded creating.

Risks and answers:

- *`AppType` proliferation* → enum + capability flags; if a sixth type appears with genuinely
  different launch semantics, revisit then.
- *Duplicate detection is heuristic*: canonical path + `(dev, inode)` catches symlinks and hardlinks;
  it will still miss the same tarball extracted twice to different paths. Accept it, show candidates
  and let the user merge.
- *AppImage needs FUSE*: types 2 AppImages fail without `libfuse2`. Detect the failure at launch and
  offer the `--appimage-extract-and-run` variant in the wrapper.
- *Extraction is an attack surface*: extracted archives must be checked for absolute paths and `..`
  entries before writing; install roots are validated to stay inside the user's chosen directory.
- *Wayland/HiDPI Swing*: set `sun.java2d.uiScale` from settings, expect XWayland; don't fight it in M0.
- *Swing on the EDT*: all IO in `SwingWorker`/a single-thread `ExecutorService` for mutations; a
  mutation queue also serializes the registry writes that the lock protects against other processes.
- *`Properties` has no nesting*: env vars are stored as ordered `env.<n>=NAME=VALUE` keys. Slightly
  awkward, vastly better than a bespoke JSON writer.
- *Java version drift*: `--release 17` plus a startup check that fails with a clear message rather
  than a `UnsupportedClassVersionError`.
