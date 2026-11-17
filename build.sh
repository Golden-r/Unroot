#!/usr/bin/env bash
# Build, run, test and package UserSpace with nothing but a JDK.
#
#   ./build.sh          compile + package build/userspace.jar
#   ./build.sh run      compile + run
#   ./build.sh test     compile + run the persistence test suite
#   ./build.sh package  app-image with a bundled runtime, verified by a smoke run
#   ./build.sh deb      the same, as a .deb a maintainer can install (needs dpkg/fakeroot)
#   ./build.sh clean    remove build/
set -euo pipefail

cd "$(dirname "$0")"

# A CLASSPATH inherited from the user's shell can inject stale entries into javac.
unset CLASSPATH

MAIN_CLASS="org.uspace.Main"
TEST_CLASS="org.uspace.test.TestMain"
RELEASE="17"
OUT="build/classes"
OUT_TEST="build/test-classes"
JAR="build/userspace.jar"
LINT="-Xlint:all -encoding UTF-8"

APP_NAME="UserSpace"
APP_VERSION="1.0.0"
DIST="build/dist"
RUNTIME="build/runtime"
PACKAGE_INPUT="build/package-input"
# java.base is everything Main and the persistence layer use. `package` fails loudly if this list
# is ever too small — the smoke run below exercises the launcher, so a missing module shows up
# immediately instead of on a user's machine. The Swing UI will add java.desktop.
RUNTIME_MODULES="java.base"

clean() {
    rm -rf build
}

require_jdk() {
    command -v javac >/dev/null 2>&1 || { echo "javac not found; install a JDK 17 or newer" >&2; exit 1; }
}

compile() {
    require_jdk
    rm -rf "$OUT"
    mkdir -p "$OUT"
    find src/main/java -name '*.java' > build/sources.txt
    echo "Compiling $(wc -l < build/sources.txt) source files (release $RELEASE)"
    javac --release "$RELEASE" $LINT -d "$OUT" "@build/sources.txt"
    jar --create --file "$JAR" --main-class "$MAIN_CLASS" -C "$OUT" .
    echo "Built $JAR"
}

compile_tests() {
    require_jdk
    rm -rf "$OUT_TEST"
    mkdir -p "$OUT_TEST"
    find src/test/java -name '*.java' > build/test-sources.txt
    echo "Compiling $(wc -l < build/test-sources.txt) test sources (release $RELEASE)"
    javac --release "$RELEASE" $LINT -cp "$OUT" -d "$OUT_TEST" "@build/test-sources.txt"
}

# --- packaging (M8) ------------------------------------------------------

require_packaging_tools() {
    command -v jlink >/dev/null 2>&1 || { echo "jlink not found; install a full JDK 17 or newer" >&2; exit 1; }
    command -v jpackage >/dev/null 2>&1 || { echo "jpackage not found; install a full JDK 17 or newer" >&2; exit 1; }
}

# jpackage needs a directory containing exactly what should ship, so the jar is copied into one
# instead of pointing --input at build/, which also holds classes and test output.
stage_input() {
    rm -rf "$PACKAGE_INPUT"
    mkdir -p "$PACKAGE_INPUT"
    cp "$JAR" "$PACKAGE_INPUT/"
}

link_runtime() {
    rm -rf "$RUNTIME"
    echo "Linking a runtime with modules: $RUNTIME_MODULES"
    jlink --add-modules "$RUNTIME_MODULES" \
        --strip-debug --no-header-files --no-man-pages \
        --output "$RUNTIME"
}

package_app() {
    require_packaging_tools
    rm -rf "$DIST"
    stage_input
    link_runtime
    echo "Packaging $APP_NAME $APP_VERSION as a rootless app-image"
    jpackage --type app-image \
        --name "$APP_NAME" \
        --app-version "$APP_VERSION" \
        --dest "$DIST" \
        --input "$PACKAGE_INPUT" \
        --main-jar "$(basename "$JAR")" \
        --main-class "$MAIN_CLASS" \
        --runtime-image "$RUNTIME" \
        --description "Rootless application manager for Linux" \
        --vendor "UserSpace" \
        --java-options "-Dfile.encoding=UTF-8" \
        --java-options "-Dstdout.encoding=UTF-8" \
        --java-options "-Dstderr.encoding=UTF-8"
    echo "Built $DIST/$APP_NAME"
    verify_package
}

# The packaged artifact is the only thing a user ever runs, so it is the thing that gets checked:
# a real launch with an empty environment (proving the bundled runtime is used, not the system
# JDK), the layout it creates, and the single-instance guard, which needs a second real process.
verify_package() {
    local launcher="$DIST/$APP_NAME/bin/$APP_NAME"
    local runtime="$DIST/$APP_NAME/lib/runtime/bin/java"
    local failures=0
    local st rc out
    local sandbox
    sandbox="$(mktemp -d "${TMPDIR:-/tmp}/userspace-pkg-XXXXXX")"

    check() {
        if [ "$2" -eq 0 ]; then
            echo "  PASS  $1"
        else
            echo "  FAIL  $1"
            failures=$((failures + 1))
        fi
    }

    echo "Verifying $DIST/$APP_NAME"

    [ -x "$launcher" ] && st=0 || st=1
    check "launcher exists and is executable" "$st"

    [ -x "$runtime" ] && st=0 || st=1
    check "a runtime is bundled inside the app-image" "$st"

    # The shipped jar must be the one just built, not a stale copy left in the image.
    cmp -s "$JAR" "$DIST/$APP_NAME/lib/app/$(basename "$JAR")" && st=0 || st=1
    check "the packaged jar is the jar just built" "$st"

    # A login shell is not involved and neither is the system JDK: only PATH, HOME and the XDG
    # variables survive, which is the environment a desktop launcher would give us.
    # `&& rc=0 || rc=$?` rather than a bare assignment: a launcher that dies must produce a FAIL
    # line, not abort the script before it can report.
    out="$(env -i PATH=/usr/bin:/bin HOME="$sandbox" \
        XDG_CONFIG_HOME="$sandbox/config" XDG_DATA_HOME="$sandbox/data" \
        XDG_STATE_HOME="$sandbox/state" XDG_CACHE_HOME="$sandbox/cache" \
        "$launcher" 2>&1)" && rc=0 || rc=$?
    check "the packaged launcher starts and exits 0 (exit $rc)" \
        "$([ "$rc" -eq 0 ] && echo 0 || echo 1)"

    case "$out" in
        *"layout, settings and registry loaded"*) st=0 ;;
        *) st=1; echo "$out" | sed 's/^/        /' ;;
    esac
    check "it reports the loaded layout" "$st"

    # Startup reads settings, it does not write them, so the proof that XDG_CONFIG_HOME was honoured
    # is the path it reports rather than a file on disk.
    case "$out" in
        *"$sandbox/config/userspace/settings.properties"*) st=0 ;;
        *) st=1; echo "$out" | sed 's/^/        /' ;;
    esac
    check "the configured XDG directories were used, not \$HOME defaults" "$st"

    # Directories it creates must be private, exactly like the un-packaged build.
    local mode
    mode="$(stat -c '%a' "$sandbox/data/userspace/apps" 2>/dev/null || echo missing)"
    [ "$mode" = "700" ] && st=0 || st=1
    check "created directories are 0700 (apps: $mode)" "$st"

    # Two processes, one lock. The holder runs on the *bundled* runtime, so this also proves
    # java.base is enough for the file-locking code path.
    local lockFile="$sandbox/state/userspace/lock"
    local holdLog="$sandbox/hold.log"
    "$runtime" -cp "$OUT:$OUT_TEST" org.uspace.test.Child hold "$lockFile" pkg 3000 "$holdLog" \
        >/dev/null 2>&1 &
    local holder=$!
    local waited=0
    while [ ! -s "$holdLog" ] && [ "$waited" -lt 100 ]; do
        sleep 0.1
        waited=$((waited + 1))
    done

    out="$(env -i PATH=/usr/bin:/bin HOME="$sandbox" \
        XDG_CONFIG_HOME="$sandbox/config" XDG_DATA_HOME="$sandbox/data" \
        XDG_STATE_HOME="$sandbox/state" XDG_CACHE_HOME="$sandbox/cache" \
        "$launcher" 2>&1)" && rc=0 || rc=$?
    check "a second instance exits 3 while another process holds the lock (exit $rc)" \
        "$([ "$rc" -eq 3 ] && echo 0 || echo 1)"

    case "$out" in
        *"already running"*) st=0 ;;
        *) st=1; echo "$out" | sed 's/^/        /' ;;
    esac
    check "the second instance names the holder instead of writing" "$st"

    wait "$holder" 2>/dev/null || true

    local size
    size="$(du -sh "$DIST/$APP_NAME" | cut -f1)"
    echo "  size  $size  ($DIST/$APP_NAME)"

    rm -rf "$sandbox"
    if [ "$failures" -ne 0 ]; then
        echo "FAILED — $failures packaged-artifact check(s)" >&2
        exit 1
    fi
    echo "OK — packaged artifact verified"
}

package_deb() {
    require_packaging_tools
    command -v dpkg-deb >/dev/null 2>&1 || { echo "dpkg-deb not found; cannot build a .deb" >&2; exit 1; }
    command -v fakeroot >/dev/null 2>&1 || { echo "fakeroot not found; cannot build a .deb" >&2; exit 1; }
    rm -rf "$DIST"
    stage_input
    link_runtime
    echo "Packaging $APP_NAME $APP_VERSION as a .deb (maintainer build, installs system-wide)"
    jpackage --type deb \
        --name "$APP_NAME" \
        --app-version "$APP_VERSION" \
        --dest "$DIST" \
        --input "$PACKAGE_INPUT" \
        --main-jar "$(basename "$JAR")" \
        --main-class "$MAIN_CLASS" \
        --runtime-image "$RUNTIME" \
        --description "Rootless application manager for Linux" \
        --vendor "UserSpace" \
        --java-options "-Dfile.encoding=UTF-8" \
        --java-options "-Dstdout.encoding=UTF-8" \
        --java-options "-Dstderr.encoding=UTF-8"
    local deb
    deb="$(find "$DIST" -maxdepth 1 -name '*.deb' -print -quit)"
    [ -n "$deb" ] || { echo "jpackage reported success but no .deb is in $DIST" >&2; exit 1; }
    echo "Built $deb"
    dpkg-deb --info "$deb" >/dev/null
    echo "  PASS  dpkg-deb reads the package"
}

case "${1:-build}" in
    build)
        clean
        compile
        ;;
    run)
        clean
        compile
        exec java -jar "$JAR"
        ;;
    test)
        clean
        compile
        compile_tests
        java -cp "$OUT:$OUT_TEST" "$TEST_CLASS"
        ;;
    package)
        clean
        compile
        compile_tests
        package_app
        ;;
    deb)
        clean
        compile
        package_deb
        ;;
    clean)
        clean
        echo "Cleaned"
        ;;
    *)
        echo "usage: $0 [build|run|test|package|deb|clean]" >&2
        exit 64
        ;;
esac
