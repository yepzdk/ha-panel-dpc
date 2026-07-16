#!/usr/bin/env bash
# Generate the device-owner provisioning QR code.
# Usage: ./make-qr.sh   (reads local.env, writes qr.png + payload.json here)
set -euo pipefail
cd "$(dirname "$0")"

[ -f local.env ] || { echo "Copy local.env.example to local.env and fill it in first."; exit 1; }
# shellcheck source=local.env.example
source local.env

command -v qrencode >/dev/null || { echo "qrencode missing: brew install qrencode"; exit 1; }

# Keystore password from the (gitignored) keystore.properties next to the keystore.
KEYSTORE_PW=$(grep '^storePassword=' "$(dirname "$KEYSTORE")/keystore.properties" | cut -d= -f2-)

# URL-safe base64 (no padding) of the SHA-256 of the signing certificate.
CHECKSUM=$(keytool -storepass "$KEYSTORE_PW" -exportcert -keystore "$KEYSTORE" -alias "$KEY_ALIAS" -rfc 2>/dev/null \
  | openssl x509 -outform DER \
  | openssl dgst -sha256 -binary \
  | openssl base64 \
  | tr '+/' '-_' | tr -d '=')
[ -n "$CHECKSUM" ] || { echo "Could not compute signature checksum from $KEYSTORE"; exit 1; }

# Values go through the environment and json.dumps — never string templating —
# so SSIDs and passwords may contain any characters.
export WIFI_SSID WIFI_PASSWORD DPC_APK_URL HA_APK_URL CHECKSUM
export WIFI_SECURITY="${WIFI_SECURITY:-WPA}"
python3 > payload.json <<'EOF'
import json, os
e = os.environ
print(json.dumps({
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": "dk.yepzdk.hapanel/.AdminReceiver",
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": e["DPC_APK_URL"],
    "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM": e["CHECKSUM"],
    "android.app.extra.PROVISIONING_WIFI_SSID": e["WIFI_SSID"],
    "android.app.extra.PROVISIONING_WIFI_PASSWORD": e["WIFI_PASSWORD"],
    "android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE": e["WIFI_SECURITY"],
    "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": False,
    "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE": {"ha_apk_url": e["HA_APK_URL"]},
}, indent=2))
EOF

qrencode -o qr.png -s 8 < payload.json
echo "Wrote qr.png — factory reset the device, tap the welcome screen 6 times, scan."
