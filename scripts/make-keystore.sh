#!/usr/bin/env bash
# Creates the sideloading keystore used by the release build (not committed: *.keystore is git-ignored).
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -f release.keystore ]; then echo "release.keystore already exists"; exit 0; fi
keytool -genkeypair -v -keystore release.keystore -storetype PKCS12 -alias myedits \
  -keyalg RSA -keysize 2048 -validity 10000 -storepass myedits123 -keypass myedits123 \
  -dname "CN=My Edits, OU=Dev, O=My Edits, L=Local, S=Local, C=US"
