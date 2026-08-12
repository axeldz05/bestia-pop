#!/usr/bin/env bash
set -Eeuo pipefail

# Host E2E scripts build serially and never retain a daemon. Callers can lower/raise the cap without
# editing every script, while the default stays safe on development machines with limited RAM.
GRADLE_MAX_WORKERS="${GRADLE_MAX_WORKERS:-1}"
GRADLE_JVM_ARGS="${GRADLE_JVM_ARGS:--Xmx1536m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8}"

exec gradle \
    --no-daemon \
    --max-workers="$GRADLE_MAX_WORKERS" \
    -Dorg.gradle.jvmargs="$GRADLE_JVM_ARGS" \
    -Pkotlin.compiler.execution.strategy=in-process \
    "$@"
