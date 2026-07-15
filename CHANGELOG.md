# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Android project scaffold for the HA Panel DPC app (Kotlin, minSdk 28, no dependencies).
- QR-code device-owner provisioning (Android 12+ flow and 9–11 legacy path).
- Kiosk policies: lock task pinning of the HA Companion app, persistent HOME trampoline, keyguard/status bar disabled, stay-on-while-plugged.
- Silent download + install of the HA Companion app (minimal flavor) with an HA-triggerable update command.
- HA-driven panel commands via broadcast: screen off, brightness, app update.
- Provisioning tooling: QR payload template and `make-qr.sh`, plus signing config.
- Docs: README provisioning walkthrough and ready-to-paste HA automations.
