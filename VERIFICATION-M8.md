# M8 — Packaging: verification report

Environment: Debian-family Linux, non-root user `ar253104`, Oracle JDK 25.0.4.1 (`jlink` and
`jpackage` from it), compiling with `--release 17`. `dpkg-deb` and `fakeroot` present.

## Commands run and their results

| Command | Result |
|---|---|
| `./build.sh package` | links a `java.base` runtime, builds `build/dist/UserSpace`, then runs 9 checks on the packaged launcher: **9 PASS, exit 0**, `55M` |
| `./build.sh deb` | `build/dist/userspace_1.0.0_amd64.deb`, 12 440 372 bytes, `dpkg-deb --info` reads it, **exit 0** |
| `./build.sh test` | unchanged: **53 passed, 0 skipped**, exit 0 |
| image copied to `/tmp/elsewhere/` and run | starts, exit 0 — the app-image is relocatable |
| `find build/dist -not -user $(id -u)` | empty — every shipped file is owned by the packaging user |

## What the packaging actually produces

```
build/dist/UserSpace/
├── bin/UserSpace                 18 KB native launcher: it execs the bundled runtime
└── lib/
    ├── runtime/                  jlink image: java.base only, 53M
    ├── app/userspace.jar         60K — the jar built in the same run
    ├── app/UserSpace.cfg         app.classpath, main class, the -D options
    └── libapplauncher.so
```

`lib/app/UserSpace.cfg` carries the three encoding options, which are not cosmetic: with
`X11`-less launchers and no locale, `stdout.encoding` otherwise falls back to ASCII and the banner
prints `UserSpace ? layout…`. This was observed, then fixed:

```
before:  UserSpace ? layout, settings and registry loaded (no UI yet)
after:   UserSpace — layout, settings and registry loaded (no UI yet)
```

## The nine checks the build runs on the artifact

`./build.sh package` does not stop at "jpackage exited 0". It runs the launcher the way a desktop
session would — `env -i` with only `PATH`, `HOME` and the four `XDG_*` variables — and asserts:

1. `bin/UserSpace` exists and is executable.
2. `lib/runtime/bin/java` exists: a runtime really is bundled.
3. `cmp` between `build/userspace.jar` and `lib/app/userspace.jar`: the image holds the jar just
   built, not a stale one.
4. the launcher exits 0.
5. it reports the loaded layout.
6. the settings path it echoes is inside the sandbox `XDG_CONFIG_HOME`, so `XDG_*` is honoured and
   `$HOME` defaults were not used.
7. the directories it created are `0700` (`apps: 700`).
8. while a second process holds the lock, the launcher exits **3**.
9. that run names the holder (`pid=… since=…`) instead of writing.

Check 8 is the one that costs something: the holder is `org.uspace.test.Child hold` executed by the
**bundled** runtime (`lib/runtime/bin/java -cp build/classes:build/test-classes`). That single step
proves two things at once — the single-instance guard still works after packaging, and `java.base`
is enough for `FileChannel.tryLock`, so the runtime module list is not silently too small.

## Negative controls (the checks can fail)

Run by hand, outside the script, and reverted by rebuilding afterwards.

**Control 1 — remove the bundled runtime from a copy of the image:**

```
$ cp -a build/dist/UserSpace /tmp/broken && rm -rf /tmp/broken/lib/runtime
$ env -i PATH=/usr/bin:/bin HOME=/tmp/nohome /tmp/broken/bin/UserSpace
Failed to find JVM in "/tmp/broken/lib/runtime" directory.
RC=1
```

So check 2 and check 4 measure the image, not the system JDK: there is no fallback to `/usr/bin/java`,
which is exactly what makes the artifact portable to a machine without a JDK.

**Control 2 — launch with the lock free** (no holder process, same sandbox):

```
RC=0
```

Check 8 can therefore only pass because a real second process held the lock; an always-3 launcher
would fail the "exits 0" check, and a launcher that never reports a conflict would fail check 8.

**Control 3 — a stale or wrong jar** is caught by check 3 by construction: it `cmp`s the shipped jar
against `build/userspace.jar` from the same run, so a leftover image or a jar staged from the wrong
directory fails instead of being reported as a fresh build. This one is argued rather than observed —
there was no stale image left to trip it here.

## The `.deb`, and why it is not the recommended artifact

`./build.sh deb` produces an ordinary Debian package:

```
Package: userspace        Version: 1.0.0        Architecture: amd64
Depends: libc6, zlib1g    Installed-Size: 55397  Description: Rootless application manager for Linux
./opt/userspace/…         plus preinst/postinst/prerm/postrm maintainer scripts
```

It installs under `/opt/userspace` and therefore needs **root**, which is the opposite of the
project's premise. It exists because the roadmap lists it as an optional maintainer channel, and it
is documented as such rather than offered as the normal way to run UserSpace. Two consequences
worth keeping: `Maintainer:` comes from `--vendor` and reads `UserSpace <Unknown>` until a packager
passes their own identity, and nothing verifies the installed package (installing it here would need
`sudo`, which is out of scope).

## Not tested / known limitations

1. **Any other machine.** The image was built and run on this host only. It is `x86_64` with
   `glibc >= 2.6`-era interpreter requirements; a `aarch64` build needs packaging on that
   architecture, since `jlink` cannot cross-link a runtime for another platform.
2. **The `.deb` was never installed.** Only its metadata and file list were inspected.
3. **No desktop integration yet.** There is no `.desktop` file inside the image and no menu entry, so
   M4's `desktop-file-validate` check (available on this host) is still unused.
4. **`java.desktop` is not in the runtime** because nothing in M0–M2 uses Swing. Adding the UI will
   make the packaged launcher fail until `RUNTIME_MODULES` grows — the smoke run is what will say so.
5. **The runtime is not compressed.** `jlink --compress` was deliberately left out: the flag's syntax
   changed across JDK generations (`--compress=2` vs `--compress=zip-6`), and the build targets
   Debian 12's JDK 17 as well as this JDK 25. 55 MB uncompressed against roughly 25 MB compressed.
6. **No signing, no checksums, no reproducible-build flags.** `jpackage` embeds a timestamp, so two
   runs of `./build.sh package` are not byte-identical.

## Files touched

New: `VERIFICATION-M8.md`. Changed: `build.sh` (`package`, `deb`, `verify_package`, `jlink` runtime),
`README.md`, `ARCHITECTURE.md`, `current step.md`. No Java source changed for this milestone, which is
why the 53-case suite result above is the same one M2 recorded.
