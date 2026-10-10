# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/yepzdk/ha-panel-dpc/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/yepzdk/ha-panel-dpc/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/yepzdk/ha-panel-dpc/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/yepzdk/ha-panel-dpc/releases/tag/v0.1.0
