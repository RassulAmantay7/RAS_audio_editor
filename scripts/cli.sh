#!/usr/bin/env bash
# CLI ядра :core (без Android SDK): WAV/FLAC → правки → WAV/FLAC + автопроверка FR-33.
# Пример: scripts/cli.sh in.wav --norm -14 --fade-in 3 --out x.flac
# Компилирует :core в build/cli при изменении исходников (тот же компилятор, что в scripts/check.sh).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
L=/opt/gradle-8.14.3/lib
KCP=$L/kotlin-compiler-embeddable-2.0.21.jar:$L/kotlin-stdlib-2.0.21.jar:$L/kotlin-script-runtime-2.0.21.jar:$L/kotlin-reflect-2.0.21.jar:$L/kotlin-daemon-embeddable-2.0.21.jar:$L/trove4j-1.0.20200330.jar:$L/annotations-24.0.1.jar:$L/kotlinx-coroutines-core-jvm-1.6.4.jar
STD=$L/kotlin-stdlib-2.0.21.jar
OUT=${CLI_OUT:-$ROOT/build/cli}
stamp="$OUT/.stamp"
if [[ ! -f "$stamp" ]] || [[ -n "$(find "$ROOT/core/src/main" -name '*.kt' -newer "$stamp" -print -quit)" ]]; then
  rm -rf "$OUT"; mkdir -p "$OUT"
  java -Xmx2g -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -jvm-target 17 -nowarn \
    -cp "$STD" -d "$OUT" $(find "$ROOT/core/src/main" -name '*.kt') 2>&1 | grep -v '^Picked up' >&2 || true
  [[ -f "$OUT/com/rassul/audioeditor/core/cli/MainKt.class" ]] || { echo "CLI: ошибка компиляции" >&2; exit 3; }
  touch "$stamp"
fi
exec java -Xmx2g -Dfile.encoding=UTF-8 -cp "$STD:$OUT" com.rassul.audioeditor.core.cli.MainKt "$@"
