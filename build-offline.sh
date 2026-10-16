#!/usr/bin/env bash
# Build with NO gradle and NO network, using a JDK and a directory of dependency jars.
#
# Most people should use gradle (`gradle build`). This exists for building on a machine with
# no gradle installed and no internet -- which is the situation this plugin was extracted in.
#
#   JAVA_HOME=/path/to/jdk21  SKIRMISH_LIBS=/path/to/jars  ./build-offline.sh
#
# SKIRMISH_LIBS needs paper-api plus its transitive deps (adventure, guava, gson, bungee-chat)
# and, for the tests, the JUnit 5 platform. Easiest way to get them is one gradle build on any
# machine, then copy ~/.gradle/caches/modules-2 jars into one directory.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JDK="${JAVA_HOME:-}"
LIBS="${SKIRMISH_LIBS:-}"
[[ -n "$JDK"  ]] || { echo "set JAVA_HOME to a JDK 21+" >&2; exit 2; }
[[ -n "$LIBS" ]] || { echo "set SKIRMISH_LIBS to a directory of dependency jars" >&2; exit 2; }
[[ -x "$JDK/bin/javac" ]] || { echo "no javac at $JDK/bin/javac" >&2; exit 2; }

CP=$(ls "$LIBS"/*.jar | tr '\n' ':')
# A near-empty classpath makes javac fail in ways that read like source errors. Refuse loudly.
n=$(ls "$LIBS"/*.jar 2>/dev/null | wc -l | tr -d ' ')
[[ "$n" -ge 8 ]] || { echo "REFUSING: only $n jars in $LIBS" >&2; exit 2; }

OUT="$HERE/build"; rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/test-classes"
echo "==> compiling ($n dependency jars)"
find "$HERE/src/main/java" -name '*.java' > "$OUT/s.txt"
"$JDK/bin/javac" -nowarn -cp "$CP" -d "$OUT/classes" @"$OUT/s.txt"
echo "==> tests"
find "$HERE/src/test/java" -name '*.java' > "$OUT/t.txt"
"$JDK/bin/javac" -nowarn -cp "$CP:$OUT/classes" -d "$OUT/test-classes" @"$OUT/t.txt"
"$JDK/bin/javac" -nowarn -cp "$CP" -d "$OUT/test-classes" "$HERE/tools/TestRunner.java"
"$JDK/bin/java" -cp "$CP:$OUT/classes:$OUT/test-classes" TestRunner app.vexoris.skirmish
echo "==> packaging"
cp -r "$HERE/src/main/resources/." "$OUT/classes/"
"$JDK/bin/jar" --create --file "$OUT/Skirmish-1.0.0.jar" -C "$OUT/classes" .
echo "  $OUT/Skirmish-1.0.0.jar"
