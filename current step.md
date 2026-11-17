# Current step

Last updated after M3 (discovery). Branch `main`, 40 main sources + 14 test sources, suite green.

```bash
./build.sh test                        # 71 passed, 0 skipped, 0 failed
./build.sh package                     # build/dist/UserSpace, then 9 checks against the launcher
java -jar build/userspace.jar --scan   # discovery (M3): proposals only, nothing registered
```

## Done

| Milestone | What exists | Proof |
|---|---|---|
| **M0** | `build.sh` (JDK only), `XdgDirectories`, `Main` that refuses to run as root, prepares the layout and reports it | compiles at `--release 17`, runs, creates `0700` directories |
| **M1** | `PropertiesFile` (UTF-8, temp-file + fsync + atomic rename, non-atomic fallback), `ApplicationCodec`, `ApplicationStore`, `ApplicationRegistry`, `RegistryResult`, `MetadataIssue`/`LoadReport` | unknown-key preservation, schema-version refusal, permissions, crash-safe write behaviour |
| **M2** | `Settings`/`SettingsStore`/`SettingsLoad`, `FileMutex`, mutations under the lock, single-instance guard in `Main` (exit `3`), `LOCK_UNAVAILABLE` outcome, on-disk id re-check | four child-JVM concurrency cases; negative controls in `VERIFICATION-M2.md` |
| **M3** | `discovery`: `DiscoveryScanner` (bounded, cancellable, symlink-safe walk), `FileClassifier` (AppImage magic/suffix, ELF, `AppRun` trees, runnable jars, simple `.desktop` imports), `DiscoveryCandidate`/`Result`/`Issue`/`Confidence`, `Slug`, `Main --scan` | 18 new cases (71 total); negative controls in `VERIFICATION-M3.md`; CLI run prints 4 candidates from a fixture tree with zero registrations |
| **M8** | `./build.sh package` (jlink `java.base` runtime + `jpackage --type app-image`) and `./build.sh deb`; the packaged launcher is verified, not just built | 9 artifact checks green (`VERIFICATION-M8.md`), 55 MB image that runs after being copied elsewhere |

Discovery is deliberately read-only: it proposes, the user decides, and `--scan` therefore leaves an
empty registry behind. That is the seam M4 will plug into — `core.service` will take a candidate,
register it, then integrate it.

## Next, in order

### M2b — inode equality (small, close the known gap)

Duplicate detection still uses `toRealPath()`, in two places that must agree
(`ApplicationRegistry.identity` and `DiscoveryScanner.identity`). That catches symlinks but **not
hardlinks**.

- Compare `(device, inode)` from `Files.readAttributes(path, BasicFileAttributes.class).fileKey()`
  when the file exists; fall back to the real path when `fileKey()` is `null` (some filesystems).
- Do it in **one** place (a `system` helper) and have both callers use it, so a file cannot be a
  duplicate for the registry and novel for the scan.
- Tests: hardlink to an already registered binary → `DUPLICATE_EXECUTABLE`; hardlinked discovery
  candidate skipped; a not-yet-existing path falls back without throwing. Two *mounts* of the same
  file cannot be created without root, so that stays documented as untested.

### M4 — desktop integration

- `IntegrationPlan` / `IntegrationPlanner` / `IntegrationApplier` as designed in ARCHITECTURE §1–2.
- Wrapper script in `$XDG_DATA_HOME/userspace/bin/<id>`, `.desktop` in
  `$XDG_DATA_HOME/applications/userspace-<id>.desktop`, icon copied to `userspace/icons/`.
- `Exec=` stays trivial (one wrapper path + `%U`); quoting lives in `sh`.
- `core.service` arrives here: `register(candidate)` → registry → integrate, so the CLI can finally
  turn a proposal into an installed application.
- Verify with `desktop-file-validate` — installed on this machine, and M8's image still has no
  `.desktop` file, so this check has never run.
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
root, broken artefact (recorded path gone or not ours), AppImage needing FUSE. Repair = de-integrate +
re-integrate. This is also where discovery's `MEDIUM` candidates get re-examined with real probes
instead of a 16-byte header.

### M7 — journal and undo

Pre-images into `$XDG_STATE_HOME/userspace/journal/<op-id>/` with a manifest; "undo last change"
replays backwards; prune by age at startup. This is the milestone that makes the reversibility claim
in the README true for integration, not just for removal.

### UI — the window

Still unscheduled as its own milestone; ARCHITECTURE §5 step 7 says "not implemented". The rule stands:
`ui` imports no `java.nio.file` and no `ProcessBuilder`, all IO off the EDT, and `RUNTIME_MODULES` in
`build.sh` grows to `java.desktop` when Swing arrives — `./build.sh package` fails until it does.

## Open limitations (carried forward, do not lose track)

From `VERIFICATION-M2.md`, `VERIFICATION-M3.md` and `VERIFICATION-M8.md`:

1. **`FileLock` semantics on NFS** are unverified; the code degrades to atomic renames but the
   combination is untested.
2. **`InterruptedException` inside `FileMutex.lock`** (while polling) is implemented but not exercised.
3. **No fairness guarantee** — acquisition polls, so starvation is possible in principle and unmeasured.
4. **Cross-process duplicate detection by executable path is in-memory only.** The id collision is
   checked on disk inside the lock; the path collision is not. Fix together with M2b.
5. **No real AppImage or real `.desktop` file has ever been scanned.** Discovered fixtures are
   synthetic (64-byte ELF headers, a two-entry jar), so the AppImage path has not met a file produced
   by `appimagetool`.
6. **Two copies of the same application at different paths are two candidates.** Discovery identity is
   path identity; content hashing does not exist.
7. **Symlinked files are skipped entirely**, including links whose target is inside the scan root. A
   directory symlink is reported; a file symlink is invisible.
8. **`Exec` import refuses more than it could accept**: only a single unquoted absolute path. Field
   codes, arguments and quoting produce an issue instead of a guess.
9. **Jar interpreter is the JVM that ran the scan**, recorded as an absolute path; `JAVA_HOME`/`PATH`
   probing is M4/M6 work. `AppRun` is not validated beyond the executable bit (no FUSE, no
   architecture check) — M6.
10. **`integration.properties` and the journal/undo** do not exist yet (M4/M7).
11. **Shell layer untouched**; nothing has been exercised against a real shell configuration.
12. **`ENOSPC`/quota failures** are not simulated; only read-only directories are.
13. **`ownerDescription`** may read a partially written line during a race — diagnostics only.
14. **Packaging is single-architecture and single-machine**; `jlink` cannot cross-link, so an `aarch64`
    build must be produced there. The `.deb` was never installed, needs root, and its `Maintainer:`
    reads `UserSpace <Unknown>` until a packager passes an identity. No signing, not reproducible.
15. **Scanning is single-threaded**; cancel response is bounded by one directory read and unmeasured.

## Traps already paid for (do not re-learn these)

- **`Properties.store` always writes a date comment.** Comparing encoded data with the file and
  skipping unchanged writes is why `mtime` is stable; do not "simplify" that away.
- **Prefix collisions between a count key and its entries.** `launch.argument.` matched
  `launch.argument.count`, so an empty list looked like orphaned data. Entry prefixes are now
  `launch.arguments.`, `env.`, `scan.root.` — keep them disjoint from their count keys.
- **`Properties.load(InputStream)` is ISO-8859-1.** Always use the `Reader`/`Writer` overloads.
- **A blocking `FileLock.lock()` cannot be interrupted**, hence the polling loop with a deadline.
- **Concurrency tests without a barrier are vacuous.** `Child register` waits for the parent to create
  a barrier file, so every process has loaded the registry before any writes.
- **`FileMutex.forFile` shares one instance per path**; two channels on the same file inside one JVM
  throw `OverlappingFileLockException`.
- **`jpackage --input` ships everything in the directory.** The jar is staged into
  `build/package-input`, and the build `cmp`s the shipped jar against the one it just built.
- **`java.base` alone is a real runtime** (it has `FileChannel.tryLock`), but it has no Swing: the UI
  needs `java.desktop` in `RUNTIME_MODULES`.
- **A locale-less launcher writes `?` instead of non-ASCII**; the packaged launcher passes
  `-Dstdout.encoding=UTF-8` and `-Dstderr.encoding=UTF-8`.
- **`jlink --compress` syntax changed** (`--compress=2` → `--compress=zip-N`), so it is left out to keep
  one script working on JDK 17 and JDK 25.
- **An exclusion test must put the excluded directory inside the scan root.** The first version of the
  "own directories" case scanned a root that did not contain the XDG data home, so it passed with the
  per-directory exclusion disabled — caught by the negative control, not by review.
- **Java rejects `while (false)`** — a negative control needs a condition the compiler cannot fold, or
  the patch does not compile.
- **A `.AppImage` test fixture must be executable.** The first version of the "one of each kind" case
  wrote the magic bytes but forgot `setExecutable`, and the missing candidate was a test bug, not a
  classifier bug.

## Conventions

- Java records, validation in compact constructors, no IO in `core.model`.
- Checked `ConfigException` for persistence; results-as-values (`RegistryResult`, `DiscoveryResult`)
  for expected outcomes.
- Persist before mutating memory; discovery proposes before anything is persisted.
- Package-private types for internals (`Proposal`, `FileClassifier`), public only for the seam the UI
  and the service need.
- Tests use their own temporary directories, never `$HOME`, and skip (never fake) permission cases when
  run as root.
- `./build.sh test` must be green before a commit, and `./build.sh package` before claiming a build
  ships; report anything that could not run.
