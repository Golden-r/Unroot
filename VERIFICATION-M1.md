# M1 — Persistence Foundation: verification report

Environment: Debian-family Linux, non-root user `ar253104`, JDK 25 compiling with `--release 17`
(class file major version 61), ext4/tmpfs under `/tmp`.

## Commands run and their results

| Command | Result |
|---|---|
| `./build.sh` | 28 main sources, `javac --release 17 -Xlint:all` **no warnings**, jar built, exit 0 |
| `./build.sh test` | 28 main + 5 test sources compile; **33 passed, 0 skipped, 33 total**, exit 0 |
| `java -jar build/userspace.jar` (real `$HOME`) | layout resolved, `Registered applications: 0`, exit 0 |
| `java -jar build/userspace.jar` (sandboxed `XDG_*`) | 0 apps on empty sandbox; then 1 app + 2 reported issues, exit 0 |

The sandbox run seeded `apps/media-player/meta.properties` (valid), `apps/broken/` (metadata with only
`schema.version`) and `apps/future/` (schema 999), then started the application through its normal
entry point:

```
Registered applications: 1
  media-player         PORTABLE  /home/user/Apps/Media Player/run.sh
Unreadable registrations (left untouched):
  .../apps/broken/meta.properties: required value is missing (key 'id')
  .../apps/future/meta.properties: written by a newer UserSpace (schema 999 > 1) (key 'schema.version')
```

The schema-999 file was re-read afterwards and is byte-identical to what was written: a refused file is
never rewritten. No `*.tmp` files remained anywhere under the sandbox or the probe directory.

## Required test scenarios

| Requirement | Test case | Result |
|---|---|---|
| Basic serialization round-trip | `every field survives write and read` | pass |
| Unicode application names | `unicode names and descriptions are stored literally` (also asserts the file contains no unicode escapes) | pass |
| Paths containing spaces | `paths containing spaces round-trip` | pass |
| Values containing `=` | `values containing '=' round-trip` (asserts the value is not truncated at the first `=`) | pass |
| Empty optional values | `empty optional values stay absent` (null root/icon, `""` description, empty lists, no `icon=` key written) | pass |
| Multiple environment variables | `multiple environment variables keep their order` | pass |
| Missing metadata | `missing metadata file is reported and other applications still load` | pass |
| Corrupted metadata | `corrupted metadata is reported, not thrown` (binary garbage + unknown `type`) | pass |
| Duplicate registrations | `duplicate id is rejected`, `duplicate executable under another id is rejected`, `a symlink to a registered executable is a duplicate` | pass |
| Application removal | `removal deletes our metadata and leaves foreign files alone`, `removal clears memory and disk` | pass |
| Atomic write failure handling | `an unwritable directory fails the write, keeps the old file and leaves no temp file` | pass |
| Schema version compatibility | `schema version 1 loads, a newer version is refused, a missing version is treated as 1` | pass |

Additional cases beyond the checklist: unknown-property preservation across an update, no-rewrite when
data is unchanged, non-atomic fallback move, `0600`/`0700` permissions, id/directory mismatch, invalid
directory name, absent applications directory, display-name ordering, persist-before-mutate on store
failure, one corrupt registration not hiding the others, and `ConfigException` on impossible paths.

## Bugs the tests found (both fixed)

1. **Prefix collision.** `launch.argument.` and `env.` were prefixes of their own count keys
   (`launch.argument.count`, `env.count`), so every application with an empty argument list was decoded
   as "orphaned arguments without a count". Fixed by making entry prefixes distinct from count keys
   (`launch.arguments.<i>`, `environment.count`).
2. **Silent rewrite of unchanged data.** `Properties.store` always emits a date comment, so saving an
   application whose data had not changed still produced different bytes and a new mtime. Fixed by
   comparing the encoded properties with the file and skipping the write entirely.

## Verified by inspection rather than by test

- `0600` file and `0700` directory modes: asserted by tests **and** confirmed with `stat` on a real
  generated file (`600`, `700`).
- UTF-8 vs ISO-8859-1 behaviour, `=`/`:` escaping, leading/trailing space survival: confirmed first
  with a scratch probe of `Properties.store(Writer)`/`load(Reader)` before the codec was written, then
  locked down by the tests above.

## Not tested / known limitations

1. **The `AtomicMoveNotSupportedException` branch is not exercised.** The fallback *method* is tested
   directly (`the non-atomic fallback replaces an existing file`), but no filesystem here refuses
   `ATOMIC_MOVE`, so the catch itself has never run. Behaviour on NFS/some FUSE mounts is unverified.
2. **Crash durability is not proven.** `FileChannel.force(true)` is called before the rename, but no
   test kills the JVM mid-write. The guarantee is "old content survives", not "no loss on power failure".
3. **No cross-process serialization.** `FileLock` is still unimplemented (M2). Two instances can
   interleave writes; each write is atomic, but last-writer-wins at the property level.
4. **The root-refusal path is untested** — it requires a root shell.
5. **Non-POSIX filesystems are untested.** The permission attributes are skipped when the filesystem
   does not support `posix`, but only ext4/tmpfs were available.
6. **An unreadable applications directory** (permission denied on listing) surfaces as a
   `ConfigException` from `loadAll`; no test covers that branch.
7. **Hand-edited files with CRLF line endings or ISO-8859-1 bytes** are read via the declared UTF-8
   reader. Invalid UTF-8 becomes a reported `MetadataIssue` rather than a crash; neither case is tested.
8. **Collection size limits** are enforced (`1000` entries) but only the happy path is tested.
9. **`settings.properties` is not implemented** in this milestone; `Main` prints its path only.
10. **Discovery, desktop and shell integration are untouched**, so nothing here was exercised against a
    real AppImage, `.desktop` file or shell configuration.

Tests were run as a normal user, so the permission-failure cases ran for real rather than being skipped
(`0 skipped`). As root they would report `SKIP` instead — the suite says so rather than pretending to
pass.
