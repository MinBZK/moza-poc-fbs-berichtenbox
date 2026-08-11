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

case "$MODUS" in
    bridge|hostnet) ;;
    *) echo "Onbekende modus '$MODUS'; kies 'bridge' of 'hostnet'." >&2; exit 1 ;;
esac

python3 "$WORTEL/demo/genereer-magazijnen.py"

if [ "$MODUS" = "bridge" ]; then
    echo "Klaar (bridge): container-DNS-namen ongewijzigd."
    exit 0
fi

# In één gedeelde netns bestaan de container-DNS-namen niet; alles loopt over 127.0.0.1 met de
# poorten uit compose.podman-hostnet.yaml.
REGISTER="$GEN/magazijnen-stubs.properties"

# Niet met `sed -i`: dat vervangt het inode, terwijl compose dit bestand als lós bestand mount.
# Een tweede run tegen een draaiende stack zou de container dan naar het verwijderde inode laten
# kijken en het register stil niet bijwerken.
sed 's|http://magazijn-stubs:8080|http://127.0.0.1:8092|g' "$REGISTER" > "$REGISTER.tmp"
cat "$REGISTER.tmp" > "$REGISTER"
rm -f "$REGISTER.tmp"

# Let op magazijn-b: die luistert in deze variant op 8091, niet op 8090 zoals bij bridge.
sed -e 's|"berichtenmagazijn-a:8090"|"127.0.0.1:8090"|' \
    -e 's|"berichtenmagazijn-b:8090"|"127.0.0.1:8091"|' \
    -e 's|"redis:6379"|"127.0.0.1:6379"|' \
    -e 's|"profiel-service:8080"|"127.0.0.1:8089"|' \
    -e 's|"notificatie-stub:8080"|"127.0.0.1:8084"|' \
    -e 's|"berichtenuitvraag:8086"|"127.0.0.1:8086"|' \
    "$WORTEL/toxiproxy/proxies.json" > "$GEN/proxies-host.json.tmp"

# Guards op de omgeschreven bestanden. Beide tellen éérst wat er te controleren viel: vindt een
# guard niets — verminkte invoer, hernoemd veld, een `jq`-herformattering die key en waarde op
# aparte regels zet — dan is 'nul afwijkingen' niet te onderscheiden van 'alles goed' en zou hij
# stil groen worden op werk dat nooit is gedaan.
REGEL_URLS="$(grep -cE '^magazijnen\."[^"]+"\.url=' "$REGISTER" || true)"

if [ "$REGEL_URLS" -eq 0 ]; then
    echo "FOUT: geen enkele register-URL gevonden in $REGISTER — guard kan niets borgen." >&2
    exit 1
fi

# Op de klasse controleren, niet op de ene string die de sed hierboven verving: een nieuwe
# container-DNS-naam uit de generator zou anders ongemerkt in het register blijven staan.
if grep -E '^magazijnen\."[^"]+"\.url=' "$REGISTER" | grep -v '=http://127\.0\.0\.1:' >&2; then
    echo "FOUT: bovenstaande register-URL's wijzen niet naar 127.0.0.1; vul de sed-regel aan." >&2
    exit 1
fi

BRON_UPSTREAMS="$(grep -c '"upstream"' "$WORTEL/toxiproxy/proxies.json" || true)"

# Tellen wat de guard hieronder daadwerkelijk hérkent, niet hoe vaak het woord voorkomt: zet
# iemand `proxies.json` met `jq .` om, dan staan sleutel en waarde op aparte regels, matcht het
# patroon nergens meer en zou de guard nul afwijkingen zien op nul gecontroleerde upstreams.
DOEL_UPSTREAMS="$(grep -o '"upstream": *"[^"]*"' "$GEN/proxies-host.json.tmp" | grep -c . || true)"

if [ "$BRON_UPSTREAMS" -eq 0 ] || [ "$DOEL_UPSTREAMS" -ne "$BRON_UPSTREAMS" ]; then
    echo "FOUT: $DOEL_UPSTREAMS van $BRON_UPSTREAMS upstreams herkend — guard kan niets borgen." >&2
    exit 1
fi

if grep -o '"upstream": *"[^"]*"' "$GEN/proxies-host.json.tmp" | grep -v '127\.0\.0\.1' >&2; then
    echo "FOUT: bovenstaande toxiproxy-upstreams wijzen niet naar 127.0.0.1; vul de sed-regels aan." >&2
    exit 1
fi

# Pas hernoemen als de guards slagen: een half geschreven bestand zou anders door een handmatige
# compose-start gewoon gemount worden.
mv "$GEN/proxies-host.json.tmp" "$GEN/proxies-host.json"

echo "Klaar (hostnet): $REGEL_URLS register-URL's en $DOEL_UPSTREAMS toxiproxy-upstreams op 127.0.0.1."
