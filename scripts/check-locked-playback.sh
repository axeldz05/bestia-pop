#!/usr/bin/env bash
set -Eeuo pipefail

PACKAGE="com.bestiapop.android"
WAIT_SECONDS="${WAIT_SECONDS:-15}"
SERIAL="${ANDROID_SERIAL:-}"

service_snapshot() {
    local dump="$1"
    local foreground=false
    local foreground_id=false
    [[ "$dump" == *"isForeground=true"* ]] && foreground=true
    [[ "$dump" == *"foregroundId=1001"* ]] && foreground_id=true
    printf '%s %s\n' "$foreground" "$foreground_id"
}

media_snapshot() {
    local dump="$1"
    local tail="${dump#*package=$PACKAGE}"
    local block="${tail%%audioAttrs=*}"
    local active=false
    local state=-1
    [[ "$tail" != "$dump" && "$block" == *"active=true"* ]] && active=true
    if [[ "$block" =~ state=PlaybackState[[:space:]]\{state=[A-Z_]+\(([0-9]+)\) ]]; then
        state="${BASH_REMATCH[1]}"
    fi
    printf '%s %s\n' "$active" "$state"
}

playback_notification_visible() {
    local dump="$1"
    local line
    while IFS= read -r line; do
        if [[ "$line" == *"NotificationRecord("* &&
            "$line" == *"pkg=$PACKAGE "* &&
            "$line" == *"id=1001 "* ]]; then
            printf 'true\n'
            return
        fi
    done <<<"$dump"
    printf 'false\n'
}

command -v adb >/dev/null 2>&1 || {
    printf 'adb is required on PATH\n' >&2
    exit 1
}

if [[ -z "$SERIAL" ]]; then
    mapfile -t DEVICES < <(adb devices | awk '$2 == "device" { print $1 }')
    if ((${#DEVICES[@]} != 1)); then
        printf 'Expected exactly one connected device; found %d.\n' "${#DEVICES[@]}" >&2
        exit 1
    fi
    SERIAL="${DEVICES[0]}"
fi
ADB=(adb -s "$SERIAL")

pid_before="$("${ADB[@]}" shell pidof "$PACKAGE" | tr -d '\r')"
[[ -n "$pid_before" ]] || {
    printf 'BestiaPop is not running. Install with ./install.sh and start playback first.\n' >&2
    exit 1
}

power_dump="$("${ADB[@]}" shell dumpsys power)"
was_awake=0
if [[ "$power_dump" == *"mWakefulness=Awake"* || "$power_dump" == *"mInteractive=true"* ]]; then
    was_awake=1
fi

restore_screen() {
    if ((was_awake == 1)); then
        "${ADB[@]}" shell input keyevent KEYCODE_WAKEUP >/dev/null
    fi
}
trap restore_screen EXIT

user_id="$("${ADB[@]}" shell am get-current-user | tr -d '\r')"
restriction="$("${ADB[@]}" shell cmd activity get-bg-restriction-level \
    --user "$user_id" "$PACKAGE" | tr -d '\r')"
app_op="$("${ADB[@]}" shell cmd appops get \
    --user "$user_id" "$PACKAGE" RUN_ANY_IN_BACKGROUND | tr -d '\r')"

printf 'PID before lock: %s\n' "$pid_before"
printf 'Background restriction: %s\n' "$restriction"
printf 'RUN_ANY_IN_BACKGROUND: %s\n' "$app_op"
printf 'Locking screen for %ss...\n' "$WAIT_SECONDS"

"${ADB[@]}" shell input keyevent KEYCODE_SLEEP >/dev/null
sleep "$WAIT_SECONDS"

pid_after="$("${ADB[@]}" shell pidof "$PACKAGE" | tr -d '\r')"
service_dump="$("${ADB[@]}" shell dumpsys activity services "$PACKAGE")"
notification_dump="$("${ADB[@]}" shell dumpsys notification --noredact)"
media_dump="$("${ADB[@]}" shell dumpsys media_session)"
read -r foreground_after foreground_id_after \
    < <(service_snapshot "$service_dump")
read -r media_active_after media_state_after \
    < <(media_snapshot "$media_dump")
notification_visible_after="$(playback_notification_visible "$notification_dump")"

[[ "$pid_after" == "$pid_before" ]] || {
    printf 'BestiaPop PID changed or died while locked: before=%s after=%s\n' \
        "$pid_before" "${pid_after:-none}" >&2
    exit 1
}
[[ "$service_dump" == *"MusicService"* &&
    ("$foreground_after" == true || "$foreground_id_after" == true) ]] || {
    printf 'MusicService is not reported as foreground while locked.\n' >&2
    exit 1
}
[[ "$notification_visible_after" == true ]] || {
    printf 'Playback notification 1001 is not visible while locked.\n' >&2
    exit 1
}
[[ "$media_active_after" == true && "$media_state_after" == 3 ]] || {
    printf 'MediaSession is not reported as playing while locked.\n' >&2
    exit 1
}

printf 'PASS: same PID, active MediaSession, foreground service and notification while locked.\n'
