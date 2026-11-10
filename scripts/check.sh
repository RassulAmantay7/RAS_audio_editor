#!/usr/bin/env bash
# Локальная проверка БЕЗ Android SDK: компиляция :core, запуск его тестов (JUnit 4)
# и компиляция Kotlin-кода :app против android.jar (API 35).
# Ресурсы (XML) здесь не проверяются — только в CI.
# Использование: scripts/check.sh [core|app|all]   (по умолчанию all)
set -euo pipefail
cd "$(dirname "$0")/.."
L=/opt/gradle-8.14.3/lib
ANDROID_JAR=${ANDROID_JAR:-/home/claude/sdk/android-35.jar}
KCP=$L/kotlin-compiler-embeddable-2.0.21.jar:$L/kotlin-stdlib-2.0.21.jar:$L/kotlin-script-runtime-2.0.21.jar:$L/kotlin-reflect-2.0.21.jar:$L/kotlin-daemon-embeddable-2.0.21.jar:$L/trove4j-1.0.20200330.jar:$L/annotations-24.0.1.jar:$L/kotlinx-coroutines-core-jvm-1.6.4.jar
STD=$L/kotlin-stdlib-2.0.21.jar
JUNIT=$L/junit-4.13.2.jar:$(ls $L/hamcrest-core-*.jar 2>/dev/null | head -1)
OUT=${CHECK_OUT:-build/check}
what=${1:-all}
kotlinc() { java -Xmx2g -cp "$KCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -jvm-target 17 -nowarn "$@" 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS' || true; }

rm -rf "$OUT"; mkdir -p "$OUT"
fail=0
if [[ $what == core || $what == all ]]; then
  echo "== core: main"
  kotlinc -cp "$STD" -d "$OUT/core" $(find core/src/main -name '*.kt') | tee "$OUT/core.log"
  grep -q 'error:' "$OUT/core.log" && fail=1
  if [[ $fail == 0 && -d core/src/test ]] && [[ -n "$(find core/src/test -name '*.kt')" ]]; then
    echo "== core: tests"
    kotlinc -cp "$STD:$OUT/core:$JUNIT" -d "$OUT/core-test" $(find core/src/test -name '*.kt') | tee "$OUT/core-test.log"
    if grep -q 'error:' "$OUT/core-test.log"; then fail=1; else
      classes=$(cd "$OUT/core-test" && find . -name '*Test.class' ! -name '*$*' | sed 's#^\./##; s#\.class$##; s#/#.#g')
      if [[ -n "$classes" ]]; then
        java -cp "$STD:$OUT/core:$OUT/core-test:$JUNIT:core/src/test/resources" org.junit.runner.JUnitCore $classes 2>&1 | grep -v '^Picked up' | tail -40 | tee "$OUT/junit.log"
        grep -q '^OK' "$OUT/junit.log" || fail=1
      fi
    fi
  fi
fi
if [[ $what == app || $what == all ]]; then
  echo "== app: kotlin vs android-35.jar"
  [[ -d "$OUT/core" ]] || kotlinc -cp "$STD" -d "$OUT/core" $(find core/src/main -name '*.kt') >/dev/null
  kotlinc -cp "$STD:$ANDROID_JAR:$OUT/core" -d "$OUT/app" $(find app/src/main/java -name '*.kt') | tee "$OUT/app.log"
  grep -q 'error:' "$OUT/app.log" && fail=1
fi
if [[ $fail == 0 ]]; then echo "CHECK: OK"; else echo "CHECK: FAILED"; exit 1; fi
