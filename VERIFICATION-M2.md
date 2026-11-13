# M2 — Settings and cross-process locking: verification report

Environment: Debian-family Linux, non-root user `ar253104`, JDK 25 compiling with `--release 17`
(class file major version 61), ext4/tmpfs under `/tmp`.

## Commands run and their results

| Command | Result |
|---|---|
| `./build.sh test` | 32 main + 13 test sources, `-Xlint:all` **no warnings**, **53 passed, 0 skipped**, exit 0 |
| same command, three consecutive runs | identical result (concurrency cases are not flaky in practice) |
| `java -jar build/userspace.jar` (sandboxed `XDG_*`) | settings defaults, then invalid-value issues, exit 0 |
| same, while another process held the lock | exit **3** with `pid=… since=…` of the holder |
| same, after the holder exited | exit 0, lock file `600` |

## Requirement mapping

| Requirement | Evidence | Result |
|---|---|---|
| Settings persistence | 11 cases: absent file → defaults, round-trip, empty roots, invalid values → defaults + issues, missing scan-root entry, newer schema refused and file untouched, unknown keys preserved, unchanged data not rewritten, `0600`/`0700`, save into read-only dir fails, unreadable file → defaults + issue | pass |
| `FileLock` single-instance guard | `LockTests` (re-entrancy, ref counting, shared instance per path, lock file `0600` + owner pid, close releases) and end-to-end exit code 3 while another process holds it | pass |
| Two-process test proving writes cannot interleave | 4 cases in `ConcurrencyTests`, each spawning real child JVMs through `Child` | pass |

## What the concurrency tests actually do

`Child hold` takes the lock, logs `ENTER <label>`, sleeps 150 ms, logs `EXIT <label>`, releases. Three
children run concurrently and the parent asserts the log never nests:

```
ENTER one
EXIT one
ENTER two
...
```

`Child register` loads the registry, waits on a **barrier file**, then registers. The barrier is the
important part: without it the children serialize naturally (each finishes before the next starts) and
the test would pass even with no lock at all. With it, all three have loaded an empty registry before
any of them writes — a genuine race.

## Negative controls (the tests can fail)

Both controls were applied temporarily, the suite run, and the patch reverted; `grep -rn "NEGATIVE
CONTROL" src/` is empty and the suite is green afterwards.

**Control 1 — make the lock a no-op** (`acquireNow()` returns `true` without taking an OS lock):

```
FAIL  another process holding the lock makes tryLock and lock(timeout) fail
      the lock must not be available while another process holds it
FAIL  critical sections from three processes never overlap
      two processes were inside the critical section at once:
      [ENTER one, ENTER three, ENTER two, EXIT one, EXIT three, EXIT two]
FAIL  the same id cannot be registered by two processes
      ... shared-0 REGISTERED (twice) ...
```

So the ordering assertion observes real mutual exclusion rather than a lucky schedule.

**Control 2 — drop the on-disk id re-check** inside the critical section (lock still working):

```
FAIL  the same id cannot be registered by two processes
      exactly one process may win each id:
      OUTCOME shared-0 REGISTERED   (from process 1)
      OUTCOME shared-0 REGISTERED   (from process 2)
      ... 10 REGISTERED instead of 5 ...
```

That proves the `Files.exists(meta.properties)` re-check is load-bearing: the in-memory duplicate check
is stale by construction once another process has written.

## Verified by inspection rather than by test

- The lock file's content (`pid=… since=…`) is informational; `tryLock` is always the source of truth.
  Confirmed indirectly: deleting the content does not affect acquisition.
- A killed process does not leave a stale lock (kernel releases it). Observed while iterating: the
  `wait`/`destroyForcibly` paths in the tests never blocked a later run.

## Not tested / known limitations

1. **NFS and other network filesystems.** `FileLock` over NFS is historically unreliable; the code
   degrades to plain atomic renames there but that combination is unverified.
2. **No interruption test** for `FileMutex.lock` while waiting: the `InterruptedException` →
   `LockBusyException` path is implemented but not exercised.
3. **No fairness guarantee.** Acquisition polls; a process can starve another in principle. With a GUI
   holding the lifetime lock and mutations lasting milliseconds, this is acceptable and unmeasured.
4. **Cross-process executable duplicates are still in-memory only.** Two processes could register
   *different* ids pointing at the same binary; the id collision is caught, the path collision is not.
   Documented in ARCHITECTURE.md §4.10.
5. **`ownerDescription` can read a half-written owner line** if it races an acquisition. It is
   diagnostics-only and never used for decisions.
6. **Settings write failures** are tested only through the read-only-directory case; ENOSPC and quota
   exhaustion are not simulated.
7. **Still absent by design:** `integration.properties`, the journal/undo (M7), discovery (M3), the
   desktop and shell layers (M4–M5), and any Swing code.

## Files touched

New: `system/FileMutex`, `config/Settings`, `config/SettingsStore`, `config/SettingsLoad`,
`test/Child`, `test/SettingsTests`, `test/LockTests`, `test/ConcurrencyTests`.
Changed: `core/registry/ApplicationRegistry` (mutex + on-disk id re-check + `LOCK_UNAVAILABLE`),
`core/registry/RegistryResult` (new outcome), `Main` (settings, lock, exit code 3), package docs,
`ARCHITECTURE.md`, `test/TestMain`.
