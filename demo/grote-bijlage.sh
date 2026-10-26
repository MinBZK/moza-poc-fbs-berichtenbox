#!/usr/bin/env bash
# Levert één bericht met een bijlage van een gekozen grootte aan bij het magazijn, en haalt die
# bijlage via de uitvraag terug, byte voor byte vergeleken met wat er aangeleverd is.
#
# Een bijlage kleiner dan 1 MiB gaat in één keer de deur uit; vanaf 1 MiB streamt de uitvraag hem.
# Rond die grens zit het interessante gedrag: probeer 1048575, 1M en 25M (de bovengrens van het
# magazijn). Daarboven hoort het magazijn de aanlevering al met een 400 te weigeren.
#
# Vereist een draaiend magazijn en een uitvraag die dat magazijn kent onder AFZENDER:
#   docker compose up -d
#   ./mvnw compile quarkus:dev -pl services/berichtenmagazijn -am    # :8090
#   ./mvnw compile quarkus:dev -pl services/berichtenuitvraag -am    # :8086
#
# Gebruik:  demo/grote-bijlage.sh [grootte]
#           grootte in bytes, of met K of M voor KiB of MiB; standaard 5M
#
# Aan het eind staat het adres van de bijlage. Daarmee is de rest met de hand na te gaan: HEAD, een
# trage afnemer (curl --limit-rate), of meerdere downloads tegelijk voor het plafond. Het script zelf
# kan dat laatste niet: twee runs tegelijk botsen op de ophaalronde, die per ontvanger exclusief is.
set -euo pipefail

MAGAZIJN="${MAGAZIJN:-http://localhost:8090}"
UITVRAAG="${UITVRAAG:-http://localhost:8086}"
AFZENDER="${AFZENDER:-00000000000000100000}"
ONTVANGER="${ONTVANGER:-BSN:999993653}"
GROOTTE="${1:-5M}"

if [[ ! "$GROOTTE" =~ ^([0-9]+)([KM]?)$ ]]; then
    echo "Gebruik: $0 [grootte]   (bytes, of met K of M voor KiB of MiB; bijv. 1048575, 1M, 25M)" >&2
    exit 2
fi

# `10#`: zonder dat leest de shell een getal met een voorloopnul als octaal.
case "${BASH_REMATCH[2]}" in
    K) BYTES=$((10#${BASH_REMATCH[1]} * 1024)) ;;
    M) BYTES=$((10#${BASH_REMATCH[1]} * 1024 * 1024)) ;;
    *) BYTES=$((10#${BASH_REMATCH[1]})) ;;
esac

for dienst in "$MAGAZIJN" "$UITVRAAG"; do
    curl -sf "$dienst/q/health/ready" > /dev/null \
        || { echo "FOUT: $dienst is niet gezond; draait hij?" >&2; exit 1; }
done

# Bij een fout blijft de werkmap staan: dan zijn de aangeleverde en de teruggekregen bytes nog naast
# elkaar te leggen.
WERK="$(mktemp -d -t grote-bijlage-XXXXXX)"
BEWAREN=0
trap '[ "$BEWAREN" = 1 ] || rm -rf "$WERK"' EXIT

faal() {
    BEWAREN=1
    echo "FOUT: $1" >&2
    echo "De bestanden staan in $WERK" >&2
    exit 1
}

# Het magazijn neemt alleen PDF aan. Een echte PDF in plaats van willekeurige bytes met dat label,
# zodat de bijlage ook in de berichtenbox opent. De grootte komt uit een ongebruikt stream-object
# met willekeurige bytes; nullen zou een comprimerende proxy onderweg tot niets terugbrengen.
python3 -I - "$WERK/bijlage.pdf" "$BYTES" <<'PY' || faal "de PDF van $BYTES bytes maken mislukte"
import os
import sys

bestand, doel = sys.argv[1], int(sys.argv[2])

tekst = b"BT /F1 24 Tf 72 760 Td (Bijlage van %d bytes) Tj ET" % doel
VAST = [
    [b"<< /Type /Catalog /Pages 2 0 R >>"],
    [b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>"],
    [b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>"],
    [b"<< /Length %d >>\nstream\n" % len(tekst), tekst, b"\nendstream"],
    [b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"],
]


def pdf(opvulling, witruimte):
    """De PDF als delen, met een int op de plek van zoveel willekeurige bytes; plus de totale lengte."""
    delen, posities, positie = [], [], 0

    def voeg_toe(*nieuw):
        nonlocal positie

        for deel in nieuw:
            delen.append(deel)
            positie += deel if isinstance(deel, int) else len(deel)

    voeg_toe(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    objecten = [*VAST, [b"<< /Length %d >>\nstream\n" % opvulling, opvulling, b"\nendstream"]]

    for nummer, inhoud in enumerate(objecten, start=1):
        posities.append(positie)
        voeg_toe(b"%d 0 obj\n" % nummer, *inhoud, b"\nendobj\n")

    # Witruimte vóór `xref` vangt het verschil van een byte op wanneer een lengte een cijfer verspringt.
    voeg_toe(b" " * witruimte)
    xref = positie
    voeg_toe(b"xref\n0 %d\n0000000000 65535 f \n" % (len(objecten) + 1))
    voeg_toe(*(b"%010d 00000 n \n" % p for p in posities))
    voeg_toe(b"trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objecten) + 1, xref))
    return delen, positie


schatting = doel - (pdf(doel, 0)[1] - doel)
passend = next(
    (
        delen
        for opvulling in range(max(0, schatting - 10), max(0, schatting + 10))
        for witruimte in range(20)
        for delen, lengte in [pdf(opvulling, witruimte)]
        if lengte == doel
    ),
    None,
)

if passend is None:
    sys.exit(f"{doel} bytes is te klein voor een geldige PDF; kies minstens 1K")

with open(bestand, "wb") as uit:
    for deel in passend:
        uit.write(os.urandom(deel) if isinstance(deel, int) else deel)
PY

# Base64 van 25 MiB is ruim 33 MiB: te groot voor een argument op de commandline, dus via een
# bestand. `tr` in plaats van `base64 -w0`, want die optie kent de macOS-variant niet.
{
    printf '{"afzender":"%s","ontvanger":{"type":"%s","waarde":"%s"},' \
        "$AFZENDER" "${ONTVANGER%%:*}" "${ONTVANGER#*:}"
    printf '"onderwerp":"Bijlage van %s bytes","inhoud":"Bericht met een grote bijlage.",' "$BYTES"
    printf '"bijlagen":[{"naam":"bijlage-%s.pdf","mimeType":"application/pdf","inhoud":"' "$BYTES"
    base64 < "$WERK/bijlage.pdf" | tr -d '\n'
    printf '"}]}'
} > "$WERK/aanlevering.json"

STATUS="$(curl -sS -o "$WERK/aanlevering-antwoord.json" -w '%{http_code}' \
    -H 'Content-Type: application/json' --data-binary @"$WERK/aanlevering.json" \
    "$MAGAZIJN/api/v1/aanleveringen")" \
    || faal "aanleveren brak af (curl stopte met code $?)"

[ "$STATUS" = 201 ] || faal "aanleveren gaf $STATUS: $(head -c 2000 "$WERK/aanlevering-antwoord.json")"

BERICHT="$(python3 -I -c 'import json, sys; print(json.load(open(sys.argv[1]))["berichtId"])' \
    "$WERK/aanlevering-antwoord.json")" \
    || faal "geen berichtId in het antwoord van het magazijn"
echo "Aangeleverd: bericht $BERICHT met een bijlage van $BYTES bytes"

# De uitvraag geeft een bijlage alleen vrij voor een bericht dat in de sessie van de ontvanger staat.
# Een ophaalronde bevraagt elk magazijn opnieuw, dus daarna kent de sessie het nieuwe bericht.
STATUS="$(curl -sS -N --max-time 180 -o "$WERK/ophalen.sse" -w '%{http_code}' \
    -H "X-Ontvanger: $ONTVANGER" -H 'Accept: text/event-stream' \
    "$UITVRAAG/api/v1/berichten/_ophalen")" \
    || faal "ophalen brak af (curl stopte met code $?)"

[ "$STATUS" = 200 ] || faal "ophalen gaf $STATUS: $(head -c 2000 "$WERK/ophalen.sse")"

# Zonder deze controle eindigt een mislukte bevraging van het magazijn later als een kale 404 op de
# download, en dan lijkt het of de uitvraag de bijlage kwijt is.
python3 -I - "$WERK/ophalen.sse" "$AFZENDER" <<'PY' || faal "de ophaalronde zette het bericht niet in de sessie"
import json
import sys

bestand, magazijn = sys.argv[1:3]
events = [
    json.loads(regel[len("data:"):])
    for regel in open(bestand, encoding="utf-8")
    if regel.startswith("data:")
]

if not events or events[-1].get("event") != "ophalen-gereed":
    laatste = events[-1].get("event") if events else "geen enkel event"
    sys.exit(f"de ophaalronde eindigde niet met 'ophalen-gereed' maar met '{laatste}'")

voltooid = [e for e in events if e.get("event") == "magazijn-bevraging-voltooid" and e.get("magazijnId") == magazijn]

if not voltooid or voltooid[-1].get("status") != "OK":
    status = voltooid[-1].get("status") if voltooid else "niet bevraagd"
    sys.exit(f"magazijn {magazijn} in de ophaalronde: {status}")
PY

STATUS="$(curl -sS -o "$WERK/bericht.json" -w '%{http_code}' -H "X-Ontvanger: $ONTVANGER" \
    "$UITVRAAG/api/v1/berichten/$BERICHT?magazijnId=$AFZENDER")" \
    || faal "het bericht ophalen brak af (curl stopte met code $?)"

[ "$STATUS" = 200 ] || faal "het bericht ophalen gaf $STATUS: $(head -c 2000 "$WERK/bericht.json")"

# Het adres van de bijlage komt uit `_links`, zoals bij elke afnemer; zelf samenstellen zou een fout
# in die link verbergen.
URL="$(python3 -I -c '
import json, sys
from urllib.parse import urljoin
bericht = json.load(open(sys.argv[2]))
print(urljoin(sys.argv[1] + "/", bericht["bijlagen"][0]["_links"]["self"]["href"]))
' "$UITVRAAG" "$WERK/bericht.json")" \
    || faal "geen bijlage-link in het bericht: $(head -c 2000 "$WERK/bericht.json")"

# Bij een gestreamde bijlage ligt de 200 al vast voordat de bytes komen. Breekt de stream daarna af,
# dan is dat alleen aan de exitcode van curl te zien.
UIT="$(curl -sS -D "$WERK/headers.txt" -o "$WERK/terug.pdf" \
    -w '%{http_code} %{size_download} %{time_total}' \
    -H "X-Ontvanger: $ONTVANGER" "$URL")" \
    || faal "de download brak af (curl stopte met code $?); ontvangen: $(cat "$WERK/terug.pdf" 2>/dev/null | wc -c | tr -d ' ') van $BYTES bytes"
read -r CODE ONTVANGEN DUUR <<< "$UIT"

[ "$CODE" = 200 ] || faal "downloaden gaf $CODE: $(head -c 2000 "$WERK/terug.pdf")"

grep -i '^\(content-type\|content-length\|transfer-encoding\):' "$WERK/headers.txt" | tr -d '\r' | sed 's/^/  /' || true

cmp -s "$WERK/bijlage.pdf" "$WERK/terug.pdf" \
    || faal "de teruggekregen bijlage ($ONTVANGEN bytes) wijkt af van de aangeleverde ($BYTES bytes)"

echo "OK: $ONTVANGEN bytes byte-gelijk terug in ${DUUR}s"
echo
echo "Bijlage-adres, voor verdere proeven:"
echo "  $URL"
echo "  -H 'X-Ontvanger: $ONTVANGER'"
