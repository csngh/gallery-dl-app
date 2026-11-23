#!/bin/bash
# Build a Release Mac app and zip it. The app is signed to run on this Mac (ad hoc); on another Mac, Control-click it and
# choose Open the first time, or give DEVELOPMENT_TEAM / CODE_SIGN_IDENTITY (Developer ID) to sign it properly.
set -euo pipefail
cd "$(dirname "$0")/.."

DERIVED="${TMPDIR:-/tmp}/gallerydl-mac-build"
rm -rf "$DERIVED" dist
xcodebuild -project GalleryDLMac.xcodeproj -scheme GalleryDLMac -configuration Release \
    -destination 'generic/platform=macOS' -derivedDataPath "$DERIVED" "$@" build | tail -1

mkdir -p dist
cp -R "$DERIVED/Build/Products/Release/Gallery DL.app" dist/
codesign --verify --deep --strict "dist/Gallery DL.app"
# ditto keeps the app's signature and symbolic links intact, which a plain zip does not.
ditto -c -k --keepParent "dist/Gallery DL.app" dist/GalleryDL-App-Mac.zip
rm -rf "dist/Gallery DL.app"
echo "Wrote macos/dist/GalleryDL-App-Mac.zip ($(du -h dist/GalleryDL-App-Mac.zip | cut -f1))"
