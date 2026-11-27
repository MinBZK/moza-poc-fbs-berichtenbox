#!/usr/bin/env bash
# Faalt als Quinoa in deze job toch zelf Node heeft gedownload. Dat gebeurt stil zodra de stap
# node-beheerconsole ontbreekt, is overgeslagen of na de build staat: Quinoa installeert Node dan in
# `<module>/.quinoa/`, zonder controle. Deze uitkomst hangt niet aan namen of volgorde in de workflow.
#
# Gebruik: quinoa-zonder-download.sh [repo-root]   (standaard: de huidige map)

set -euo pipefail

root=${1:-.}
gevonden=$(find "$root/services" "$root/libraries" "$root/demo" -maxdepth 2 -type d -name .quinoa 2>/dev/null || true)

if [ -n "$gevonden" ]; then
  echo "::error::Quinoa heeft Node zelf gedownload, zonder checksum ($(tr '\n' ' ' <<<"$gevonden")). De stap node-beheerconsole ontbreekt of is overgeslagen."
  exit 1
fi

echo "Geen eigen Node-download van Quinoa."
