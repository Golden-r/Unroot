# Current step

Last updated after M2. Branch `main`, 32 main sources + 13 test sources, suite green.

```bash
./build.sh test   # 53 passed, 0 skipped, 0 failed — the number to check before and after any change
```

## Done

| Milestone | What exists | Proof |
|---|---|---|
| **M0** | `build.sh` (JDK only), `XdgDirectories`, `Main` that refuses to run as root, prepares the layout and reports it | compiles at `--release 17`, runs, creates `0700` directories |
| **M1** | `PropertiesFile` (UTF-8, temp-file + fsync + atomic rename, non-atomic fallback), `ApplicationCodec`, `ApplicationStore`, `ApplicationRegistry`, `RegistryResult`, `MetadataIssue`/`LoadReport` | 33 cases originally; unknown-key preservation, schema-version refusal, permissions, crash-safe write behaviour |
| **M2** | `Settings`/`SettingsStore`/`SettingsLoad`, `FileMutex`, mutations under the lock, single-instance guard in `Main` (exit `3`), `LOCK_UNAVAILABLE` outcome, on-disk id re-check | 20 more cases including four child-JVM concurrency cases; negative controls recorded in `VERIFICATION-M2.md` |

## Next, in order

### M2b — inode equality (small, close the known gap)

Duplicate detection currently uses `toRealPath()`, which catches symlinks but **not hardlinks** or two
different mount points to the same file.

- Compare `(device, inode)` from `Files.readAttributes(path, BasicFileAttributes.class).fileKey()`
  when the file exists; fall back to the real path when `fileKey()` is `null` (some filesystems).
- Keep the registry's in-memory index, but key it by that identity.
- Tests: hardlink to an already registered binary → `DUPLICATE_EXECUTABLE`; two mounts of the same
  file if one can be simulated; a not-yet-existing path → falls back to real path without throwing.

### M3 — discovery

Scan `settings.scanRoots`, classify candidates, never auto-register.

- Rules: AppImage (executable, `AppImage` magic / `.AppImage` suffix), ELF binary (`\x7fELF` header,
  executable), extracted tree (`AppRun` present), runnable jar (`META-INF/MANIFEST.MF` + `Main-Class`),
  `*.desktop` in user directories.
- Emit candidates with a confidence and a reason string; the UI will present them for review.
- Skip duplicates against the registry; report rather than silently dropping.
- Bound the walk (depth limit, skip our own data directories, never follow symlinks out of the root)
  and make it cancellable.
- Tests: a temp tree with one of each kind, a symlink loop, an unreadable directory, a file that looks
  like an executable but is not (permission bits), and no duplicates after registering.

### M4 — desktop integration

- `IntegrationPlan` / `IntegrationPlanner` / `IntegrationApplier` as designed in ARCHITECTURE §1–2.
- Wrapper script in `$XDG_DATA_HOME/userspace/bin/<id>`, `.desktop` in
  `$XDG_DATA_HOME/applications/userspace-<id>.desktop`, icon copied to `userspace/icons/`.
- `Exec=` stays trivial (one wrapper path + `%U`); quoting lives in `sh`.
- Verify with `desktop-file-validate` when available; `update-desktop-database` is optional.
- Tests: golden-file comparison of generated `.desktop`/wrapper, de-integration removes exactly the
  recorded artefacts, a pre-existing user file at the target path is journalled, never clobbered.

### M5 — shell integration

Wrapper is already generated in M4; this milestone adds `~/.local/bin` links and the env drop-in.

- Probe whether `~/.local/bin` is on `PATH`; report instead of assuming.
- `$XDG_CONFIG_HOME/userspace/env.sh` for aliases/exports; print the `source` line.
- Only on explicit opt-in: append a delimited `# >>> userspace >>>` block to `~/.profile`, journalled.
- Tests: no shell file is touched by default; the block is removed exactly; a user's own lines survive.

### M6 — health checks and repair

`Health` (`OK|BROKEN|STALE`) computed on demand: missing executable, missing exec bit, missing install
root, broken artefact (recorded path gone or not ours), missing dependency note for AppImages needing
FUSE. Repair = de-integrate + re-integrate.

### M7 — journal and undo

Pre-images into `$XDG_STATE_HOME/userspace/journal/<op-id>/` with a manifest; "undo last change"
replays backwards; prune by age at startup. This is the milestone that makes the reversibility claim
in the README true for integration, not just for removal.

### M8 — packaging

`jpackage --type app-image` producing a rootless directory with a bundled runtime; optional `.deb`
built by a maintainer.

## Open limitations (carried forward, do not lose track)

Verified-but-untestable and known gaps, from `VERIFICATION-M2.md`:

1. **`FileLock` semantics on NFS** are unverified; the code degrades to atomic renames but the
   combination is untested.
2. **`InterruptedException` inside `FileMutex.lock`** (while polling) is implemented but not exercised.
3. **No fairness guarantee** — acquisition polls, so starvation is possible in principle and
   unmeasured.
4. **Cross-process duplicate detection by executable path is in-memory only.** The id collision is
   checked on disk inside the lock; the path collision is not. Fix together with M2b.
5. **`integration.properties` and the journal/undo** do not exist yet (M4/M7).
6. **Discovery, desktop and shell layers are untouched**; nothing has been exercised against a real
   AppImage or a real `.desktop` file.
7. **`ENOSPC`/quota failures** are not simulated; only read-only directories are.
8. **`ownerDescription`** may read a partially written line during a race — diagnostics only, never
   used for decisions.

## Traps already paid for (do not re-learn these)

- **`Properties.store` always writes a date comment.** Comparing encoded data with the file and
  skipping unchanged writes is why `mtime` is stable; do not "simplify" that away.
- **Prefix collisions between a count key and its entries.** `launch.argument.` matched
  `launch.argument.count`, so an empty list looked like orphaned data. Entry prefixes are now
  `launch.arguments.`, `env.`, `scan.root.` — keep them disjoint from their count keys.
- **`Properties.load(InputStream)` is ISO-8859-1.** Always use the `Reader`/`Writer` overloads.
- **A blocking `FileLock.lock()` cannot be interrupted**, hence the polling loop with a deadline.
- **Concurrency tests without a barrier are vacuous.** `Child register` waits for the parent to create
  a barrier file, so every process has loaded the registry before any writes. Removing the barrier makes
  the test pass even with no lock.
- **`FileMutex.forFile` shares one instance per path**; two channels on the same file inside one JVM
  throw `OverlappingFileLockException`.

## Conventions

- Java records, validation in compact constructors, no IO in `core.model`.
- Checked `ConfigException` for persistence; results-as-values (`RegistryResult`) for expected
  user outcomes.
- Persist before mutating memory.
- Tests use their own temporary directories, never `$HOME`, and skip (never fake) permission cases when
  run as root.
- `./build.sh test` must be green before a commit; report anything that could not run.
