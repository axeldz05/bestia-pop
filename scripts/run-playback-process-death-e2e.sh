#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET_PACKAGE="com.bestiapop.android"
TEST_PACKAGE="com.bestiapop.android.test"
RUNNER_COMPONENT="${TEST_PACKAGE}/androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS="com.bestiapop.android.persistence.PlaybackProcessDeathE2ETest"
TARGET_APK="app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
SERIAL=""
BACKUP_READY=0
BACKUP_STARTED=0

usage() {
    printf '%s\n' \
        "Usage: $0 [--serial DEVICE_SERIAL]" \
        "" \
        "Runs the real PlaybackRuntime process-death E2E in two instrumentation phases:" \
        "  1. Build/install target + test APK once; persist a paused synthetic-WAV queue." \
        "  2. Host executes 'am force-stop' (no data clear), then starts phase 2 directly." \
        "  3. Verify cold hydration/no autoplay, clean fixtures, and restore prior app state." \
        "" \
        "Safety:" \
        "  - HostOrchestratedProcessDeathTest is excluded from connectedDebugAndroidTest." \
        "  - The script never uses pm clear or uninstall." \
        "  - Room/playback state is snapshotted while force-stopped and restored." \
        "  - Playback starts isolated; ListenBrainz stays disabled for the test process." \
        "  - Do not invoke both test methods in one instrumentation process."
}

while (($# > 0)); do
    case "$1" in
        --serial)
            [[ $# -ge 2 ]] || {
                printf 'Missing value for --serial\n' >&2
                exit 2
            }
            SERIAL="$2"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            printf 'Unknown option: %s\n\n' "$1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

command -v adb >/dev/null 2>&1 || {
    printf 'adb is required on PATH\n' >&2
    exit 1
}
command -v gradle >/dev/null 2>&1 || {
    printf 'gradle is required on PATH\n' >&2
    exit 1
}

if [[ -z "$SERIAL" ]]; then
    mapfile -t CONNECTED_DEVICES < <(adb devices | awk '$2 == "device" { print $1 }')
    if ((${#CONNECTED_DEVICES[@]} != 1)); then
        printf 'Expected exactly one connected device; found %d. Use --serial.\n' \
            "${#CONNECTED_DEVICES[@]}" >&2
        exit 1
    fi
    SERIAL="${CONNECTED_DEVICES[0]}"
fi

ADB=(adb -s "$SERIAL")

backup_app_state() {
    "${ADB[@]}" shell am force-stop "$TARGET_PACKAGE"
    "${ADB[@]}" shell am force-stop "$TEST_PACKAGE"
    if "${ADB[@]}" shell run-as "$TARGET_PACKAGE" \
        test -e no_backup/.playback-process-death-e2e-host; then
        printf '%s\n' \
            "Refusing to overwrite a previous process-death backup." \
            "Restore/remove no_backup/.playback-process-death-e2e-host with run-as first." >&2
        return 1
    fi
    BACKUP_STARTED=1
    "${ADB[@]}" shell run-as "$TARGET_PACKAGE" sh -s <<'REMOTE_BACKUP'
        set -e
        backup_one() {
            source_path="$1"
            backup_path="$2"
            if [ -f "$source_path" ]; then
                cp "$source_path" "$backup_path"
                touch "${backup_path}.present"
            fi
        }
        mkdir -p no_backup/.playback-process-death-e2e-host
        backup_one files/datastore/playback_session.preferences_pb \
            no_backup/.playback-process-death-e2e-host/playback_session.preferences_pb
        backup_one files/datastore/playback_settings.preferences_pb \
            no_backup/.playback-process-death-e2e-host/playback_settings.preferences_pb
        backup_one files/datastore/listenbrainz_settings.preferences_pb \
            no_backup/.playback-process-death-e2e-host/listenbrainz_settings.preferences_pb
        backup_one databases/bestiapop_music_db \
            no_backup/.playback-process-death-e2e-host/bestiapop_music_db
        backup_one databases/bestiapop_music_db-wal \
            no_backup/.playback-process-death-e2e-host/bestiapop_music_db-wal
        backup_one databases/bestiapop_music_db-shm \
            no_backup/.playback-process-death-e2e-host/bestiapop_music_db-shm
REMOTE_BACKUP
    BACKUP_READY=1
    "${ADB[@]}" shell run-as "$TARGET_PACKAGE" sh -s <<'REMOTE_PREPARE'
        set -e
        rm -f \
            files/datastore/playback_session.preferences_pb \
            files/datastore/playback_settings.preferences_pb \
            files/datastore/listenbrainz_settings.preferences_pb
REMOTE_PREPARE
}

restore_app_state() {
    local exit_code=$?
    trap - EXIT
    if ((BACKUP_READY == 1)); then
        set +e
        "${ADB[@]}" shell am force-stop "$TARGET_PACKAGE"
        "${ADB[@]}" shell am force-stop "$TEST_PACKAGE"
        "${ADB[@]}" shell run-as "$TARGET_PACKAGE" sh -s <<'REMOTE_RESTORE'
            set -e
            backup_root=no_backup/.playback-process-death-e2e-host

            stage_one() {
                original_path="$1"
                backup_path="$2"
                staged_path="${original_path}.process-death-e2e-restore"
                rm -f "$staged_path"
                if [ -f "${backup_path}.present" ]; then
                    test -f "$backup_path"
                    cp "$backup_path" "$staged_path"
                fi
            }
            restore_one() {
                original_path="$1"
                backup_path="$2"
                staged_path="${original_path}.process-death-e2e-restore"
                if [ -f "${backup_path}.present" ]; then
                    mv -f "$staged_path" "$original_path"
                else
                    rm -f "$original_path"
                fi
            }
            test -d "$backup_root"
            mkdir -p files/datastore databases

            stage_one files/datastore/playback_session.preferences_pb \
                "$backup_root/playback_session.preferences_pb"
            stage_one files/datastore/playback_settings.preferences_pb \
                "$backup_root/playback_settings.preferences_pb"
            stage_one files/datastore/listenbrainz_settings.preferences_pb \
                "$backup_root/listenbrainz_settings.preferences_pb"
            stage_one databases/bestiapop_music_db \
                "$backup_root/bestiapop_music_db"
            stage_one databases/bestiapop_music_db-wal \
                "$backup_root/bestiapop_music_db-wal"
            stage_one databases/bestiapop_music_db-shm \
                "$backup_root/bestiapop_music_db-shm"

            restore_one files/datastore/playback_session.preferences_pb \
                "$backup_root/playback_session.preferences_pb"
            restore_one files/datastore/playback_settings.preferences_pb \
                "$backup_root/playback_settings.preferences_pb"
            restore_one files/datastore/listenbrainz_settings.preferences_pb \
                "$backup_root/listenbrainz_settings.preferences_pb"
            restore_one databases/bestiapop_music_db \
                "$backup_root/bestiapop_music_db"
            restore_one databases/bestiapop_music_db-wal \
                "$backup_root/bestiapop_music_db-wal"
            restore_one databases/bestiapop_music_db-shm \
                "$backup_root/bestiapop_music_db-shm"
            rm -rf files/playback-process-death-e2e
            rm -rf "$backup_root"
REMOTE_RESTORE
        local restore_code=$?
        set -e
        if ((restore_code != 0)); then
            printf 'WARNING: could not fully restore the pre-test app state.\n' >&2
            ((exit_code == 0)) && exit_code=$restore_code
        fi
    elif ((BACKUP_STARTED == 1)); then
        set +e
        "${ADB[@]}" shell run-as "$TARGET_PACKAGE" \
            rm -rf no_backup/.playback-process-death-e2e-host
        set -e
    fi
    exit "$exit_code"
}

run_phase() {
    local method="$1"
    local label="$2"
    local output
    local command_code

    printf '\n%s\n' "$label"
    set +e
    output="$(
        "${ADB[@]}" shell am instrument -w \
            -e class "${TEST_CLASS}#${method}" \
            "$RUNNER_COMPONENT" 2>&1
    )"
    command_code=$?
    set -e
    printf '%s\n' "$output"

    if ((command_code != 0)) ||
        [[ "$output" == *"FAILURES!!!"* ]] ||
        [[ "$output" != *"OK (1 test)"* ]]; then
        printf '%s failed (adb exit %d).\n' "$label" "$command_code" >&2
        return 1
    fi
}

cd "$ROOT_DIR"
printf 'Building target and instrumentation APKs once...\n'
gradle :app:assembleDebug :app:assembleDebugAndroidTest

[[ -f "$TARGET_APK" ]] || {
    printf 'Missing target APK: %s\n' "$TARGET_APK" >&2
    exit 1
}
[[ -f "$TEST_APK" ]] || {
    printf 'Missing test APK: %s\n' "$TEST_APK" >&2
    exit 1
}

printf 'Installing APKs on %s without clearing app data...\n' "$SERIAL"
"${ADB[@]}" install -r -d "$TARGET_APK"
"${ADB[@]}" install -r -d -t "$TEST_APK"

trap restore_app_state EXIT
backup_app_state

run_phase "phase1_persistPausedQueueForHostKill" "Phase 1: persist real paused playback"

printf '\nKilling %s from the host (data preserved)...\n' "$TARGET_PACKAGE"
"${ADB[@]}" shell am force-stop "$TARGET_PACKAGE"
TARGET_PID="$("${ADB[@]}" shell pidof "$TARGET_PACKAGE" 2>/dev/null || true)"
if [[ -n "${TARGET_PID//[[:space:]]/}" ]]; then
    printf 'Target process survived force-stop: %s\n' "$TARGET_PID" >&2
    exit 1
fi

run_phase \
    "phase2_restoreAfterHostKillWithoutAutoplayAndCleanUp" \
    "Phase 2: verify cold hydration and cleanup"

printf '\nPlayback process-death E2E passed. Restoring prior app state...\n'
