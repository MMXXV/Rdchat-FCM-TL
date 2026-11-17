#!/usr/bin/env sh
set -eu
VERSION="9.4.1"
EXPECTED_SHA256="2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb"
GRADLE_HOME_BASE="${GRADLE_USER_HOME:-$HOME/.gradle}"
CACHE_DIR="$GRADLE_HOME_BASE/rdchat-bootstrap"
INSTALL_DIR="$CACHE_DIR/gradle-$VERSION"
ARCHIVE="$CACHE_DIR/gradle-$VERSION-bin.zip"
if [ ! -x "$INSTALL_DIR/bin/gradle" ]; then
  mkdir -p "$CACHE_DIR"
  if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 3 "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip" -o "$ARCHIVE"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$ARCHIVE" "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip"
  else
    echo "Instale curl ou wget para baixar Gradle $VERSION." >&2; exit 1
  fi
  if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL_SHA256="$(sha256sum "$ARCHIVE" | awk '{print $1}')"
  elif command -v shasum >/dev/null 2>&1; then
    ACTUAL_SHA256="$(shasum -a 256 "$ARCHIVE" | awk '{print $1}')"
  else
    echo "Instale sha256sum ou shasum para validar o arquivo baixado." >&2
    rm -f "$ARCHIVE"; exit 1
  fi
  if [ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]; then
    echo "Checksum SHA-256 do Gradle inválido." >&2; rm -f "$ARCHIVE"; exit 1
  fi
  if ! command -v unzip >/dev/null 2>&1; then
    echo "Instale unzip para extrair Gradle $VERSION." >&2; rm -f "$ARCHIVE"; exit 1
  fi
  unzip -q "$ARCHIVE" -d "$CACHE_DIR"
  rm -f "$ARCHIVE"
fi
exec "$INSTALL_DIR/bin/gradle" "$@"
