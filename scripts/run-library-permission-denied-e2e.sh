#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET_PACKAGE="com.bestiapop.android"
TEST_PACKAGE="com.bestiapop.android.test"
RUNNER_COMPONENT="${TEST_PACKAGE}/androidx.test.runner.AndroidJUnitRunner"
TEST_METHOD="com.bestiapop.android.ui.LibraryPermissionDeniedHostE2ETest#deniedAudioPermission_firstLaunchKeepsImportPendingAndActivityUsable"
TARGET_APK="app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
SERIAL=""
TEMP_DIR=""
ARCHIVE=""
SNAPSHOT_READY=0
PREVIOUS_AUDIO_GRANT=0
PREVIOUS_NOTIFICATION_GRANT=0

usage() {
    printf '%s\n' \
        "Usage: $0 [--serial DEVICE_SERIAL]" \
        "" \
        "Runs the real denied READ_MEDIA_AUDIO first-launch flow. The script snapshots exact" \
        "internal app data and permission grants, restores both on EXIT, and never uninstalls."
}

while (($# > 0)); do
    case "$1" in
        --serial)
            [[ $# -ge 2 ]] || { printf 'Missing serial\n' >&2; exit 2; }
            SERIAL="$2"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            printf 'Unknown option: %s\n' "$1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

for command in adb gradle tar; do
    command -v "$command" >/dev/null 2>&1 || {
        printf '%s is required on PATH\n' "$command" >&2
        exit 1
    }
done

if [[ -z "$SERIAL" ]]; then
    mapfile -t devices < <(adb devices | awk '$2 == "device" { print $1 }')
    ((${#devices[@]} == 1)) || {
        printf 'Expected one connected device; found %d. Use --serial.\n' "${#devices[@]}" >&2
        exit 1
    }
    SERIAL="${devices[0]}"
fi
ADB=(adb -s "$SERIAL")

permission_granted() {
    local permission="$1"
    "${ADB[@]}" shell dumpsys package "$TARGET_PACKAGE" |
        tr -d '\r' |
        awk -v permission="$permission" '
            index($0, permission ": granted=true") > 0 { found=1 }
            END { exit !found }
        '
}

restore_everything() {
    local exit_code=$?
    trap - EXIT
    set +e
    "${ADB[@]}" shell am force-stop "$TARGET_PACKAGE"
    "${ADB[@]}" shell am force-stop "$TEST_PACKAGE"
    if ((SNAPSHOT_READY == 1)); then
        "${ADB[@]}" shell pm clear "$TARGET_PACKAGE" >/dev/null
        "${ADB[@]}" shell -T run-as "$TARGET_PACKAGE" tar -C . -xf - <"$ARCHIVE"
        restore_code=$?
        if ((PREVIOUS_AUDIO_GRANT == 1)); then
            "${ADB[@]}" shell pm grant "$TARGET_PACKAGE" android.permission.READ_MEDIA_AUDIO
        fi
        if ((PREVIOUS_NOTIFICATION_GRANT == 1)); then
            "${ADB[@]}" shell pm grant "$TARGET_PACKAGE" android.permission.POST_NOTIFICATIONS
        fi
        if ((restore_code != 0 && exit_code == 0)); then
            exit_code=$restore_code
        fi
    fi
    if [[ -n "$TEMP_DIR" && -d "$TEMP_DIR" && $exit_code -eq 0 ]]; then
        rm -rf "$TEMP_DIR"
    elif [[ -n "$TEMP_DIR" && -d "$TEMP_DIR" ]]; then
        printf 'Recovery archive retained at %s\n' "$TEMP_DIR" >&2
    fi
    exit "$exit_code"
}

cd "$ROOT_DIR"
SDK="$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r[:space:]')"
((SDK >= 33)) || { printf 'READ_MEDIA_AUDIO requires API 33+; got %s\n' "$SDK" >&2; exit 1; }

"$ROOT_DIR/scripts/gradle-low-memory.sh" :app:assembleDebug :app:assembleDebugAndroidTest
"${ADB[@]}" install -r -d "$TARGET_APK"
"${ADB[@]}" install -r -d -t "$TEST_APK"

TEMP_DIR="$(mktemp -d -t bestiapop-permission-e2e.XXXXXX)"
ARCHIVE="$TEMP_DIR/original-app-data.tar"
trap restore_everything EXIT

"${ADB[@]}" shell am force-stop "$TARGET_PACKAGE"
"${ADB[@]}" shell am force-stop "$TEST_PACKAGE"
permission_granted android.permission.READ_MEDIA_AUDIO && PREVIOUS_AUDIO_GRANT=1
permission_granted android.permission.POST_NOTIFICATIONS && PREVIOUS_NOTIFICATION_GRANT=1
"${ADB[@]}" exec-out run-as "$TARGET_PACKAGE" tar -C . -cf - . >"$ARCHIVE"
tar -tf "$ARCHIVE" >/dev/null
SNAPSHOT_READY=1

"${ADB[@]}" shell pm revoke "$TARGET_PACKAGE" android.permission.READ_MEDIA_AUDIO >/dev/null 2>&1 || true
"${ADB[@]}" shell pm grant "$TARGET_PACKAGE" android.permission.POST_NOTIFICATIONS

output="$(
    "${ADB[@]}" shell am instrument -w \
        -e class "$TEST_METHOD" \
        "$RUNNER_COMPONENT" 2>&1
)"
printf '%s\n' "$output"
[[ "$output" == *"OK (1 test)"* && "$output" != *"FAILURES!!!"* ]] || {
    printf 'Denied audio permission E2E failed.\n' >&2
    exit 1
}

printf 'Denied audio permission E2E passed; restoring app data and grants.\n'
