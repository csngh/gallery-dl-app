#!/bin/bash
# Build phase: copy the app's Python code and the pip packages into the app's Resources.
# Python itself is the embedded Python.framework (Xcode embeds and signs it).
set -euo pipefail

APP="$CODESIGNING_FOLDER_PATH"
RES="$APP/Contents/Resources"
REPO_ROOT="$PROJECT_DIR/.."

mkdir -p "$RES/app"
rsync -au --delete "$REPO_ROOT/ios/python/" "$RES/app/"
# One source of truth: the Android app's bridge runs unchanged on iPhone and Mac.
cp "$REPO_ROOT/app/src/main/python/gdl_bridge.py" "$RES/app/gdl_bridge.py"
cp "$REPO_ROOT/app/src/main/python/gdl_plugins.py" "$RES/app/gdl_plugins.py"
rsync -au --delete "$PROJECT_DIR/app_packages/" "$RES/app_packages/"
