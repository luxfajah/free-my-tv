#!/usr/bin/env bash
# Build and publish an OTA release of PacocaOsTV to GitHub Releases (luxfajah/free-my-tv).
# Usage: ./release_ota.sh v1.4.0 "Control Center Apple TV, Perfil de Imagem, AirPlay e Enviar Link"
set -euo pipefail

TAG="${1:-}"
NOTES="${2:-Atualização OTA do Paçoca OsTV}"

if [ -z "$TAG" ]; then
  echo "Uso: ./release_ota.sh <tag> [notas]"
  echo "Exemplo: ./release_ota.sh v1.4.0 \"Control Center Apple TV + Enviar Link\""
  exit 1
fi

cd "$(dirname "$0")"
./build.sh
cp PacocaOsTV.apk /Volumes/Work/PROJETOR/apks/PacocaOsTV.apk
cp PacocaOsTV.apk "APK PERFEITO/PacocaOsTV.apk"

gh release create "$TAG" PacocaOsTV.apk FreeMyTV.apk \
  --repo luxfajah/free-my-tv \
  --title "Paçoca OsTV $TAG" \
  --notes "$NOTES"

echo "OTA publicado! No projetor, vá em Configurações > Home Settings > Atualizações."
