#!/usr/bin/env bash
# Builds a universal Slimmer.app and packages it into build/Slimmer.dmg
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="${VERSION:-1.0.0}"
BUILD="${BUILD_NUMBER:-1}"

swift build -c release --arch arm64 --arch x86_64
BIN=".build/apple/Products/Release/Slimmer"

rm -rf build
APP="build/Slimmer.app"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/Slimmer"
sed -e "s/__VERSION__/$VERSION/" -e "s/__BUILD__/$BUILD/" Resources/Info.plist > "$APP/Contents/Info.plist"
if [ -f Resources/AppIcon.icns ]; then
  cp Resources/AppIcon.icns "$APP/Contents/Resources/"
  /usr/libexec/PlistBuddy -c "Add :CFBundleIconFile string AppIcon" "$APP/Contents/Info.plist"
fi

# Ad-hoc signature pinned to the bundle identifier so notification / local network
# permissions survive updates. Set SIGN_IDENTITY to use a real certificate.
if [ -n "${SIGN_IDENTITY:-}" ]; then
  codesign --force --deep --options runtime --sign "$SIGN_IDENTITY" "$APP"
else
  codesign --force --deep --sign - \
    --requirements '=designated => identifier "dev.seon.slimmer"' "$APP"
fi
codesign --verify --verbose "$APP"

mkdir -p build/dmg
cp -R "$APP" build/dmg/
ln -s /Applications build/dmg/Applications
hdiutil create -volname "Slimmer" -srcfolder build/dmg -ov -format UDZO build/Slimmer.dmg
echo "Built build/Slimmer.dmg"
