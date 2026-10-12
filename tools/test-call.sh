#!/bin/sh
# Ring the installed app through Twilio and play a spoken end-to-end test message.
set -eu

package=dev.dialer.voice
twilio_number=${TWILIO_NUMBER:-}
client_identity=${DEFAULT_CLIENT_IDENTITY:-default}
message='Hello. This is your DashPhone test call. The app can receive calls.'
confirmed=false

if ! printf '%s\n' "$twilio_number" | grep -Eq '^\+[1-9][0-9]{6,14}$'; then
    echo "Set TWILIO_NUMBER privately to your Twilio number in E.164 format." >&2
    exit 1
fi

if [ "${1:-}" = "--yes" ]; then
    confirmed=true
    shift
fi
if [ "$#" -gt 0 ]; then
    message=$*
fi

command -v adb >/dev/null 2>&1 || {
    echo "ADB is not installed or is not on PATH." >&2
    exit 1
}
command -v twilio >/dev/null 2>&1 || {
    echo "Twilio CLI is not installed or is not on PATH." >&2
    exit 1
}

adb get-state >/dev/null
if ! adb shell pm path "$package" 2>/dev/null | grep -q '^package:'; then
    echo "DashPhone is not installed on the connected device." >&2
    exit 1
fi

package_dump=$(adb shell dumpsys package "$package" | tr -d '\r')
if ! printf '%s\n' "$package_dump" | grep -q 'android.permission.RECORD_AUDIO: granted=true'; then
    echo "Microphone permission is not granted. Open the app, allow it, then retry." >&2
    exit 1
fi
echo "Permissions: microphone granted."

sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
if [ "$sdk" -ge 31 ]; then
    if printf '%s\n' "$package_dump" | grep -q 'android.permission.BLUETOOTH_CONNECT: granted=true'; then
        echo "Permissions: nearby Bluetooth devices granted."
    else
        echo "Warning: nearby Bluetooth devices is not granted; phone audio will be used." >&2
    fi
fi
if [ "$sdk" -ge 33 ]; then
    if printf '%s\n' "$package_dump" | grep -q 'android.permission.POST_NOTIFICATIONS: granted=true'; then
        echo "Permissions: notifications granted."
    else
        echo "Incoming-call notifications are not granted. Open the app, allow them, then retry." >&2
        exit 1
    fi
fi

connectivity=$(adb shell dumpsys connectivity | tr -d '\r')
if ! printf '%s\n' "$connectivity" | grep -q 'Active default network:'; then
    echo "No active network is available on the phone." >&2
    exit 1
fi
if ! printf '%s\n' "$connectivity" | grep -q 'IS_VALIDATED'; then
    echo "The phone's active network has not been validated for internet access." >&2
    exit 1
fi
echo "Network: active internet connection validated."

if [ "$confirmed" != true ]; then
    printf 'Ring the app through Twilio and play the test message? This may incur charges. [y/N] '
    read -r answer
    case "$answer" in
        y|Y|yes|YES) ;;
        *) echo "Cancelled."; exit 0 ;;
    esac
fi

# Bring the process up so it refreshes its access token and push registration.
adb shell am start -n "$package/.MainActivity" >/dev/null
sleep 2

# XML-escape the only user-controlled TwiML content.
escaped_message=$(printf '%s' "$message" \
    | sed 's/&/\&amp;/g; s/</\&lt;/g; s/>/\&gt;/g; s/"/\&quot;/g; s/'"'"'/\&apos;/g')
twiml="<Response><Say voice=\"Polly.Nicole\">$escaped_message</Say><Pause length=\"2\"/></Response>"

create_result=$(twilio api:core:calls:create \
    --to "client:$client_identity" \
    --from "$twilio_number" \
    --twiml "$twiml" \
    --properties sid,status \
    -o tsv)
call_sid=$(printf '%s\n' "$create_result" | awk '$1 ~ /^CA[[:alnum:]]+$/ { print $1; exit }')
if [ -z "$call_sid" ]; then
    echo "Twilio did not return a Call SID." >&2
    printf '%s\n' "$create_result" >&2
    exit 1
fi

echo "Twilio call $call_sid created. Answer it on the device to hear the message."
elapsed=0
while [ "$elapsed" -lt 45 ]; do
    result=$(twilio api:core:calls:fetch \
        --sid "$call_sid" \
        --properties status,duration \
        -o tsv 2>/dev/null || true)
    status=$(printf '%s\n' "$result" | awk 'NR == 2 { print $1 }')
    duration=$(printf '%s\n' "$result" | awk 'NR == 2 { print $2 }')
    case "$status" in
        completed)
            echo "SUCCESS: the app answered and Twilio completed the spoken test call (${duration:-0}s)."
            exit 0
            ;;
        failed|busy|no-answer|canceled)
            echo "FAILED: Twilio ended the test call with status '$status'." >&2
            exit 1
            ;;
    esac
    sleep 2
    elapsed=$((elapsed + 2))
done

echo "No final Twilio result after 45 seconds; check the phone and Twilio console." >&2
exit 3
