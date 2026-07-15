#!/usr/bin/env bash
# Generate the device-owner provisioning QR code.
# Usage: ./make-qr.sh   (reads local.env, writes qr.png + payload.json here)
set -euo pipefail
cd "$(dirname "$0")"

[ -f local.env ] || { echo "Copy local.env.example to local.env and fill it in first."; exit 1; }
# shellcheck source=local.env.example
source local.env

command -v qrencode >/dev/null || { echo "qrencode missing: brew install qrencode"; exit 1; }

# URL-safe base64 (no padding) of the SHA-256 of the signing certificate.
CHECKSUM=$(keytool -exportcert -keystore "$KEYSTORE" -alias "$KEY_ALIAS" -rfc 2>/dev/null \
  | openssl x509 -outform DER \
  | openssl dgst -sha256 -binary \
  | openssl base64 \
  | tr '+/' '-_' | tr -d '=')
[ -n "$CHECKSUM" ] || { echo "Could not compute signature checksum from $KEYSTORE"; exit 1; }

sed -e "s|@DPC_APK_URL@|$DPC_APK_URL|" \
    -e "s|@SIGNATURE_CHECKSUM@|$CHECKSUM|" \
    -e "s|@WIFI_SSID@|$WIFI_SSID|" \
    -e "s|@WIFI_PASSWORD@|$WIFI_PASSWORD|" \
    -e "s|@HA_APK_URL@|$HA_APK_URL|" \
    payload.template.json > payload.json

qrencode -o qr.png -s 8 < payload.json
echo "Wrote qr.png — factory reset the device, tap the welcome screen 6 times, scan."
