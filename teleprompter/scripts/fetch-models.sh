#!/usr/bin/env bash
# Downloads the Vosk small English and Spanish models into the app's assets.
# Vosk's StorageService.unpack needs a "uuid" file in each model folder; it
# re-copies the model onto the phone whenever that value changes.
set -euo pipefail

cd "$(dirname "$0")/.."
ASSETS=app/src/main/assets
mkdir -p "$ASSETS"

fetch() {
  local name=$1 target=$2
  if [ -f "$ASSETS/$target/uuid" ] && [ "$(cat "$ASSETS/$target/uuid")" = "$name" ]; then
    echo "$name already present"
    return
  fi
  local tmp
  tmp=$(mktemp -d)
  curl -fL --retry 3 -o "$tmp/$name.zip" "https://alphacephei.com/vosk/models/$name.zip"
  unzip -q "$tmp/$name.zip" -d "$tmp"
  rm -rf "${ASSETS:?}/$target"
  mv "$tmp/$name" "$ASSETS/$target"
  echo -n "$name" > "$ASSETS/$target/uuid"
  rm -rf "$tmp"
  echo "$name -> $ASSETS/$target"
}

fetch vosk-model-small-en-us-0.15 model-en
fetch vosk-model-small-es-0.42 model-es
