# HA Panel DPC

Turn any old Android phone or tablet (Android 9+) into a Home Assistant wall
panel: factory reset, scan one QR code, log in — done.

This is a minimal Device Policy Controller (DPC). During Android's QR-code
device-owner provisioning it installs itself, then silently installs the
[HA Companion app](https://github.com/home-assistant/android) (minimal flavor,
no Google Play needed) and pins it fullscreen in lock task mode:

- No status bar, no navigation escape, survives reboots and app updates
- No Google account, no Play Store, no per-device fiddling
- Screen on/off, brightness, navigation, restarts, maintenance access, and
  updates of both the HA app and the DPC itself driven by HA automations
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
it takes to change what future provisions install. Provisioned panels update
over the air with `UPDATE_PANEL` (see [Testing panel commands](#testing-panel-commands)).
Bump `versionCode` in `app/build.gradle.kts` for every published build:
Android refuses to install a lower one over the installed version. GitHub
takes about a minute to point `releases/latest` at a new release; an update
sent before then installs the previous one again.

### 3. Generate the QR code

```sh
cd provisioning
cp local.env.example local.env   # fill in Wi-Fi credentials + your APK URL
./make-qr.sh                     # writes qr.png
```

Set the optional `HA_URL` to your HA server (e.g. `http://homeassistant.local:8123`)
and the HA app opens its onboarding with that server filled in on the panel's
first launch, so only the login is left.

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
   welcome screen on the next restart. If the wizard asked for a Wi-Fi
   before the QR scanner, the network picked there does not stick: once
   setup is complete, the DPC forgets every saved network except the QR's,
   so the panel cannot rejoin, say, an isolated guest network after a
   restart. Listing saved networks needs location access, so the DPC grants
   it to itself for this one step and, if location is off, turns it on for
   the moment it takes (Android 11+; on 9 and 10 the step is skipped with
   location off). Networks added later, e.g. in maintenance mode, stay.
5. Log in with your dedicated dashboard user, pick the default dashboard. How
   to set up a user, default dashboard and kiosk view per panel:
   [Setting up users and dashboards for panels](https://github.com/yepzdk/ha-smart-panel/blob/main/docs/panel-setup.md).
6. In Settings → Companion app, enable **Fullscreen** — this hides the
   navigation bar (the back arrow is already inert in lock task mode, but
   fullscreen removes it entirely). Do this while the sidebar is reachable:
   if your dashboard hides it with the kiosk-mode plugin, the way back is
   `NAVIGATE` to the dashboard with `?disable_km` (see
   [Testing panel commands](#testing-panel-commands)).
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

> **Using the [Smart Panel integration](https://github.com/yepzdk/ha-smart-panel)?**
> Use its entities and actions instead (for example `smart_panel.navigate`):
> they target the panel by device, and need neither a notify service nor the
> command format below.

Run each action from **Developer tools → Actions** (YAML mode). Replace
`notify.mobile_app_<your_panel>` with the panel's notify action: find the
device under Settings → Devices & services → Mobile App. Every provisioning
registers a new device, so delete stale entries and use the newest — a command
sent to a stale entry silently goes nowhere. Keep `dk.yepzdk.hapanel` as it
is: it is the panel app's package name, the same on every panel. For
automations, see [docs/automations.md](docs/automations.md).

Enable the panel's **Screen brightness** sensor (in the entity's settings in
HA) to see results without walking to the panel. The app reports it on its
next sensor update, so allow a few seconds.

1. **Delivery check** — set a brightness and watch the sensor follow. If the
   action fails with "not connected to local push notifications", or the
   sensor does not change, the panel is not receiving commands and the steps
   below cannot work.

   ```yaml
   action: notify.mobile_app_<your_panel>
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
   action: notify.mobile_app_<your_panel>
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

   **Avoid for now:** after a screen off, a panel can stay unreachable until
   it restarts, and a tap may not wake it. See
   [home-assistant/core#176371](https://github.com/home-assistant/core/issues/176371);
   dim with `SET_BRIGHTNESS` instead.

   ```yaml
   action: notify.mobile_app_<your_panel>
   data:
     message: command_broadcast_intent
     data:
       intent_package_name: dk.yepzdk.hapanel
       intent_action: dk.yepzdk.hapanel.SCREEN_OFF
   ```

   ```yaml
   action: notify.mobile_app_<your_panel>
   data:
     message: command_broadcast_intent
     data:
       intent_package_name: dk.yepzdk.hapanel
       intent_action: dk.yepzdk.hapanel.SCREEN_ON
   ```

   The Companion app's own `command_screen_on` does not remove the overlay.

4. **Open a page** — opens HA at `path` and loads it fresh, so it also works
   as a reload after a dashboard change. Without `path` it opens the default
   dashboard. `/profile` reaches **Log out** when the dashboard hides the
   sidebar, and `?disable_km` skips the kiosk-mode plugin for one load:

   ```yaml
   action: notify.mobile_app_<your_panel>
   data:
     message: command_broadcast_intent
     data:
       intent_package_name: dk.yepzdk.hapanel
       intent_action: dk.yepzdk.hapanel.NAVIGATE
       intent_extras: "path:/profile"
   ```

5. **Maintenance mode** — unpins HA and enables the status bar, so Android
   settings are reachable from quick settings (e.g. Apps → Home Assistant →
   Storage → Clear data to log the app out). HA reopens on its own; the home
   button returns to it. `MAINTENANCE_OFF` pins it again, and so does a
   restart, so a panel cannot be left unlocked by mistake:

   ```yaml
   action: notify.mobile_app_<your_panel>
   data:
     message: command_broadcast_intent
     data:
       intent_package_name: dk.yepzdk.hapanel
       intent_action: dk.yepzdk.hapanel.MAINTENANCE_ON
   ```

   ```yaml
   action: notify.mobile_app_<your_panel>
   data:
     message: command_broadcast_intent
     data:
       intent_package_name: dk.yepzdk.hapanel
       intent_action: dk.yepzdk.hapanel.MAINTENANCE_OFF
   ```

6. **Restart** the device. Android refuses during a phone call, and the
   panel shows why:

   ```yaml
   action: notify.mobile_app_<your_panel>
   data:
     message: command_broadcast_intent
     data:
       intent_package_name: dk.yepzdk.hapanel
       intent_action: dk.yepzdk.hapanel.REBOOT
   ```

7. **Update the HA app** — re-downloads and reinstalls it. The dashboard
   disappears while it reinstalls, then HA comes back pinned:

   ```yaml
   action: notify.mobile_app_<your_panel>
   data:
     message: command_broadcast_intent
     data:
       intent_package_name: dk.yepzdk.hapanel
       intent_action: dk.yepzdk.hapanel.UPDATE_HA
   ```

8. **Update the DPC** — installs the latest release. The panel shows
   "Updating panel…", then "Panel updated to x.y.z", and returns to the
   dashboard. A failed download or install shows "Panel update failed" with
   the reason:

   ```yaml
   action: notify.mobile_app_<your_panel>
   data:
     message: command_broadcast_intent
     data:
       intent_package_name: dk.yepzdk.hapanel
       intent_action: dk.yepzdk.hapanel.UPDATE_PANEL
   ```

   To try a build before releasing it, publish it as a pre-release (it never
   becomes `releases/latest`) and pass its URL. Keep the `:String` suffix: the
   Companion app splits extras on `:`, and the suffix makes it join the URL
   back together.

   ```yaml
       intent_extras: "url:https://github.com/yepzdk/ha-panel-dpc/releases/download/<tag>/ha-panel-dpc.apk:String"
   ```

   The build must be signed with the release key and have the same or a
   higher `versionCode`. Android refuses anything else, and the panel keeps
   running the version it has.

9. **Link to a status webhook** — the panel then POSTs its state as JSON
   (versions, maintenance, screen off, brightness, device model) to `url`:
   right away, after every screen, brightness or maintenance change, after a
   restart or an update of either app, and every 15 minutes. The Smart Panel
   integration sends this itself; to watch the reports by hand, point `url` at
   an automation with a webhook trigger. `ref` (letters, digits, `_`, `-`, up
   to 64) is echoed in every report. Keep the `:String` suffixes: the URL
   contains `:`, and a `ref` of only digits would otherwise arrive as a number:

   ```yaml
   action: notify.mobile_app_<your_panel>
   data:
     message: command_broadcast_intent
     data:
       intent_package_name: dk.yepzdk.hapanel
       intent_action: dk.yepzdk.hapanel.LINK
       intent_extras: "ref:<your_ref>:String,url:http://homeassistant.local:8123/api/webhook/<webhook_id>:String"
   ```

   An invalid `ref` or a URL that is not `http(s)` is ignored. Without a
   working network the report is skipped; the next one carries fresh state.

10. **Report now**, then **unlink** — `REPORT` sends the state immediately;
    `UNLINK` forgets the URL and stops all reports:

    ```yaml
    action: notify.mobile_app_<your_panel>
    data:
      message: command_broadcast_intent
      data:
        intent_package_name: dk.yepzdk.hapanel
        intent_action: dk.yepzdk.hapanel.REPORT
    ```

    ```yaml
    action: notify.mobile_app_<your_panel>
    data:
      message: command_broadcast_intent
      data:
        intent_package_name: dk.yepzdk.hapanel
        intent_action: dk.yepzdk.hapanel.UNLINK
    ```

11. **Factory reset** — wipes the panel back to the welcome screen. Run last:

   ```yaml
   action: notify.mobile_app_<your_panel>
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
| `WifiCleanup` | Once after setup: forgets every saved Wi-Fi network except the QR's (`wifi_ssid` admin extra) |
| `HomeActivity` | Persistent HOME trampoline — anything that lands on "home" re-pins the HA app (unpinned in maintenance mode); the first launch after provisioning opens the HA app's onboarding at the QR's `ha_url` admin extra, when set |
| `ScreenOffActivity` | Black overlay for `SCREEN_OFF`; the device stays awake so HA can wake it again |
| `PackageReplacedReceiver` | After a DPC update: re-applies policies and re-pins the dashboard |
| `PanelCommandReceiver` | HA-driven commands: `SCREEN_OFF`, `SCREEN_ON`, `SET_BRIGHTNESS`, `NAVIGATE`, `REBOOT`, `MAINTENANCE_ON`, `MAINTENANCE_OFF`, `UPDATE_HA`, `UPDATE_PANEL`, `FACTORY_RESET`, `LINK`, `UNLINK`, `REPORT` |
| `StatusReporter` | While linked (`LINK`): POSTs the panel's state as JSON to the status webhook |
| `StatusJobService` / `BootReceiver` | 15-minute heartbeat report, and the report after a restart once the network is up |
| `provisioning/make-qr.sh` | Renders the provisioning QR from your local config |

Development builds: `export JAVA_HOME=$(/usr/libexec/java_home -v 17+)` (or a
Homebrew JDK ≤25) before running `./gradlew`, since Gradle may not support the
newest installed JDK.
