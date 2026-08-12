#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET_PACKAGE="com.bestiapop.android"
TEST_PACKAGE="com.bestiapop.android.test"
RUNNER_COMPONENT="${TEST_PACKAGE}/androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS="com.bestiapop.android.persistence.BackupRestoreE2ETest"
TARGET_APK="app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
LOCAL_TRANSPORT="com.android.localtransport/.LocalTransport"
SERIAL=""
EXECUTE=0
SNAPSHOT_READY=0
TRANSPORT_CHANGED=0
LOCAL_DATA_WRITTEN=0
PREVIOUS_TRANSPORT=""
TEMP_DIR=""
APP_DATA_ARCHIVE=""
declare -A PREVIOUS_PERMISSION_GRANTS=()
RUNTIME_PERMISSIONS=(
    "android.permission.POST_NOTIFICATIONS"
    "android.permission.READ_MEDIA_AUDIO"
    "android.permission.READ_EXTERNAL_STORAGE"
)

usage() {
    printf '%s\n' \
        "Usage: $0 [--serial DEVICE_SERIAL] [--execute-destructive-emulator-fixture]" \
        "" \
        "Default is a dry-run capability/safety check. Execution is intentionally restricted to a" \
        "disposable AOSP emulator with LocalTransport. The confirmed path performs:" \
        "  seed -> bmgr backupnow -> pm clear -> bmgr restore -> contract verification." \
        "" \
        "The fixture verifies included theme/playback settings and excluded Room/library-scan/" \
        "playback-session/download/review/ListenBrainz-token data." \
        "" \
        "Safety contract:" \
        "  - Never run this against a phone or a real-user emulator." \
        "  - Before seeding, the script force-stops the app and saves an exact tar of internal app" \
        "    data on the host, plus declared runtime-permission grants." \
        "  - EXIT cleanup clears fixture data, restores that tar, restores grants and the previous" \
        "    backup transport, and wipes only this package from LocalTransport." \
        "  - pm clear is unavoidable for a framework restore E2E, so execution requires the explicit" \
        "    destructive fixture flag; without it this script never builds, installs, clears, or backs up."
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
        --execute-destructive-emulator-fixture)
            EXECUTE=1
            shift
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

for command in adb awk tar; do
    command -v "$command" >/dev/null 2>&1 || {
        printf '%s is required on PATH\n' "$command" >&2
        exit 1
    }
done
if ((EXECUTE == 1)); then
    command -v gradle >/dev/null 2>&1 || {
        printf 'gradle is required on PATH\n' >&2
        exit 1
    }
fi

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

SDK="$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r[:space:]')"
QEMU="$("${ADB[@]}" shell getprop ro.boot.qemu | tr -d '\r[:space:]')"
PRODUCT="$("${ADB[@]}" shell getprop ro.product.model | tr -d '\r')"
TRANSPORT_COMPONENTS="$("${ADB[@]}" shell bmgr list transports -c | tr -d '\r')"
BACKUP_ENABLED="$("${ADB[@]}" shell bmgr enabled | tr -d '\r')"
BACKUP_ACTIVATED="$("${ADB[@]}" shell bmgr activated | tr -d '\r')"

if [[ "$QEMU" != "1" ]]; then
    printf '%s\n' \
        "ABORT: $SERIAL ($PRODUCT) is not an emulator (ro.boot.qemu=$QEMU)." \
        "A real framework restore requires pm clear; this script refuses physical/real-user data." >&2
    exit 1
fi
if ((SDK < 31)); then
    printf 'ABORT: API %s does not exercise data_extraction_rules cloud-backup semantics.\n' \
        "$SDK" >&2
    exit 1
fi
if [[ "$TRANSPORT_COMPONENTS" != *"com.android.localtransport/"* ]]; then
    printf '%s\n' \
        "ABORT: AOSP LocalTransport is unavailable." \
        "Available transport components:" \
        "$TRANSPORT_COMPONENTS" >&2
    exit 1
fi
if [[ "$BACKUP_ENABLED" != *"enabled"* || "$BACKUP_ENABLED" == *"not enabled"* ]]; then
    printf 'ABORT: BackupManager is disabled: %s\n' "$BACKUP_ENABLED" >&2
    exit 1
fi
if [[ "$BACKUP_ACTIVATED" != *"activated"* || "$BACKUP_ACTIVATED" == *"not activated"* ]]; then
    printf 'ABORT: BackupManager is not activated: %s\n' "$BACKUP_ACTIVATED" >&2
    exit 1
fi

printf 'Backup/restore fixture capability check passed: %s, API %s, LocalTransport present.\n' \
    "$PRODUCT" "$SDK"
if ((EXECUTE == 0)); then
    printf '%s\n' \
        "DRY RUN ONLY: no device/app state changed." \
        "Re-run with --execute-destructive-emulator-fixture on a disposable emulator."
    exit 0
fi

permission_granted() {
    local permission="$1"
    "${ADB[@]}" shell dumpsys package "$TARGET_PACKAGE" |
        tr -d '\r' |
        awk -v permission="$permission" '
            index($0, permission ": granted=true") > 0 { found=1 }
            END { exit !found }
        '
}

snapshot_app_data() {
    "${ADB[@]}" shell am force-stop "$TARGET_PACKAGE"
    "${ADB[@]}" shell am force-stop "$TEST_PACKAGE"
    for permission in "${RUNTIME_PERMISSIONS[@]}"; do
        if permission_granted "$permission"; then
            PREVIOUS_PERMISSION_GRANTS["$permission"]=1
        else
            PREVIOUS_PERMISSION_GRANTS["$permission"]=0
        fi
    done
    "${ADB[@]}" exec-out run-as "$TARGET_PACKAGE" tar -C . -cf - . >"$APP_DATA_ARCHIVE"
    [[ -s "$APP_DATA_ARCHIVE" ]] || {
        printf 'Could not snapshot internal app data with run-as/tar.\n' >&2
        return 1
    }
    tar -tf "$APP_DATA_ARCHIVE" >/dev/null
    SNAPSHOT_READY=1
}

restore_runtime_permissions() {
    local permission
    for permission in "${RUNTIME_PERMISSIONS[@]}"; do
        if [[ "${PREVIOUS_PERMISSION_GRANTS[$permission]:-0}" == "1" ]]; then
            "${ADB[@]}" shell pm grant "$TARGET_PACKAGE" "$permission" >/dev/null 2>&1 || true
        else
            "${ADB[@]}" shell pm revoke "$TARGET_PACKAGE" "$permission" >/dev/null 2>&1 || true
        fi
    done
}

restore_everything() {
    local exit_code=$?
    trap - EXIT
    set +e
    "${ADB[@]}" shell am force-stop "$TARGET_PACKAGE"
    "${ADB[@]}" shell am force-stop "$TEST_PACKAGE"

    if ((LOCAL_DATA_WRITTEN == 1)); then
        "${ADB[@]}" shell bmgr wipe "$LOCAL_TRANSPORT" "$TARGET_PACKAGE"
    fi
    if ((TRANSPORT_CHANGED == 1)) && [[ -n "$PREVIOUS_TRANSPORT" ]]; then
        "${ADB[@]}" shell bmgr transport "$PREVIOUS_TRANSPORT"
    fi

    if ((SNAPSHOT_READY == 1)); then
        "${ADB[@]}" shell pm clear "$TARGET_PACKAGE" >/dev/null
        "${ADB[@]}" shell -T run-as "$TARGET_PACKAGE" tar -C . -xf - <"$APP_DATA_ARCHIVE"
        data_restore_code=$?
        restore_runtime_permissions
        if ((data_restore_code != 0)); then
            printf 'WARNING: exact host app-data restore failed; archive retained at %s\n' \
                "$APP_DATA_ARCHIVE" >&2
            ((exit_code == 0)) && exit_code=$data_restore_code
        fi
    fi
    if [[ -n "$TEMP_DIR" && -d "$TEMP_DIR" && $exit_code -eq 0 ]]; then
        rm -rf "$TEMP_DIR"
    elif [[ -n "$TEMP_DIR" && -d "$TEMP_DIR" ]]; then
        printf 'Recovery artifacts retained at %s\n' "$TEMP_DIR" >&2
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

cd "$ROOT_DIR"
printf 'Building target and instrumentation APKs...\n'
"$ROOT_DIR/scripts/gradle-low-memory.sh" :app:assembleDebug :app:assembleDebugAndroidTest
[[ -f "$TARGET_APK" && -f "$TEST_APK" ]] || {
    printf 'Missing debug target/test APK output.\n' >&2
    exit 1
}
printf 'Installing APKs on disposable emulator %s without clearing existing data...\n' "$SERIAL"
"${ADB[@]}" install -r -d "$TARGET_APK"
"${ADB[@]}" install -r -d -t "$TEST_APK"

TEMP_DIR="$(mktemp -d -t bestiapop-backup-e2e.XXXXXX)"
APP_DATA_ARCHIVE="$TEMP_DIR/original-app-data.tar"
trap restore_everything EXIT
snapshot_app_data

PREVIOUS_TRANSPORT="$(
    "${ADB[@]}" shell bmgr list transports |
        tr -d '\r' |
        awk '/^[[:space:]]*\*/ { sub(/^[[:space:]]*\*[[:space:]]*/, ""); print; exit }'
)"
[[ -n "$PREVIOUS_TRANSPORT" ]] || {
    printf 'Could not determine the currently selected backup transport.\n' >&2
    exit 1
}
"${ADB[@]}" shell bmgr transport "$LOCAL_TRANSPORT"
TRANSPORT_CHANGED=1
"${ADB[@]}" shell bmgr wipe "$LOCAL_TRANSPORT" "$TARGET_PACKAGE"
LOCAL_DATA_WRITTEN=1

run_phase "phase1_seedCloudBackupContract" "Phase 1: seed include/exclude contract"
"${ADB[@]}" shell am force-stop "$TARGET_PACKAGE"

printf '\nBacking up fixture through AOSP LocalTransport...\n'
BACKUP_OUTPUT="$(
    "${ADB[@]}" shell bmgr backupnow --monitor "$TARGET_PACKAGE" 2>&1
)"
printf '%s\n' "$BACKUP_OUTPUT"
if [[ "$BACKUP_OUTPUT" != *"Success"* && "$BACKUP_OUTPUT" != *"result: 0"* ]]; then
    printf 'LocalTransport backup did not report success.\n' >&2
    exit 1
fi

RESTORE_TOKEN="$(
    "${ADB[@]}" shell bmgr list sets |
        tr -d '\r' |
        awk '$1 ~ /^[[:xdigit:]]+$/ && $2 == ":" { print $1; exit }'
)"
[[ -n "$RESTORE_TOKEN" ]] || {
    printf '%s\n' \
        "LocalTransport produced no restore set token after backup." \
        "This AOSP image exposes the transport but does not support bmgr restore sets." >&2
    exit 1
}

printf '\nClearing only the disposable fixture package, then restoring token %s...\n' \
    "$RESTORE_TOKEN"
"${ADB[@]}" shell pm clear "$TARGET_PACKAGE"
RESTORE_OUTPUT="$(
    "${ADB[@]}" shell bmgr restore "$RESTORE_TOKEN" "$TARGET_PACKAGE" --monitor 2>&1
)"
printf '%s\n' "$RESTORE_OUTPUT"
run_phase \
    "phase2_verifyCloudBackupIncludesAndExcludes" \
    "Phase 2: verify restored include/exclude contract"

printf '\nBackup/restore E2E passed. Restoring exact pre-test app data and transport...\n'
