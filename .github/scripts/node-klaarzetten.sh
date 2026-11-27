#!/usr/bin/env bash
# Zet Node voor de build van de beheerconsole klaar, met een gecontroleerde checksum, en laat Quinoa
# die gebruiken in plaats van zelf te downloaden. Quinoa haalt Node anders van nodejs.org zonder enige
# controle, in de job die het image naar ghcr pusht.
#
# De versie staat op één plek: `quarkus.quinoa.package-manager-install.node-version` in de
# application.properties van de console. De checksum staat in het pinbestand ernaast, en noemt het
# bestand met die versie erin. Wie de versie ophoogt zonder de pin, krijgt hier een fout in plaats
# van een ongecontroleerde download.
#
# Gebruik: node-klaarzetten.sh <application.properties> <pinbestand> <doelmap>
# Schrijft naar $GITHUB_PATH (de bin-map) en $GITHUB_ENV (QUARKUS_QUINOA_PACKAGE_MANAGER_INSTALL=false).

set -euo pipefail

readonly BASIS_URL=https://nodejs.org/dist

properties=${1:?application.properties ontbreekt}
pinbestand=${2:?pinbestand ontbreekt}
doelmap=${3:?doelmap ontbreekt}

versie=$(sed -n 's/^quarkus\.quinoa\.package-manager-install\.node-version=//p' "$properties")

if ! [[ "$versie" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "::error::Geen geldige Node-versie in $properties (gevonden: '${versie}')."
  exit 1
fi

bestand="node-v$versie-linux-x64.tar.xz"
pin=$(grep -v '^#' "$pinbestand" | grep -E '^[0-9a-f]{64}  ' || true)

if [ "$(wc -l <<<"$pin")" -ne 1 ] || [ "${pin#*  }" != "$bestand" ]; then
  echo "::error::$pinbestand hoort precies één checksum voor $bestand te bevatten; de pin en de versie in $properties lopen uiteen."
  exit 1
fi

werkmap=$(mktemp -d)
trap 'rm -rf "$werkmap"' EXIT

curl --proto '=https' --tlsv1.2 -fsSL --retry 3 -o "$werkmap/$bestand" "$BASIS_URL/v$versie/$bestand"

if ! (cd "$werkmap" && sha256sum --check --strict --quiet <<<"$pin"); then
  echo "::error::Checksum van $bestand klopt niet met $pinbestand; Node wordt niet gebruikt."
  exit 1
fi

mkdir -p "$doelmap"
tar -xJf "$werkmap/$bestand" -C "$doelmap"

bin="$doelmap/node-v$versie-linux-x64/bin"

if ! [ -x "$bin/node" ]; then
  echo "::error::$bestand bevat geen bin/node."
  exit 1
fi

echo "$bin" >>"$GITHUB_PATH"
echo "QUARKUS_QUINOA_PACKAGE_MANAGER_INSTALL=false" >>"$GITHUB_ENV"
echo "Node $versie klaargezet in $bin (checksum gecontroleerd)."
