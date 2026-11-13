#!/usr/bin/env bash
# Build, run and test UserSpace with nothing but a JDK.
#
#   ./build.sh          compile + package build/userspace.jar
#   ./build.sh run      compile + run
#   ./build.sh test     compile + run the persistence test suite
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
    clean)
        clean
        echo "Cleaned"
        ;;
    *)
        echo "usage: $0 [build|run|test|clean]" >&2
        exit 64
        ;;
esac
