# M3 — Discovery: verification report

Environment: Debian-family Linux, non-root user `ar253104`, JDK 25 compiling with `--release 17`.

## Commands run and their results

| Command | Result |
|---|---|
| `./build.sh test` | 40 main + 14 test sources, `-Xlint:all` **no warnings**, **71 passed, 0 skipped**, exit 0 |
| same command after the negative controls were reverted | identical: 71 passed, exit 0 |
| `java -jar build/userspace.jar --scan` on a fixture tree | 4 candidates, correct types and confidences, **no registration**, exit 0 |
| `--help` | usage text, exit 0 |
| an unknown option | usage on stderr, exit **64** |
| `--scan` with no scan roots configured | explains where to set `scan.root.0`, exit 0 |

## Requirement mapping

| Requirement (current step.md, M3) | Evidence | Result |
|---|---|---|
| Scan `settings.scanRoots`, never auto-register | CLI run: `Registered applications: 0` before and after, `apps/` stays empty, `Main` only reads the registry to skip known executables | pass |
| AppImage by magic **or** suffix | `AppImage confidence follows the magic bytes, not the name`: magic → `HIGH`, suffix without magic → `MEDIUM` with a reason naming the missing magic | pass |
| ELF binary by header + exec bit | `one of each supported kind`: `bintool` → `BINARY`; `an ELF file without the executable bit is ignored` | pass |
| Extracted tree via `AppRun` | `tree` → `PORTABLE`, install root = the directory, walk does not descend into it | pass |
| Runnable jar via manifest `Main-Class` | `standalone` → `JAVA_APP`; a jar without `Main-Class` is reported, not proposed | pass |
| `*.desktop` in user directories | simple `Exec=` imported as `CUSTOM`/`LOW`; quoting, field codes or a relative path are refused with a reason | pass |
| Confidence per candidate, plus a reason | every case asserts the confidence and, for the interesting ones, the reason text | pass |
| Skip duplicates against the registry, report them | `an already registered executable is skipped and named`: skipped **and** one issue saying "already registered" | pass |
| Bounded walk | depth limit case (default 6) and the `truncated` flag at 200 candidates | pass |
| Skip our own data directories | `UserSpace's own directories are never scanned`, with the data home deliberately *inside* the scanned root | pass |
| Never follow symlinks out of the root | `a symlink loop does not escape the root and does not hang` | pass |
| Cancellable | `cancellation stops the walk immediately`: exactly one poll, zero candidates, `cancelled=true` | pass |

The suite went from 53 to 71 cases; the 18 new ones are all in `DiscoveryTests`.

## What the CLI actually printed

Fixture tree: `Tool.AppImage` (ELF + `AI\x02`), `bintool` (ELF), `Tree/AppRun`, `jarapp/standalone.jar`,
with `settings.properties` naming that directory as the single scan root.

```
Registered applications: 0

Discovery — 1 root(s), 3 file(s) inspected in 3 director(ies), 16 ms
  bintool                  BINARY    high   /tmp/…/apps/bintool
      why: ELF executable
  standalone               JAVA_APP  high   /usr/lib/jvm/…/bin/java
      why: runnable jar (Main-Class com.example.Main)
  tool                     APPIMAGE  high   /tmp/…/apps/Tool.AppImage
      why: AppImage (ELF file carrying the AppImage magic)
  tree                     PORTABLE  high   /tmp/…/apps/Tree/AppRun
      why: extracted application tree (AppRun)
Nothing was registered: these are proposals to review.
```

Four applications from three inspected files is correct, not a bug: `Tree/AppRun` is reached through
its directory (one candidate, subtree skipped), so the file itself is never visited.

## Negative controls (the tests can fail)

Each control was applied alone, the suite run, and the file restored from a copy; `grep -rn "NEGATIVE
CONTROL" src/` is empty and the suite is green afterwards.

**Control 1 — stop excluding our own directories** (`if (isExcluded(dir) && false)`):

```
FAIL  UserSpace's own directories are never scanned
      our own wrappers inside a scanned home must not be proposed as applications
      expected: <[real]>
      actual:   <[generated-wrapper, real]>
```

This control is also why that case was rewritten during the milestone: the first version scanned a
root that did *not* contain the XDG data directory, so it only exercised the "this whole root is
ours" check and passed with the per-directory exclusion disabled. The data home now lives inside the
scanned root, which is the realistic home-directory case.

**Control 2 — never allocate a second id** (`uniqueId` returns the base slug):

```
FAIL  two applications with the same name get different ids
      the second keeps the name and gets a suffix
      expected: <[tool, tool-2]>
      actual:   <[tool, tool]>
```

## Not tested / known limitations

1. **No real AppImage or real `.desktop` file was scanned.** Every fixture is minimal and synthetic:
   64-byte ELF headers, a two-entry jar. A real type-2 AppImage carries a SquashFS payload and a
   runtime; the classifier only reads the first 16 bytes, so this is very likely fine, but it has not
   been run against a file produced by `appimagetool`.
2. **Two copies of the same application at different paths are still two candidates.** Duplicate
   detection is path identity, and content hashing is not implemented (same limitation as the
   registry; M2b fixes hardlinks, not copies).
3. **A symlinked *file* is skipped entirely** — including a symlink whose target lies inside the
   scan root. The safer rule (never follow) was chosen deliberately; a directory symlink is reported,
   a file symlink is not, because it is indistinguishable from an ordinary file in the walk.
4. **`Exec` handling refuses more than it could accept.** Only a single unquoted absolute path is
   imported; `%U`-style field codes, arguments and quoting produce an issue. That is a deliberate
   refusal, not a gap: a wrong guess starts the wrong program.
5. **The jar interpreter is whichever JVM runs the scan.** Recorded as an absolute path, so the
   candidate is reproducible, but a user who wants a different JVM has to edit it. `JAVA_HOME`/`PATH`
   probing is M4/M6 work.
6. **`AppRun` is not executed or validated beyond the executable bit** (`--appimage-extract-and-run`,
   FUSE availability and architecture mismatches are M6 health checks).
7. **No dedup of `.desktop` files against the applications they point at**, and no import of
   `OnlyShowIn`/`NotShowIn`, `MimeType` or try-exec semantics.
8. **Scanning is single-threaded** and cancellability is polled per entry; on a very large tree the
   response to a cancel request is bounded by one directory read, which was not measured.
9. **The non-ASCII banner prints `?` under a locale-less environment** (`env -i … | head`), because
   `stdout.encoding` falls back to ASCII. The packaged launcher sets `-Dstdout.encoding=UTF-8`, so
   this is only visible when running the bare jar without a terminal locale.

## Files touched

New: `util/Slug`, `discovery/DiscoveryScanner`, `discovery/FileClassifier`, `discovery/Proposal`,
`discovery/DiscoveryCandidate`, `discovery/DiscoveryResult`, `discovery/DiscoveryIssue`,
`discovery/Confidence`, `test/DiscoveryTests`, `VERIFICATION-M3.md`.

Changed: `Main` (`--scan`, `--help`, unknown-option exit 64, discovery report), `discovery/package-info`,
`test/TestMain`, `ARCHITECTURE.md`, `README.md`, `current step.md`. No model, config or registry code
was modified: discovery reads the registry only as a set of already-known executables.
