#!/usr/bin/env bash
# Genereert de demo-artefacten voor de gekozen podman-modus. Alle output blijft in
# demo/generated/ (git-ignored).
#
#   demo/podman-prepare.sh bridge    — adressering op container-DNS (gelijk aan Docker)
#   demo/podman-prepare.sh hostnet   — adressering op 127.0.0.1, voor de gedeelde-netns-variant
set -euo pipefail

MODUS="${1:-bridge}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GEN="$ROOT/demo/generated"

case "$MODUS" in
    bridge|hostnet) ;;
    *) echo "Onbekende modus '$MODUS'; kies 'bridge' of 'hostnet'." >&2; exit 1 ;;
esac

python3 "$ROOT/demo/genereer-magazijnen.py"

if [ "$MODUS" = "bridge" ]; then
    echo "Klaar (bridge): container-DNS-namen ongewijzigd."
    exit 0
fi

REGISTER="$GEN/magazijnen-register.properties"
PROXIES="$GEN/proxies-host.json"

trap 'rm -f "$REGISTER.tmp" "$PROXIES.tmp"' EXIT

# In één gedeelde netns bestaan de container-DNS-namen niet; alles loopt over 127.0.0.1 met de
# poorten uit compose.podman-hostnet.yaml. Beide gaan eerst naar `.tmp`, zodat een falende guard
# de bestemming onaangeroerd laat; voor het register is dat bovendien nodig omdat `sed` niet uit
# zijn eigen invoerbestand kan lezen én erin schrijven.
# Het adres hieronder is de default van genereer-magazijnen.py. Wie dat script met een eigen
# SIMULATOR_URL draait (de ZAD-variant schrijft er een configuratie-expressie in), krijgt hier een
# sed die niets doet — daarom telt de guard verderop hoeveel regels hij herkende.
sed 's|http://magazijn-simulator:8092|http://127.0.0.1:8092|g' "$REGISTER" > "$REGISTER.tmp"

# `listen` gaat mee van 0.0.0.0 naar 127.0.0.1: in een gedeelde netns bindt een wildcard op élke
# interface van de machine, en achter deze proxies zitten Redis en de magazijn-API's. Bovendien
# botst een wildcard-bind met elke specifieke bind op dezelfde poort — bijvoorbeeld van een
# FSC-federatie die in dezelfde netns draait.
sed -e 's|"listen": "0\.0\.0\.0:|"listen": "127.0.0.1:|g' \
    -e 's|"berichtenmagazijn-a:8090"|"127.0.0.1:8090"|' \
    -e 's|"berichtenmagazijn-b:8090"|"127.0.0.1:8091"|' \
    -e 's|"redis:6379"|"127.0.0.1:6379"|' \
    -e 's|"profiel-service:8080"|"127.0.0.1:8089"|' \
    -e 's|"notificatie-stub:8080"|"127.0.0.1:8084"|' \
    -e 's|"berichtenuitvraag:8086"|"127.0.0.1:8086"|' \
    "$ROOT/toxiproxy/proxies.json" > "$PROXIES.tmp"

# De guards tellen éérst wat ze herkennen. Vinden ze niets — hernoemd veld, verminkte invoer, een
# formaat waarin het patroon niet meer matcht — dan is 'nul afwijkingen' niet te onderscheiden van
# 'alles goed' en zou de guard stil groen worden op werk dat nooit is gedaan. Bron en doel worden
# op dezelfde manier geteld, anders geeft compacte JSON (alles op één regel) een vals alarm.
tel() {
    grep -o "$1" "$2" | grep -c . || true
}

REGISTER_URLS="$(tel '^magazijnen\."[^"]*"\.url=[^ ]*' "$REGISTER.tmp")"

if [ "$REGISTER_URLS" -eq 0 ]; then
    echo "FOUT: geen enkele register-URL herkend in $REGISTER.tmp — guard kan niets borgen." >&2
    exit 1
fi

# Op de hele waarde toetsen, niet op een substring: een nieuwe container-DNS-naam uit de generator
# (of een naam waar 127.0.0.1 toevallig in voorkomt) zou anders ongemerkt blijven staan.
if grep -E '^magazijnen\."[^"]*"\.url=' "$REGISTER.tmp" |
   grep -vE '=http://127\.0\.0\.1:[0-9]+(/[^[:space:]]*)?$' >&2; then
    echo "FOUT: bovenstaande register-URL's wijzen niet naar 127.0.0.1; vul de sed-regel aan." >&2
    exit 1
fi

BRON_UPSTREAMS="$(tel '"upstream": *"[^"]*"' "$ROOT/toxiproxy/proxies.json")"
DOEL_UPSTREAMS="$(tel '"upstream": *"[^"]*"' "$PROXIES.tmp")"
DOEL_LISTENS="$(tel '"listen": *"[^"]*"' "$PROXIES.tmp")"

if [ "$BRON_UPSTREAMS" -eq 0 ] || [ "$DOEL_UPSTREAMS" -ne "$BRON_UPSTREAMS" ]; then
    echo "FOUT: $DOEL_UPSTREAMS van $BRON_UPSTREAMS upstreams herkend — guard kan niets borgen." >&2
    exit 1
fi

if [ "$DOEL_LISTENS" -ne "$BRON_UPSTREAMS" ]; then
    echo "FOUT: $DOEL_LISTENS listen-adressen bij $BRON_UPSTREAMS upstreams — proxies.json klopt niet." >&2
    exit 1
fi

if grep -o '"upstream": *"[^"]*"' "$PROXIES.tmp" |
   grep -vE '"upstream": *"127\.0\.0\.1:[0-9]+"' >&2; then
    echo "FOUT: bovenstaande toxiproxy-upstreams wijzen niet naar 127.0.0.1; vul de sed-regels aan." >&2
    exit 1
fi

# Een listen-adres op een containernaam laadt Toxiproxy niet (`Failed to populate proxies`), en dan
# staat er een gezonde proxy-loze Toxiproxy die elke naïeve probe groen laat.
if grep -o '"listen": *"[^"]*"' "$PROXIES.tmp" |
   grep -vE '"listen": *"127\.0\.0\.1:[0-9]+"' >&2; then
    echo "FOUT: bovenstaande toxiproxy-listen-adressen staan niet op 127.0.0.1." >&2
    exit 1
fi

# Met `cat` naar de bestemming, niet met `mv`: compose mount beide bestanden als lós bestand, en
# een vervangen inode laat een draaiende container naar het oude bestand blijven kijken. Prijs
# daarvan: dit schrijven is niet atomair, dus een crash middenin laat een half bestand achter.
cat "$REGISTER.tmp" > "$REGISTER"
cat "$PROXIES.tmp" > "$PROXIES"

echo "Klaar (hostnet): $REGISTER_URLS register-URL's en $DOEL_UPSTREAMS toxiproxy-upstreams op 127.0.0.1."
