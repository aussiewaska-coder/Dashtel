# Dashtel ~ Twilio Dialer for Android

**Your Twilio number. A native Android calling experience.**

Dashtel turns your Twilio number into a dedicated calling app for Android,
with system incoming-call controls, Bluetooth audio, and an adaptive interface
for phones, foldables, tablets, and Samsung DeX. Bring your own Twilio account
and make or receive calls over Wi-Fi or mobile data—no SIM calling required.

## Highlights

- Native Android Telecom integration for incoming calls and audio routing.
- Bluetooth, speaker and earpiece controls with a focused in-call keypad.
- Push-delivered incoming calls through Firebase Cloud Messaging.
- Contacts, grouped call history, and playback of available Twilio recordings.
- A separate Messages pane with searchable SMS conversations, drafts, unread
  counts, delivery status, and incoming Android notifications.
- Light and dark themes, with layouts that adapt to phone and desktop screens.
- Device-encrypted settings and a small, self-hosted Twilio Function backend.

Current outbound destinations are Australia (+61), Cambodia (+855), and
Thailand (+66). A Twilio account, voice-capable number, and Firebase setup are
required; normal Twilio usage charges apply.

### Project status

The project is now named **Dashtel**. The current 1.2.0 Android build and APK
are still labelled **DashPhone**; setup instructions below use that installed
name. The app is under active development. Validate incoming calls, audio
routing, background delivery and network handovers on your device before
relying on it. This is not a replacement for emergency calling through your
carrier.

## Architecture

The app uses Twilio Voice SDK, stores its account credentials encrypted on the
device, and signs short-lived Voice Access Tokens locally. Twilio Functions
provides call routing and an incoming-SMS notification relay; Vercel is not part
of either path. SMS sending and history use Twilio's Messages REST API directly.

## Call path

```text
Android app ── Voice SDK ── Twilio
     │                         │
     ├─ signs short-lived token│
     │                         ├─ outbound TwiML request ── Twilio Function
     │                         └─ inbound number call ───── Twilio Function
     └─ call history / recordings ── Twilio REST API
```

The Twilio API key secret is entered in Settings and encrypted at rest with an
Android Keystore key. It is not compiled into the APK or sent to Vercel. The
Android Keystore keeps key material non-exportable; a compromised or rooted
device can still access credentials while the app is using them. For stronger
account isolation, use a server-side token service and a restricted Twilio API
key.

## Configure Twilio

1. Create a Twilio API key and note the Account SID, API Key SID, and API Key
   Secret. The app needs Voice permissions plus Messages read/create and
   IncomingPhoneNumbers read/update for SMS and notification setup.
2. Create a TwiML App. After deploying the Function below, set its Voice URL to
   the Function's `/voice` URL using `POST`.
3. Point your Twilio phone number's incoming Voice webhook to the same Function
   URL using `POST`.
4. If you want incoming calls on Android, configure Firebase Cloud Messaging,
   create an FCM Push Credential in Twilio, and note its `CR…` SID. Use a real
   `google-services.json` for package `dev.dialer.voice` in the Android project.

The Function routes calls to the `DEFAULT_CLIENT_IDENTITY` client when the
Twilio number is called. The app's Device identity in Settings must match that
value. All app-originated calls use `TWILIO_NUMBER` as caller ID; the client
cannot override it in call parameters. The webhook is protected by Twilio's
request signature verification.

## Deploy the Twilio Function

Install the Twilio CLI and Serverless plugin, then authenticate the CLI with
your Twilio account. In `twilio-functions/`, copy `.env.example` to `.env` and
set `TWILIO_NUMBER` and `DEFAULT_CLIENT_IDENTITY`. Set `RECORD_CALLS=true` only
if you want outbound calls recorded. Then deploy:

```bash
cd twilio-functions
npm run deploy
```

The deployment prints a URL ending in `/voice`; use that URL for both the TwiML
App Voice URL and phone number webhook. The Function has no database, token
endpoint, recording proxy, or stored Twilio credentials.

## Configure and use the Android app

Build and install the app, open Settings, and enter:

- Account SID (`AC…`)
- API Key SID (`SK…`) and Secret
- TwiML App SID (`AP…`)
- Your Twilio phone number in E.164 form
- Push Credential SID (`CR…`) for incoming calls
- Device identity matching the Function setting (default: `default`)

Tap **Test**, then **Save**. Settings are encrypted with a device-bound key and
survive app updates. Android removes them if the app is uninstalled or its data
is cleared. Existing plaintext settings from the previous build are migrated
to encrypted storage the first time the new app opens. A test APK never contains
the Twilio API secret.

Grant microphone, notification, Bluetooth and Phone permissions when prompted,
and enable **DashPhone** in Android's calling accounts. The Phone permission
and calling account support Android Telecom integration; media still travels
through Twilio. Your SIM remains available for carrier calls. Contacts access
is used for the address book.

For incoming calls, the Firebase project, `google-services.json`, Twilio Push
Credential, and Access Token's `push_credential_sid` must all refer to the same
setup. Supply your own Firebase configuration locally; `google-services.json`
is excluded from version control. Never commit your live configuration,
service-account keys, Twilio credentials or signing keys.

Call history and recordings are read directly from Twilio by the Android app.
Twilio's parent and child call legs are grouped into one user-visible call, and
recordings from either leg are streamed into app-private cache for playback.
Recording is off by default. Inbound calls to a Twilio Client are not recorded
by the outbound `<Dial>` recording option.

## SMS setup and use

SMS requires an SMS-capable Twilio number. Sending uses the number in Settings,
and normal per-segment Twilio charges apply. The app supports SMS destinations
in Australia, Cambodia and Thailand, subject to Twilio's permissions and
country/sender requirements. It does not use SIM messaging permissions.

1. In the same Firebase project as the Android app, create a service account
   with the **Firebase Cloud Messaging API Admin** role. Enable the Firebase
   Cloud Messaging API if necessary, and generate a JSON private key.
2. Store that file at
   `twilio-functions/assets/fcm-service-account.private.json`. This path is
   Git-ignored. The `.private.json` suffix is essential: Twilio deploys it as a
   private asset, accessible to Functions at `/fcm-service-account.json`.
   Never put the key in Android resources or publish it as a public asset.
3. Redeploy the existing service with `npm run deploy`. Keep `/sms` **protected**
   and `/sms-status` public. The status endpoint returns only a readiness flag.
4. Open **Messages → Enable notifications**. The app checks number capability
   and relay readiness, then sets only the number's SMS URL/method. It refuses
   to overwrite a different SMS webhook or Messaging TwiML application.
   Allow Android notifications, including the **Messages** notification channel.

The SMS URL stores this handset's FCM registration token in a query parameter;
keep it private. Twilio signs incoming SMS requests, the relay checks the
account and receiving number, then sends a high-priority FCM data message to
that device. Enabling notifications on another handset transfers background
SMS alerts to that handset. This version supports one notification handset per
line; every configured handset can still refresh Twilio message history.
Token refresh updates the route while the app remains configured.

Messages are cached in app-private storage with local read state and drafts;
app backup is disabled. Opening Messages refreshes both inbound and outbound
history, with further refreshes only while the screen is resumed. **Load older
messages** pages through Twilio history. Tap a notification to open its thread.
Long notification previews are replaced with full text on refresh. Sent means
Twilio/carrier submission; **delivered** is shown only when Twilio reports it.
The part count is an estimate; carrier billing and encoding rules may differ.
After an uncertain network failure, refresh before sending again to avoid a
duplicate charge. Drafts remain available until a confirmed send.

MMS attachment counts are shown, but this version sends text only and does not
view media attachments. Alphanumeric senders and unsupported destinations are
readable but cannot be replied to. Background alerts require a deployed relay,
network access and Android notification permission; force-stopping the app
prevents FCM delivery until it is opened again. Messages remain available from
Twilio even if a push is missed. Numbers attached to a Twilio Messaging Service
must route incoming messages to the number's webhook for this relay to run.

## Android build

Copy `android/local.properties.example` to `android/local.properties` and set
the local Android SDK path. Put your real Firebase `google-services.json` in
`android/app/` before building. This local file is ignored by Git. Then build the APK
from Android Studio or with Gradle:

```bash
cd android
./gradlew assembleDebug
```

The APK is created at `android/app/build/outputs/apk/debug/DashPhone-debug.apk`.

For a hands-on locked-screen check, open the Settings cog and use
**Test locked-screen call in 10 seconds** under Connection & diagnostics. The
button saves the displayed Twilio settings, counts down for ten seconds, then
rings the configured DashPhone identity so there is time to turn off and lock
the handset. Creating the call may incur a Twilio charge.

To place a deliberate end-to-end incoming test call, connect the phone with ADB
and run the helper below. It checks the required runtime permissions and phone
connectivity, reads the caller ID from the `TWILIO_NUMBER` environment variable,
rings the app's `client:default` identity, and plays a spoken message after
answer. Set `TWILIO_NUMBER` privately to your own Twilio number first; optionally
set `DEFAULT_CLIENT_IDENTITY` to match your app. The helper reports
Twilio's final call status and asks for confirmation because the call may incur
charges. Pass words after the command to use a different spoken message.

```bash
tools/test-call.sh
tools/test-call.sh --yes
tools/test-call.sh --yes This is a custom test message
```

## Tests and checks

The Android unit suite covers token construction and signatures, credential
validation, phone-number normalization, and parent/child call grouping. The
Twilio Function uses Node's built-in test runner for inbound, outbound,
recording, and rejection routing.

```bash
tools/build-arm64.sh testDebugUnitTest assembleDebug lintDebug
cd twilio-functions && npm test
```
