# HA Panel DPC

Turn any old Android phone or tablet (Android 9+) into a Home Assistant wall
panel: factory reset, scan one QR code, log in — done.

This is a minimal Device Policy Controller (DPC). During Android's QR-code
device-owner provisioning it installs itself, then silently installs the
[HA Companion app](https://github.com/home-assistant/android) (minimal flavor,
no Google Play needed) and pins it fullscreen in lock task mode:

- No status bar, no navigation escape, survives reboots and app updates
- No Google account, no Play Store, no per-device fiddling
- Screen on/off, brightness, and app updates driven by HA automations
  (see [docs/automations.md](docs/automations.md))

## One-time setup

### 1. Build the signed APK

Requires a JDK (17+) and the Android SDK (`brew install --cask temurin@17`,
`brew install android-commandlinetools qrencode`).

```sh
# Generate the signing keystore — ONCE, then back it up. The certificate hash
# is baked into every provisioning QR; losing or rotating the key means
# re-provisioning every panel.
keytool -genkeypair -v -keystore keystore.jks -alias hapanel \
  -keyalg RSA -keysize 4096 -validity 10950

cp keystore.properties.example keystore.properties  # fill in the passwords
./gradlew assembleRelease
```

The APK lands in `app/build/outputs/apk/release/app-release.apk`.

### 2. Serve the APK during provisioning

The setup wizard downloads the DPC over plain unauthenticated HTTP(S) — it
cannot authenticate, so a private GitHub release won't work. The device is
already on your Wi-Fi at that point, and the URL only needs to be live while
you provision, so an ad-hoc server on your machine is enough:

```sh
mkdir -p /tmp/dpc && cp app/build/outputs/apk/release/app-release.apk /tmp/dpc/ha-panel-dpc.apk
python3 -m http.server 8000 -d /tmp/dpc
# → http://<your-machine's-LAN-IP>:8000/ha-panel-dpc.apk  (Ctrl-C when done)
```

Alternative: anything unauthenticated on the LAN works, e.g. HA's `www`
folder (`config/www/ha-panel-dpc.apk` → `http://homeassistant.local:8123/local/ha-panel-dpc.apk`).

### 3. Generate the QR code

```sh
cd provisioning
cp local.env.example local.env   # fill in Wi-Fi credentials + your APK URL
./make-qr.sh                     # writes qr.png
```

`local.env`, the generated payload, and the QR are gitignored — the payload
contains your Wi-Fi password.

## Per device (~5 minutes)

1. Factory reset.
2. On the "Hi there" welcome screen, tap the same spot **6 times** — a QR
   scanner opens.
3. Scan `qr.png`. The device joins Wi-Fi, installs the DPC as device owner,
   the DPC installs the HA app and pins it.
4. Log in with your dedicated dashboard user, pick the default dashboard.
5. In the Companion app sensor settings, enable any sensors you want
   (illuminance for auto-dimming, battery, etc.).

### Fallback: provisioning over USB

Devices whose setup wizard has no QR entry point (some AOSP tablets):

```sh
adb install app/build/outputs/apk/release/app-release.apk
adb shell dpm set-device-owner dk.yepzdk.hapanel/.AdminReceiver
```

Works only on a freshly reset device with no accounts added.

## Troubleshooting provisioning

- **"Can't set up device" right after scanning** — usually the checksum.
  `make-qr.sh` computes it from the keystore; if you rebuilt with a different
  key, regenerate the QR. The value must be URL-safe base64 without `=` padding.
- **Download fails** — the APK URL must be reachable *from the panel's Wi-Fi*
  without auth. Test it in a phone browser on that network.
- **No QR scanner appears when tapping the welcome screen** — the device's
  setup wizard lacks QR support (common on no-GMS AOSP tablets). Use the USB
  fallback above.
- **Provisioning "not supported"** — check the device declares device admin:
  `adb shell pm list features | grep device_admin`.
- **"This device belongs to your organization" screens** — expected on
  Android 12+, not an error.

## How the pieces fit

| Component | Role |
| --- | --- |
| `AdminReceiver` | Device-owner identity; legacy (Android 9–11) provisioning hook |
| `GetProvisioningModeActivity` / `PolicyComplianceActivity` | Android 12+ QR provisioning flow |
| `KioskSetup` | Applies all policies: lock task allowlist, persistent HOME, keyguard/status bar off, stay-on-while-plugged |
| `InstallService` | Downloads + silently installs the HA Companion APK |
| `HomeActivity` | Persistent HOME trampoline — anything that lands on "home" re-pins the HA app |
| `PanelCommandReceiver` | HA-driven commands: `SCREEN_OFF`, `SET_BRIGHTNESS`, `UPDATE_HA` |
| `provisioning/make-qr.sh` | Renders the provisioning QR from your local config |

Development builds: `export JAVA_HOME=$(/usr/libexec/java_home -v 17+)` (or a
Homebrew JDK ≤25) before running `./gradlew`, since Gradle may not support the
newest installed JDK.
