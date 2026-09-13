#!/bin/bash
# Copies the three API jars this project compiles against out of a running
# server's plugins directory into libs/. They are never committed - they are other
# people's plugins and redistributing them is not ours to do.
#
# Usage: scripts/fetch-libs.sh /path/to/server/plugins

set -euo pipefail
PLUGINS="${1:-}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"

if [ -z "$PLUGINS" ] || [ ! -d "$PLUGINS" ]; then
  echo "Usage: $0 <path-to-server-plugins-dir>" >&2
  exit 2
fi

mkdir -p "$HERE/libs"

copy_one() {
  local pattern="$1" target="$2"
  local src
  src=$(find "$PLUGINS" -maxdepth 1 -name "$pattern" -type f | head -1)
  if [ -z "$src" ]; then
    echo "NOT FOUND: $pattern in $PLUGINS" >&2
    return 1
  fi
  cp "$src" "$HERE/libs/$target"
  echo "$target  <-  $(basename "$src")"
}

copy_one 'ProCosmetics*.jar' 'ProCosmetics.jar'
copy_one 'ExcellentEconomy*.jar' 'ExcellentEconomy.jar'
copy_one 'nightcore*.jar' 'nightcore.jar'

echo "Done. Now run: mvn -q clean package"
