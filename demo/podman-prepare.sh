#!/usr/bin/env bash
# Genereert de demo-artefacten voor de gekozen podman-modus. Alle output blijft in
# demo/generated/ (git-ignored).
#
#   demo/podman-prepare.sh bridge    — adressering op container-DNS (gelijk aan Docker)
#   demo/podman-prepare.sh hostnet   — adressering op 127.0.0.1, voor de gedeelde-netns-variant
set -euo pipefail

MODUS="${1:-bridge}"
WORTEL="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GEN="$WORTEL/demo/generated"

python3 "$WORTEL/demo/genereer-magazijnen.py"

if [ "$MODUS" = "bridge" ]; then
    echo "Klaar (bridge): container-DNS-namen ongewijzigd."
    exit 0
fi

if [ "$MODUS" != "hostnet" ]; then
    echo "Onbekende modus '$MODUS'; kies 'bridge' of 'hostnet'." >&2
    exit 1
fi

# In één gedeelde netns bestaan de container-DNS-namen niet; alles loopt over 127.0.0.1 met de
# poorten uit compose.podman-hostnet.yaml.
sed -i 's|http://magazijn-stubs:8080|http://127.0.0.1:8092|g' "$GEN/magazijnen-stubs.properties"

# Let op magazijn-b: die luistert in deze variant op 8091, niet op 8090 zoals bij bridge.
sed -e 's|"berichtenmagazijn-a:8090"|"127.0.0.1:8090"|' \
    -e 's|"berichtenmagazijn-b:8090"|"127.0.0.1:8091"|' \
    -e 's|"redis:6379"|"127.0.0.1:6379"|' \
    -e 's|"profiel-service:8080"|"127.0.0.1:8089"|' \
    -e 's|"notificatie-stub:8080"|"127.0.0.1:8084"|' \
    -e 's|"berichtenuitvraag:8086"|"127.0.0.1:8086"|' \
    "$WORTEL/toxiproxy/proxies.json" > "$GEN/proxies-host.json"

echo "Klaar (hostnet): register en toxiproxy-upstreams omgeschreven naar 127.0.0.1."
