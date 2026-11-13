# UserSpace

Rootless application management for Linux, in Java + Swing.

UserSpace is a user-space (no `sudo`) manager for software you install yourself: AppImages,
extracted/portable trees, standalone binaries and Java applications. It centralises what you would
otherwise do by hand — keep a registry of what is installed, generate `.desktop` entries, create
wrappers and `PATH` links, manage per-application environment variables, and detect broken
installations — while touching nothing outside your home directory.

**Status: milestones M0–M2 are implemented.** There is no GUI yet; the project is a compiling,
tested persistence and locking foundation. See [current step.md](current%20step.md) for exactly what
is done and what is next, and [ARCHITECTURE.md](ARCHITECTURE.md) for the design.

## Build and test

Only a JDK (17 or newer) is required. No Maven, no Gradle, no downloads.

```bash
./build.sh          # compile + build/userspace.jar
./build.sh run      # compile + run
./build.sh test     # compile + run the test suite (no root, no display needed)
./build.sh clean
```

The build targets `--release 17`, so the artifact runs on Debian 12 (Java 17) as well as on newer
JDKs.

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

## Layout at runtime

```
$XDG_DATA_HOME/userspace/apps/<id>/meta.properties   registered applications
$XDG_CONFIG_HOME/userspace/settings.properties       preferences
$XDG_STATE_HOME/userspace/lock                       cross-process write lock
```

## Licence

Not chosen yet.
