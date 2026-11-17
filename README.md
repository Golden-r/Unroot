# UserSpace

Rootless application management for Linux, in Java + Swing.

UserSpace is a user-space (no `sudo`) manager for software you install yourself: AppImages,
extracted/portable trees, standalone binaries and Java applications. It centralises what you would
otherwise do by hand — keep a registry of what is installed, generate `.desktop` entries, create
wrappers and `PATH` links, manage per-application environment variables, and detect broken
installations — while touching nothing outside your home directory.

**Status: milestones M0–M3 and packaging (M8) are implemented.** The tool registers applications,
keeps them in crash-safe property files under a cross-process lock, finds new ones by scanning the
directories you point it at, and ships as a relocatable directory with its own runtime. There is no GUI
and no integration yet: desktop/shell launchers and undo are next. See
[current step.md](current%20step.md) for exactly what is done and what is next, and
[ARCHITECTURE.md](ARCHITECTURE.md) for the design.

## Build, test, package

Only a JDK (17 or newer) is required. No Maven, no Gradle, no downloads.

```bash
./build.sh                              # compile + build/userspace.jar
./build.sh run                          # compile + run
./build.sh test                         # compile + run the test suite (no root, no display needed)
./build.sh package                      # app-image in build/dist/UserSpace, then verify it
./build.sh deb                          # optional .deb for maintainers (needs dpkg-deb + fakeroot)
java -jar build/userspace.jar --scan    # discovery: list proposed applications
./build.sh clean
```

The build targets `--release 17`, so the artifact runs on Debian 12 (Java 17) as well as on newer
JDKs.

### Finding applications

`--scan` walks the roots listed in `settings.properties` (`scan.root.0=…`) and prints what it
recognises: AppImages by their magic bytes, ELF binaries, extracted trees that contain `AppRun`,
jars with a `Main-Class`, and simple `.desktop` files. Each proposal carries a confidence and a reason,
and **nothing is registered** — the point is a reviewable list, not a `find`-and-commit.

The walk is bounded in depth, never follows symlinks, skips UserSpace's own directories, and can be
cancelled. Anything it could not use — an unreadable directory, a jar without `Main-Class`, an
`Exec` that needs quoting — is reported rather than silently dropped.

### The packaged artifact

`./build.sh package` produces `build/dist/UserSpace/`, a directory you can move or copy anywhere and
run straight from there — no installer, no `sudo`, no JDK on the machine:

```bash
build/dist/UserSpace/bin/UserSpace
```

It bundles a `jlink` runtime built from `java.base` (55 MB in total) and then checks itself: the
launcher is run under an empty environment with only `PATH`, `HOME` and `XDG_*`, the settings path it
reports must land inside that sandbox, created directories must be `0700`, and a second instance must
exit `3` while another process holds the lock. `.deb` output is deliberately secondary: it installs
under `/opt/userspace` with root, which is the opposite of what this tool is for.

`./build.sh package` compiles the test harness (the packaged smoke check needs it as a second
process) but does not run the 53-case suite — run `./build.sh test` as well before publishing a
build.

## Design in one paragraph

Everything the application knows is a small, human-readable file under the XDG directories, written
atomically (`temp file → fsync → rename`) and never outside `$HOME`. Every modification is planned
before it is performed, so it can be reported and undone. Metadata (yours) is kept separate from
generated artefacts (ours), which is what makes "uninstall" mean exactly that. Cross-process writes
are serialised by an OS file lock, so two instances — or a future CLI — cannot corrupt the registry.
No frameworks, no dependency injection, no interfaces without a second implementation.

## Documentation

| File | Contents |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | packages, domain model, persistence and locking rules, dependency map, roadmap, risks |
| [current step.md](current%20step.md) | where the implementation stands and what to do next |
| [VERIFICATION-M1.md](VERIFICATION-M1.md) | what persistence was verified to do, and what was not tested |
| [VERIFICATION-M2.md](VERIFICATION-M2.md) | settings and locking verification, including the negative controls |
| [VERIFICATION-M3.md](VERIFICATION-M3.md) | discovery verification: the recognised kinds, the refusals, and the controls that prove they fail |
| [VERIFICATION-M8.md](VERIFICATION-M8.md) | packaging verification: the nine artifact checks, the controls that prove they can fail |

## Layout at runtime

```
$XDG_DATA_HOME/userspace/apps/<id>/meta.properties   registered applications
$XDG_CONFIG_HOME/userspace/settings.properties       preferences
$XDG_STATE_HOME/userspace/lock                       cross-process write lock
```

## Licence

Not chosen yet.
