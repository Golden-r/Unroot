# Current step

Last updated after M8 (packaging). Branch `main`, 32 main sources + 13 test sources, suite green.

```bash
./build.sh test      # 53 passed, 0 skipped, 0 failed — the number to check before and after any change
./build.sh package   # build/dist/UserSpace, then 9 checks against the packaged launcher
```

## Done

| Milestone | What exists | Proof |
|---|---|---|
| **M0** | `build.sh` (JDK only), `XdgDirectories`, `Main` that refuses to run as root, prepares the layout and reports it | compiles at `--release 17`, runs, creates `0700` directories |
| **M1** | `PropertiesFile` (UTF-8, temp-file + fsync + atomic rename, non-atomic fallback), `ApplicationCodec`, `ApplicationStore`, `ApplicationRegistry`, `RegistryResult`, `MetadataIssue`/`LoadReport` | 33 cases originally; unknown-key preservation, schema-version refusal, permissions, crash-safe write behaviour |
| **M2** | `Settings`/`SettingsStore`/`SettingsLoad`, `FileMutex`, mutations under the lock, single-instance guard in `Main` (exit `3`), `LOCK_UNAVAILABLE` outcome, on-disk id re-check | 20 more cases including four child-JVM concurrency cases; negative controls recorded in `VERIFICATION-M2.md` |
| **M8** | `./build.sh package` (jlink `java.base` runtime + `jpackage --type app-image`) and `./build.sh deb`; the packaged launcher is verified, not just built | 9 artifact checks green (`VERIFICATION-M8.md`), 55 MB image that runs after being copied elsewhere, exit `3` proven with the holder on the bundled runtime |

The tool now ships, but it still only does what M0–M2 do. Packaging M8 before M3–M7 is deliberate: it
was the last item on the roadmap, and doing it now means every later milestone has somewhere to land
and a `./build.sh package` that will fail loudly if the new code needs a module the image lacks.

## Next, in order

### M2b — inode equality (small, close the known gap)

Duplicate detection currently uses `toRealPath()`, which catches symlinks but **not hardlinks** or two
paths to the same file reached through different directories.

- Compare `(device, inode)` from `Files.readAttributes(path, BasicFileAttributes.class).fileKey()`
  when the file exists; fall back to the real path when `fileKey()` is `null` (some filesystems).
- Keep the registry's in-memory index, but key it by that identity.
- Tests: hardlink to an already registered binary → `DUPLICATE_EXECUTABLE`; a not-yet-existing path →
  falls back to real path without throwing. Two *mounts* of the same file cannot be created without
  root, so that case is documented as untested rather than faked.

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
- Verify with `desktop-file-validate` — it is installed on this machine, and M8's image has no
  `.desktop` file yet, so this check has never run.
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

### UI — the window

Not yet scheduled as a milestone of its own; ARCHITECTURE §5 step 6 still says "not implemented".
The rule stands: `ui` imports no `java.nio.file` and no `ProcessBuilder`, all IO off the EDT, and the
runtime only grows when it actually needs Swing — at which point `RUNTIME_MODULES` in `build.sh` needs
`java.desktop`, and `./build.sh package` will fail until it gets it.

## Open limitations (carried forward, do not lose track)

Verified-but-untestable and known gaps, from `VERIFICATION-M2.md` and `VERIFICATION-M8.md`:

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
9. **Packaging is single-architecture and single-machine.** The image is x86_64 with a `java.base`
   runtime, built and run only on this host; `jlink` cannot cross-link, so an `aarch64` build must be
   produced on that architecture.
10. **The `.deb` was never installed.** Its metadata and file list were checked, nothing more. It also
    needs root, so it is a maintainer channel, not the recommended artifact — and `Maintainer:` reads
    `UserSpace <Unknown>` until a packager passes their own identity.
11. **No signing, and builds are not reproducible** (`jpackage` stamps the package).

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
- **`jpackage --input` ships everything in the directory.** Pointing it at `build/` ships `classes/`
  and `test-classes/` next to the jar; the jar is staged into `build/package-input` instead, and the
  build `cmp`s the shipped jar against the one it just built.
- **`java.base` alone is a real runtime.** It carries `FileChannel.tryLock` and `java.time`, which is
  why the packaged smoke check can use the bundled runtime as its lock holder. It does *not* carry
  Swing; adding the UI means adding `java.desktop` or the launcher dies at startup.
- **A locale-less launcher writes `?` instead of non-ASCII.** `-Dstdout.encoding=UTF-8` and
  `-Dstderr.encoding=UTF-8` are part of the package config for that reason, not decoration.
- **`jlink --compress` syntax changed** (`--compress=2` → `--compress=zip-N`); it is left out so the
  same script builds on Debian 12's JDK 17 and on JDK 25.

## Conventions

- Java records, validation in compact constructors, no IO in `core.model`.
- Checked `ConfigException` for persistence; results-as-values (`RegistryResult`) for expected
  user outcomes.
- Persist before mutating memory.
- Tests use their own temporary directories, never `$HOME`, and skip (never fake) permission cases when
  run as root.
- `./build.sh test` must be green before a commit, and `./build.sh package` before claiming a build
  ships; report anything that could not run.
