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

### 2. Publish the APK as a GitHub release

The setup wizard downloads the DPC over plain HTTP(S) and cannot
authenticate, so the release must be publicly downloadable (this repo is
public for exactly that reason — no secrets live here):

```sh
cp app/build/outputs/apk/release/app-release.apk ha-panel-dpc.apk
gh release create v0.1.0 ha-panel-dpc.apk --title "v0.1.0"
# → https://github.com/yepzdk/ha-panel-dpc/releases/latest/download/ha-panel-dpc.apk
```

The QR always points at `releases/latest`, so publishing a new release is all
it takes to change what future provisions install.

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
5. In Settings → Companion app, enable **Fullscreen** — this hides the
   navigation bar (the back arrow is already inert in lock task mode, but
   fullscreen removes it entirely). Do this NOW, while the sidebar is
   reachable: if your dashboard hides the sidebar (e.g. kiosk-mode plugin),
   this screen has no other practical way in on a locked panel.
6. In the Companion app sensor settings, enable any sensors you want
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
- **"Couldn't connect to Wi-Fi"** — two known causes:
  1. Corrupted password: open the generated `payload.json` and check it is
     *exactly* right — double quotes in `local.env` let bash expand `$` and
     backticks (use single quotes).
  2. WPA3-only (SAE) network: Android's provisioning supports only
     NONE/WPA/WEP/EAP — `WPA` covers WPA/WPA2-PSK and WPA2+WPA3
     transition mode, but a WPA3-only SSID cannot work. Provision on a
     WPA2/transition SSID (guest network is fine) instead.
  Hidden SSIDs need `"android.app.extra.PROVISIONING_WIFI_HIDDEN": true`.
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
