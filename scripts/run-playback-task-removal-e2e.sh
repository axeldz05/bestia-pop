#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET_PACKAGE="com.bestiapop.android"
TEST_PACKAGE="com.bestiapop.android.test"
RUNNER_COMPONENT="${TEST_PACKAGE}/androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS="com.bestiapop.android.persistence.PlaybackTaskRemovalE2ETest"
TARGET_APK="app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
SERIAL=""
BACKUP_STARTED=0
BACKUP_READY=0
POLICY_SNAPSHOTTED=0
PREVIOUS_RESTRICTION=""
PREVIOUS_APP_OP=""
PREVIOUS_NOTIFICATION_GRANT=""

usage() {
    printf '%s\n' \
        "Usage: $0 [--serial DEVICE_SERIAL]" \
        "" \
        "Runs host-real task removal; it never calls Activity.finish/scenario.close for removal:" \
        "  1. Instrumentation starts a real WAV, MainActivity task, and mediaPlayback FGS." \
        "  2. Host locates the exact RootTask and removes it with 'cmd activity stack remove'." \
        "  3. Host verifies the PID + FGS survive, relaunches UI in that PID, and reads the" \
        "     resident queue/progress probe." \
        "  4. A paused variant verifies task removal leaves no started/foreground service." \
        "" \
        "Safety: app Room/DataStore, notification grant, and sideload policy are snapshotted and" \
        "restored; no pm clear/uninstall is used. The test annotation excludes these phases from" \
        "normal connectedDebugAndroidTest discovery."
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

for command in adb gradle python3; do
    command -v "$command" >/dev/null 2>&1 || {
        printf '%s is required on PATH\n' "$command" >&2
        exit 1
    }
done

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

await_host() {
    local description="$1"
    local timeout_seconds="$2"
    shift 2
    local deadline=$((SECONDS + timeout_seconds))
    while ((SECONDS <= deadline)); do
        if "$@"; then
            return 0
        fi
        sleep 0.1
    done
    printf 'Timed out waiting for %s\n' "$description" >&2
    return 1
}

snapshot_policy() {
    local user_id
    local app_op_output
    user_id="$("${ADB[@]}" shell am get-current-user | tr -d '\r')"
    PREVIOUS_RESTRICTION="$(
        "${ADB[@]}" shell cmd activity get-bg-restriction-level \
            --user "$user_id" "$TARGET_PACKAGE" | tr -d '\r'
    )"
    app_op_output="$(
        "${ADB[@]}" shell cmd appops get \
            --user "$user_id" "$TARGET_PACKAGE" RUN_ANY_IN_BACKGROUND | tr -d '\r'
    )"
    if [[ "$app_op_output" == *"No operations."* ]]; then
        PREVIOUS_APP_OP="default"
    else
        PREVIOUS_APP_OP="$(
            awk -F': ' '
                /RUN_ANY_IN_BACKGROUND:/ {
                    split($2, value, /[[:space:]]+/)
                    print value[1]
                    exit
                }
            ' \
                <<<"$app_op_output"
        )"
    fi
    [[ -n "$PREVIOUS_RESTRICTION" && -n "$PREVIOUS_APP_OP" ]] || {
        printf 'Could not snapshot sideload policy.\nRestriction=%s\nAppOp=%s\n' \
            "$PREVIOUS_RESTRICTION" "$app_op_output" >&2
        return 1
    }
    if "${ADB[@]}" shell dumpsys package "$TARGET_PACKAGE" |
        awk '/android.permission.POST_NOTIFICATIONS: granted=true/ { found=1 } END { exit !found }'; then
        PREVIOUS_NOTIFICATION_GRANT="granted"
    else
        PREVIOUS_NOTIFICATION_GRANT="denied"
    fi
    POLICY_SNAPSHOTTED=1
}

restore_policy() {
    ((POLICY_SNAPSHOTTED == 1)) || return 0
    local user_id
    user_id="$("${ADB[@]}" shell am get-current-user | tr -d '\r')"
    "${ADB[@]}" shell cmd activity set-bg-restriction-level \
        --user "$user_id" "$TARGET_PACKAGE" "$PREVIOUS_RESTRICTION"
    "${ADB[@]}" shell cmd appops set \
        --user "$user_id" "$TARGET_PACKAGE" RUN_ANY_IN_BACKGROUND "$PREVIOUS_APP_OP"
    "${ADB[@]}" shell cmd appops write-settings
    if [[ "$PREVIOUS_NOTIFICATION_GRANT" == "granted" ]]; then
        "${ADB[@]}" shell pm grant "$TARGET_PACKAGE" android.permission.POST_NOTIFICATIONS
    else
        "${ADB[@]}" shell pm revoke "$TARGET_PACKAGE" android.permission.POST_NOTIFICATIONS
    fi
}

backup_app_state() {
    "${ADB[@]}" shell am force-stop "$TARGET_PACKAGE"
    "${ADB[@]}" shell am force-stop "$TEST_PACKAGE"
    if "${ADB[@]}" shell run-as "$TARGET_PACKAGE" \
        test -e no_backup/.playback-task-removal-e2e-host; then
        printf '%s\n' \
            "Refusing to overwrite a previous task-removal backup." \
            "Restore/remove no_backup/.playback-task-removal-e2e-host first." >&2
        return 1
    fi
    BACKUP_STARTED=1
    "${ADB[@]}" shell run-as "$TARGET_PACKAGE" sh -s <<'REMOTE_BACKUP'
        set -e
        backup_root=no_backup/.playback-task-removal-e2e-host
        backup_one() {
            source_path="$1"
            backup_path="$2"
            if [ -f "$source_path" ]; then
                cp "$source_path" "$backup_path"
                touch "${backup_path}.present"
            fi
        }
        mkdir -p "$backup_root"
        backup_one files/datastore/playback_session.preferences_pb \
            "$backup_root/playback_session.preferences_pb"
        backup_one files/datastore/playback_settings.preferences_pb \
            "$backup_root/playback_settings.preferences_pb"
        backup_one files/datastore/library_settings.preferences_pb \
            "$backup_root/library_settings.preferences_pb"
        backup_one files/datastore/listenbrainz_settings.preferences_pb \
            "$backup_root/listenbrainz_settings.preferences_pb"
        backup_one databases/bestiapop_music_db "$backup_root/bestiapop_music_db"
        backup_one databases/bestiapop_music_db-wal "$backup_root/bestiapop_music_db-wal"
        backup_one databases/bestiapop_music_db-shm "$backup_root/bestiapop_music_db-shm"
REMOTE_BACKUP
    BACKUP_READY=1
    "${ADB[@]}" shell run-as "$TARGET_PACKAGE" sh -s <<'REMOTE_PREPARE'
        set -e
        rm -f \
            files/datastore/playback_session.preferences_pb \
            files/datastore/playback_settings.preferences_pb \
            files/datastore/library_settings.preferences_pb \
            files/datastore/listenbrainz_settings.preferences_pb \
            databases/bestiapop_music_db \
            databases/bestiapop_music_db-wal \
            databases/bestiapop_music_db-shm
        rm -rf files/playback-task-removal-e2e
REMOTE_PREPARE
}

restore_app_state() {
    local exit_code=$?
    trap - EXIT
    set +e
    "${ADB[@]}" shell am force-stop "$TARGET_PACKAGE"
    "${ADB[@]}" shell am force-stop "$TEST_PACKAGE"
    if ((BACKUP_READY == 1)); then
        "${ADB[@]}" shell run-as "$TARGET_PACKAGE" sh -s <<'REMOTE_RESTORE'
            set -e
            backup_root=no_backup/.playback-task-removal-e2e-host
            stage_one() {
                original_path="$1"
                backup_path="$2"
                staged_path="${original_path}.task-removal-e2e-restore"
                rm -f "$staged_path"
                if [ -f "${backup_path}.present" ]; then
                    test -f "$backup_path"
                    cp "$backup_path" "$staged_path"
                fi
            }
            restore_one() {
                original_path="$1"
                backup_path="$2"
                staged_path="${original_path}.task-removal-e2e-restore"
                if [ -f "${backup_path}.present" ]; then
                    mv -f "$staged_path" "$original_path"
                else
                    rm -f "$original_path"
                fi
            }
            test -d "$backup_root"
            mkdir -p files/datastore databases
            for name in \
                playback_session.preferences_pb \
                playback_settings.preferences_pb \
                library_settings.preferences_pb \
                listenbrainz_settings.preferences_pb; do
                stage_one "files/datastore/$name" "$backup_root/$name"
            done
            for name in bestiapop_music_db bestiapop_music_db-wal bestiapop_music_db-shm; do
                stage_one "databases/$name" "$backup_root/$name"
            done
            for name in \
                playback_session.preferences_pb \
                playback_settings.preferences_pb \
                library_settings.preferences_pb \
                listenbrainz_settings.preferences_pb; do
                restore_one "files/datastore/$name" "$backup_root/$name"
            done
            for name in bestiapop_music_db bestiapop_music_db-wal bestiapop_music_db-shm; do
                restore_one "databases/$name" "$backup_root/$name"
            done
            rm -rf files/playback-task-removal-e2e "$backup_root"
REMOTE_RESTORE
        restore_code=$?
        if ((restore_code != 0)); then
            printf 'WARNING: could not fully restore pre-test app data.\n' >&2
            ((exit_code == 0)) && exit_code=$restore_code
        fi
    elif ((BACKUP_STARTED == 1)); then
        "${ADB[@]}" shell run-as "$TARGET_PACKAGE" \
            rm -rf no_backup/.playback-task-removal-e2e-host
    fi
    restore_policy
    policy_code=$?
    if ((policy_code != 0)); then
        printf 'WARNING: could not fully restore pre-test app policy.\n' >&2
        ((exit_code == 0)) && exit_code=$policy_code
    fi
    set -e
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

find_task_id() {
    "${ADB[@]}" shell cmd activity stack list |
        tr -d '\r' |
        awk -v package_name="$TARGET_PACKAGE" '
            /^RootTask id=/ {
                split($2, root, "=")
                root_id = root[2]
            }
            index($0, ": " package_name "/") > 0 {
                print root_id
            }
        '
}

task_absent() {
    local task_id="$1"
    ! "${ADB[@]}" shell cmd activity stack list |
        tr -d '\r' |
        awk -v expected="$task_id" '
            /^RootTask id=/ {
                split($2, root, "=")
                if (root[2] == expected) found=1
            }
            END { exit !found }
        '
}

service_foreground() {
    "${ADB[@]}" shell dumpsys activity services "$TARGET_PACKAGE" |
        tr -d '\r' |
        awk '
            /ServiceRecord.*MusicService/ { in_service=1 }
            in_service && /isForeground=true/ { found=1 }
            END { exit !found }
        '
}

paused_service_stop_policy_observed() {
    local dump
    dump="$("${ADB[@]}" shell dumpsys activity services "$TARGET_PACKAGE" | tr -d '\r')"
    if [[ "$dump" != *"MusicService"* ]]; then
        return 0
    fi
    [[ "$dump" == *"isForeground=false"* && "$dump" == *"startRequested=false"* ]]
}

remove_exact_task() {
    local task_id="$1"
    local matching_tasks
    matching_tasks="$(find_task_id)"
    [[ "$matching_tasks" == "$task_id" ]] || {
        printf 'Refusing ambiguous task removal. Expected %s, found: %s\n' \
            "$task_id" "$matching_tasks" >&2
        return 1
    }
    "${ADB[@]}" shell cmd activity stack remove "$task_id"
    await_host "RootTask $task_id removal" 10 task_absent "$task_id"
}

read_probe_result() {
    "${ADB[@]}" exec-out run-as "$TARGET_PACKAGE" \
        cp "files/playback-task-removal-e2e/host-phase2-result.json" /dev/stdout
}

probe_ready() {
    "${ADB[@]}" shell run-as "$TARGET_PACKAGE" \
        test -f files/playback-task-removal-e2e/host-phase2-result.json
}

cd "$ROOT_DIR"
printf 'Building target and instrumentation APKs once...\n'
"$ROOT_DIR/scripts/gradle-low-memory.sh" :app:assembleDebug :app:assembleDebugAndroidTest
[[ -f "$TARGET_APK" && -f "$TEST_APK" ]] || {
    printf 'Missing debug target/test APK output.\n' >&2
    exit 1
}
printf 'Installing APKs on %s without clearing app data...\n' "$SERIAL"
"${ADB[@]}" install -r -d "$TARGET_APK"
"${ADB[@]}" install -r -d -t "$TEST_APK"

trap restore_app_state EXIT
snapshot_policy
backup_app_state

run_phase \
    "phase1_startPlayingWavAndArmHostProbe" \
    "Playing phase 1: start real WAV/FGS and end instrumentation"
PLAYING_PID="$("${ADB[@]}" shell pidof "$TARGET_PACKAGE" | tr -d '\r[:space:]')"
[[ -n "$PLAYING_PID" ]] || {
    printf 'Target process died when phase-1 instrumentation ended.\n' >&2
    exit 1
}
mapfile -t PLAYING_TASKS < <(find_task_id)
if ((${#PLAYING_TASKS[@]} != 1)); then
    printf 'Expected one BestiaPop task; found %d: %s\n' \
        "${#PLAYING_TASKS[@]}" "${PLAYING_TASKS[*]-}" >&2
    exit 1
fi
await_host "MusicService foreground before task removal" 10 service_foreground
remove_exact_task "${PLAYING_TASKS[0]}"

AFTER_REMOVAL_PID="$("${ADB[@]}" shell pidof "$TARGET_PACKAGE" | tr -d '\r[:space:]')"
[[ "$AFTER_REMOVAL_PID" == "$PLAYING_PID" ]] || {
    printf 'Task removal changed PID: before=%s after=%s\n' \
        "$PLAYING_PID" "$AFTER_REMOVAL_PID" >&2
    exit 1
}
await_host "MusicService FGS after task removal" 10 service_foreground

"${ADB[@]}" shell cmd activity start-activity -W \
    -n "${TARGET_PACKAGE}/.MainActivity" >/dev/null
RECONNECTED_PID="$("${ADB[@]}" shell pidof "$TARGET_PACKAGE" | tr -d '\r[:space:]')"
[[ "$RECONNECTED_PID" == "$PLAYING_PID" ]] || {
    printf 'UI reconnect restarted process: before=%s after=%s\n' \
        "$PLAYING_PID" "$RECONNECTED_PID" >&2
    exit 1
}
"${ADB[@]}" shell run-as "$TARGET_PACKAGE" \
    touch files/playback-task-removal-e2e/host-phase2.signal
await_host "resident queue/progress probe" 15 probe_ready
PROBE_RESULT="$(read_probe_result)"
printf 'Resident probe: %s\n' "$PROBE_RESULT"
python3 -c '
import json, sys
expected_pid = int(sys.argv[1])
payload = json.load(sys.stdin)
assert payload.get("passed") is True, payload
assert payload.get("pid") == expected_pid, payload
assert payload.get("currentIndex") == 1, payload
assert payload.get("positionAfterMs", 0) > payload.get("positionBeforeMs", 0), payload
' "$PLAYING_PID" <<<"$PROBE_RESULT"
await_host "MusicService FGS after UI reconnect" 10 service_foreground
run_phase "cleanupHostFixture" "Playing cleanup"

run_phase \
    "phase1_startPausedWavForStopPolicy" \
    "Paused phase 1: start then pause real WAV"
mapfile -t PAUSED_TASKS < <(find_task_id)
if ((${#PAUSED_TASKS[@]} != 1)); then
    printf 'Expected one paused BestiaPop task; found %d: %s\n' \
        "${#PAUSED_TASKS[@]}" "${PAUSED_TASKS[*]-}" >&2
    exit 1
fi
remove_exact_task "${PAUSED_TASKS[0]}"
await_host \
    "paused onTaskRemoved stopSelf/non-FGS policy" \
    10 \
    paused_service_stop_policy_observed
run_phase "cleanupHostFixture" "Paused cleanup"

printf '\nPlayback task-removal E2E passed. Restoring prior app state and policy...\n'
