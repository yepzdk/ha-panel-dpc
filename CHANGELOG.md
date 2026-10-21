# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Panels report their state (versions, screen, brightness, maintenance mode) to the Smart Panel integration once it links them, on every change and every 15 minutes.
- An optional `HA_URL` in the provisioning QR opens the Home Assistant app's login at that server on first start, so only the username and password need typing.

### Changed

- Plain `http://` connections are allowed, so panels can report to a Home Assistant on the local network without HTTPS. This also allows `http://` download URLs.

## [0.5.0] - 2026-10-21

### Added

- `NAVIGATE` opens a page on the panel and loads it fresh, so HA can reload a dashboard or reach pages the dashboard hides, such as the profile page to log out.
- `REBOOT` restarts the panel.
- `MAINTENANCE_ON` unpins HA and enables the status bar so Android settings are reachable; `MAINTENANCE_OFF` or a restart locks the panel again.

## [0.4.2] - 2026-10-11

### Added

- The panel shows a short message when an update starts, and the reason when an update of the DPC or the HA app fails; the panel has no status bar to show progress.

## [0.4.1] - 2026-10-11

### Fixed

- `SCREEN_ON` did not remove the screen-off overlay; the panel stayed black until tapped.

## [0.4.0] - 2026-10-11

### Added

- `UPDATE_PANEL` updates the DPC over the air from its latest release, or from a given URL to try a build first, so a panel no longer has to be re-provisioned for each new version.
- `SCREEN_ON` wakes a blanked panel, and `SET_BRIGHTNESS` accepts `auto` to hand brightness back to the light sensor.

### Changed

- `SCREEN_OFF` covers the dashboard with a black overlay instead of putting the device to sleep. Sleep cut the Companion app's connection, so HA could not wake the panel again. A tap also wakes it.
- The README's delivery check uses a brightness change: `command_webview` does nothing on a panel, as the HA app lacks the overlay permission it needs.

## [0.3.0] - 2026-10-10

### Added

- README section with ready-to-run Developer tools actions for testing each panel command.

### Fixed

- QR-provisioned panels booted back to the welcome screen after a restart: the HA app was pinned on top of the unfinished setup wizard. The panel now waits until setup is complete.
- Plugged-in panels dimmed after the screen-off timeout despite stay-on; the timeout is now maxed out so the screen stays bright until HA turns it off.

## [0.2.0] - 2026-07-17

### Added

- `FACTORY_RESET` broadcast command (guarded by a `confirm:wipe` extra) so a panel can be wiped for re-provisioning straight from HA — no recovery-mode key combos.

### Fixed

- QR payload generation corrupted Wi-Fi credentials containing shell/sed special characters; values are now JSON-encoded properly. `WIFI_SECURITY` is also configurable.

## [0.1.0] - 2026-07-15

### Added

- Android project scaffold for the HA Panel DPC app (Kotlin, minSdk 28, no dependencies).
- QR-code device-owner provisioning (Android 12+ flow and 9–11 legacy path).
- Kiosk policies: lock task pinning of the HA Companion app, persistent HOME trampoline, keyguard/status bar disabled, stay-on-while-plugged.
- Silent download + install of the HA Companion app (minimal flavor) with an HA-triggerable update command.
- HA-driven panel commands via broadcast: screen off, brightness, app update.
- Provisioning tooling: QR payload template and `make-qr.sh`, plus signing config.
- Docs: README provisioning walkthrough and ready-to-paste HA automations.

[Unreleased]: https://github.com/yepzdk/ha-panel-dpc/compare/v0.5.0...HEAD
[0.5.0]: https://github.com/yepzdk/ha-panel-dpc/compare/v0.4.2...v0.5.0
[0.4.2]: https://github.com/yepzdk/ha-panel-dpc/compare/v0.4.1...v0.4.2
[0.4.1]: https://github.com/yepzdk/ha-panel-dpc/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/yepzdk/ha-panel-dpc/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/yepzdk/ha-panel-dpc/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/yepzdk/ha-panel-dpc/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/yepzdk/ha-panel-dpc/releases/tag/v0.1.0
