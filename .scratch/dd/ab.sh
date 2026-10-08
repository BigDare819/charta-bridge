#!/usr/bin/env bash
# A/B driver for the double-dummy solver: compiles the solver + every probe + one target and runs it.
# Args starting with -D/-X go to the JVM, the rest go to the target.
#   ./ab.sh Hard 1
#   ./ab.sh One 0 4 3 300000000
#   ./ab.sh Hard 1 -Ddd.canon=false
set -u
cd "$(dirname "$0")"
what="$1"; shift
out=ab
mkdir -p "$out"
files=(../../src/main/java/dev/bridge/game/BridgeDoubleDummy.java)
for f in src/*.java; do files+=("$f"); done
[ -f "exp/$what.java" ] && files+=("exp/$what.java")
javac -encoding UTF-8 -d "$out" "${files[@]}" 2>&1 | grep -v "^注" || true
jvm=(); prog=()
for a in "$@"; do
  case "$a" in -D*|-X*) jvm+=("$a");; *) prog+=("$a");; esac
done
java -Xmx3g "${jvm[@]+"${jvm[@]}"}" -cp "$out" "$what" "${prog[@]+"${prog[@]}"}"
