# HA Panel DPC

Turn any old Android phone or tablet (Android 9+) into a Home Assistant wall
panel: factory reset, scan one QR code, log in — done.

This is a minimal Device Policy Controller (DPC). During Android's QR-code
device-owner provisioning it installs itself, then silently installs the
[HA Companion app](https://github.com/home-assistant/android) (minimal flavor,
no Google Play needed) and pins it fullscreen in lock task mode:

- No status bar, no navigation escape, survives reboots and app updates
- No Google account, no Play Store, no per-device fiddling
- Screen on/off, brightness, and updates of both the HA app and the DPC itself
  driven by HA automations (see [docs/automations.md](docs/automations.md))

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
it takes to change what future provisions install. Provisioned panels update
over the air with `UPDATE_PANEL` (see [Testing panel commands](#testing-panel-commands)).
Bump `versionCode` in `app/build.gradle.kts` for every published build:
Android refuses to install a lower one over the installed version.

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
   and the DPC installs the HA app in the background.
4. Finish the remaining setup wizard screens. The DPC pins the HA app only
   once setup is complete — a wizard left unfinished boots back to the
   welcome screen on the next restart.
5. Log in with your dedicated dashboard user, pick the default dashboard.
6. In Settings → Companion app, enable **Fullscreen** — this hides the
   navigation bar (the back arrow is already inert in lock task mode, but
   fullscreen removes it entirely). Do this NOW, while the sidebar is
   reachable: if your dashboard hides the sidebar (e.g. kiosk-mode plugin),
   this screen has no other practical way in on a locked panel.
7. In the Companion app sensor settings, enable any sensors you want
   (illuminance for auto-dimming, battery, etc.).

### Fallback: provisioning over USB

Devices whose setup wizard has no QR entry point (some AOSP tablets):

```sh
adb install app/build/outputs/apk/release/app-release.apk
adb shell dpm set-device-owner dk.yepzdk.hapanel/.AdminReceiver
```

Works only on a freshly reset device with no accounts added.

## Testing panel commands

Run each action from **Developer tools → Actions** (YAML mode). Replace
`notify.mobile_app_kitchen_panel` with the panel's service: find the device
under Settings → Devices & services → Mobile App. Every provisioning registers
a new device, so delete stale entries and use the newest — a command sent to a
stale entry silently goes nowhere. For automations, see
[docs/automations.md](docs/automations.md).

Enable the panel's **Screen brightness** sensor (in the entity's settings in
HA) to see results without walking to the panel. The app reports it on its
next sensor update, so allow a few seconds.

1. **Delivery check** — set a brightness and watch the sensor follow. If the
   action fails with "not connected to local push notifications", or the
   sensor does not change, the panel is not receiving commands and the steps
   below cannot work.

   ```yaml
   action: notify.mobile_app_kitchen_panel
   data:
     message: command_broadcast_intent
     data:
       intent_package_name: dk.yepzdk.hapanel
       intent_action: dk.yepzdk.hapanel.SET_BRIGHTNESS
       intent_extras: "brightness:128"
   ```

   Not `command_webview`: it needs "Display over other apps" for the HA app,
   which the DPC does not grant, so it does nothing on a panel.

2. **Brightness** 0–255, or `auto` to hand control back to the light sensor:

   ```yaml
   action: notify.mobile_app_kitchen_panel
   data:
     message: command_broadcast_intent
     data:
       intent_package_name: dk.yepzdk.hapanel
       intent_action: dk.yepzdk.hapanel.SET_BRIGHTNESS
       intent_extras: "brightness:auto"
   ```

3. **Screen off**, then **screen on**. Off is a black overlay with the
   backlight at its minimum; the device stays awake, so HA can still wake it.
   A tap on the panel wakes it too.

   ```yaml
   action: notify.mobile_app_kitchen_panel
   data:
     message: command_broadcast_intent
     data:
       intent_package_name: dk.yepzdk.hapanel
       intent_action: dk.yepzdk.hapanel.SCREEN_OFF
   ```

   ```yaml
   action: notify.mobile_app_kitchen_panel
   data:
     message: command_broadcast_intent
     data:
       intent_package_name: dk.yepzdk.hapanel
       intent_action: dk.yepzdk.hapanel.SCREEN_ON
   ```

   The Companion app's own `command_screen_on` does not remove the overlay.

4. **Update the HA app** — re-downloads and reinstalls it. The dashboard
   disappears while it reinstalls, then HA comes back pinned:

   ```yaml
   action: notify.mobile_app_kitchen_panel
   data:
     message: command_broadcast_intent
     data:
       intent_package_name: dk.yepzdk.hapanel
       intent_action: dk.yepzdk.hapanel.UPDATE_HA
   ```

5. **Update the DPC** — installs the latest release. The panel shows
   "Updating panel…", then "Panel updated to x.y.z", and returns to the
   dashboard. A failed download or install shows "Panel update failed" with
   the reason:

   ```yaml
   action: notify.mobile_app_kitchen_panel
   data:
     message: command_broadcast_intent
     data:
       intent_package_name: dk.yepzdk.hapanel
       intent_action: dk.yepzdk.hapanel.UPDATE_PANEL
   ```

   To try a build before releasing it, pass its URL. Keep the `:String`
   suffix: the Companion app splits extras on `:`, and the suffix makes it
   join the URL back together.

   ```yaml
       intent_extras: "url:https://github.com/yepzdk/ha-panel-dpc/releases/download/v0.4.1-rc.1/ha-panel-dpc.apk:String"
   ```

   The build must be signed with the release key and have the same or a
   higher `versionCode`. Android refuses anything else, and the panel keeps
   running the version it has.

6. **Factory reset** — wipes the panel back to the welcome screen. Run last:

   ```yaml
   action: notify.mobile_app_kitchen_panel
   data:
     message: command_broadcast_intent
     data:
       intent_package_name: dk.yepzdk.hapanel
       intent_action: dk.yepzdk.hapanel.FACTORY_RESET
       intent_extras: "confirm:wipe"
   ```

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
- **Panel boots to the welcome screen after a restart** — the setup wizard
  was never completed. Earlier DPC builds pinned HA on top of the wizard
  mid-setup; re-provision with a current build and finish the wizard.
- **"This device belongs to your organization" screens** — expected on
  Android 12+, not an error.

## How the pieces fit

| Component | Role |
| --- | --- |
| `AdminReceiver` | Device-owner identity; legacy (Android 9–11) provisioning hook |
| `GetProvisioningModeActivity` / `PolicyComplianceActivity` | Android 12+ QR provisioning flow |
| `KioskSetup` | Applies all policies: lock task allowlist, persistent HOME, keyguard/status bar off, stay-on-while-plugged, no screen dimming |
| `InstallService` | Downloads + silently installs the HA Companion APK, or a new version of the DPC itself |
| `HomeActivity` | Persistent HOME trampoline — anything that lands on "home" re-pins the HA app |
| `ScreenOffActivity` | Black overlay for `SCREEN_OFF`; the device stays awake so HA can wake it again |
| `PackageReplacedReceiver` | After a DPC update: re-applies policies and re-pins the dashboard |
| `PanelCommandReceiver` | HA-driven commands: `SCREEN_OFF`, `SCREEN_ON`, `SET_BRIGHTNESS`, `UPDATE_HA`, `UPDATE_PANEL`, `FACTORY_RESET` |
| `provisioning/make-qr.sh` | Renders the provisioning QR from your local config |

Development builds: `export JAVA_HOME=$(/usr/libexec/java_home -v 17+)` (or a
Homebrew JDK ≤25) before running `./gradlew`, since Gradle may not support the
newest installed JDK.
