#!/usr/bin/env bash
# Fixture-tests voor demo/environment/zad-demo/beheertoegang.sh: het oordeel per verwachting, de
# tabelvalidatie en het filter.
#
# Het script is een controle, en een controle die niet meer kan falen is erger dan geen controle: hij
# staat groen terwijl een beheer-UI open op het internet staat. Precies die kant is met de hand niet
# te draaien — daarvoor zou de muur op ZAD echt weggehaald moeten worden. De fixtures zetten de
# antwoorden die het script van buiten hoort te krijgen, zodat elk oordeel afdwingbaar is.
#
# Er draait geen netwerk: een curl-stub vooraan op PATH beantwoordt elke URL uit een env-var.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$(cd "${HERE}/../zad-demo" && pwd)/beheertoegang.sh"

fails=0

ok() {
    echo "OK: $1"
}

fout() {
    echo "FAIL: $1" >&2
    fails=$((fails + 1))
}

WERKMAP="$(mktemp -d)"
trap 'rm -rf "$WERKMAP"' EXIT

STUBMAP="${WERKMAP}/stub"
mkdir -p "$STUBMAP"

# De curl-stub leest dezelfde vlaggen als het script ze zet: `-o <bestand>` en de URL als laatste
# argument. Een URL die niet in de tabel staat levert bewust een onherkenbaar antwoord op, zodat een
# vergeten fixture opvalt als een falende test in plaats van als een toevallig goed oordeel.
cat > "${STUBMAP}/curl" <<'STUBCURL'
#!/usr/bin/env bash
set -euo pipefail

uitvoerbestand=""
url=""

while [ "$#" -gt 0 ]; do
    case "$1" in
        -o) uitvoerbestand="$2"; shift 2 ;;
        -m|-w) shift 2 ;;
        -*) shift ;;
        *) url="$1"; shift ;;
    esac
done

regel="$(printf '%s\n' "${STUB_ANTWOORDEN}" | grep -F "${url}|" | head -n 1 || true)"

if [ -z "$regel" ]; then
    [ -z "$uitvoerbestand" ] || printf 'FIXTURE ONTBREEKT voor %s\n' "$url" > "$uitvoerbestand"
    printf '000'
    exit 7
fi

IFS='|' read -r _ code status body <<<"$regel"

[ -z "$uitvoerbestand" ] || printf '%s\n' "$body" > "$uitvoerbestand"

printf '%s' "$code"
exit "$status"
STUBCURL
chmod +x "${STUBMAP}/curl"

BASIS=voorbeeld.test

ROUTER_404="Error - Applicatie niet gevonden"

# De volledige tabel van het script, met per rij het antwoord dat het oordeel OK oplevert. Elke test
# hieronder begint hiermee en vervangt één regel; zo toetst elk geval precies één verschil.
goede_antwoorden() {
    local host

    for host in logius-fscctl-fsc-logius-mpfb-8wh magazijna-fscctl-fsc-magazijna-mpfm-w3h; do
        printf 'https://%s.%s/|403|0|<html>inlogpagina</html>\n' "$host" "$BASIS"
        printf 'https://%s.%s/oauth2/auth|401|0|Unauthorized\n' "$host" "$BASIS"
    done

    for host in logius-fscmgr-fsc-logius-mpfb-8wh logius-fscinway-fsc-logius-mpfb-8wh \
                magazijna-fscmgr-fsc-magazijna-mpfm-w3h magazijna-fscinway-fsc-magazijna-mpfm-w3h \
                dirmgr-test-mft-tp9; do
        printf 'https://%s.%s/|000|56|\n' "$host" "$BASIS"
    done

    for host in logius-fscoutway-fsc-logius-mpfb-8wh logius-fsctxlog-fsc-logius-mpfb-8wh \
                logius-fscpg-fsc-logius-mpfb-8wh logius-fscbootstrap-fsc-logius-mpfb-8wh \
                magazijna-fsctxlog-fsc-magazijna-mpfm-w3h magazijna-fscpg-fsc-magazijna-mpfm-w3h \
                magazijna-fscbootstrap-fsc-magazijna-mpfm-w3h dirtxlog-test-mft-tp9; do
        printf 'https://%s.%s/|404|0|%s\n' "$host" "$BASIS" "$ROUTER_404"
    done

    printf 'https://dirui-test-mft-tp9.%s/|200|0|<html>OpenFSC Directory</html>\n' "$BASIS"
}

# Vervang het antwoord voor één URL; de rest blijft staan.
vervang() {  # $1=antwoorden $2=url $3=nieuwe regel
    printf '%s\n' "$1" | grep -vF "$2|"
    printf '%s\n' "$3"
}

# $1=antwoorden, rest=argumenten voor het script. Zet $uitvoer en $status.
draai() {
    local antwoorden="$1"
    shift

    status=0
    uitvoer="$(PATH="${STUBMAP}:${PATH}" ZAD_BASE_DOMAIN="$BASIS" \
        STUB_ANTWOORDEN="$antwoorden" bash "$SCRIPT" "$@" 2>&1)" || status=$?
}

GOED="$(goede_antwoorden)"

# --- alles zoals de tabel het beschrijft -------------------------------------------------------

draai "$GOED"

if [ "$status" -eq 0 ]; then
    ok "een omgeving die klopt levert exitcode 0"
else
    fout "een kloppende omgeving gaf exitcode ${status}: ${uitvoer}"
fi

if [ "$(printf '%s\n' "$uitvoer" | grep -c '^OK ')" -eq 16 ]; then
    ok "alle zestien componenten krijgen het oordeel OK"
else
    fout "verwacht zestien OK-regels: ${uitvoer}"
fi

case "$uitvoer" in
    *"FIXTURE ONTBREEKT"*) fout "een URL uit de tabel heeft geen fixture: ${uitvoer}" ;;
    *) ok "elke rij uit de tabel is bevraagd" ;;
esac

# --- de beheer-UI zonder muur ------------------------------------------------------------------

CTL="https://logius-fscctl-fsc-logius-mpfb-8wh.${BASIS}/"

# 307 is wat de controller zelf antwoordt: een redirect naar /directory, de beheeromgeving. Zonder
# deze test zou een oordeel dat alleen op 200 let precies de waargenomen situatie groen noemen.
zonder_muur="$(vervang "$GOED" "$CTL" "${CTL}|307|0|<a href=\"/directory\">")"

draai "$zonder_muur"

if [ "$status" -ne 0 ] && printf '%s\n' "$uitvoer" | grep -q '^OPEN .*logius-fscctl'; then
    ok "een 307 naar de beheeromgeving telt als open"
else
    fout "een 307 op de controller werd niet als open gemeld: ${uitvoer}"
fi

draai "$(vervang "$GOED" "$CTL" "${CTL}|200|0|<html>controller</html>")"

if [ "$status" -ne 0 ] && printf '%s\n' "$uitvoer" | grep -q '^OPEN .*logius-fscctl'; then
    ok "een 200 op de controller telt als open"
else
    fout "een 200 op de controller werd niet als open gemeld: ${uitvoer}"
fi

# Een 403 uit de applicatie zelf is geen muur. Het verschil zit in /oauth2/auth, en dat pad bestaat
# alleen zolang de proxy ervoor staat.
geen_proxy="$(vervang "$GOED" "https://logius-fscctl-fsc-logius-mpfb-8wh.${BASIS}/oauth2/auth" \
    "https://logius-fscctl-fsc-logius-mpfb-8wh.${BASIS}/oauth2/auth|404|0|${ROUTER_404}")"

draai "$geen_proxy"

if [ "$status" -ne 0 ] && printf '%s\n' "$uitvoer" | grep -q '^AFWIJKING .*logius-fscctl'; then
    ok "een 403 zonder oauth2-proxy erachter telt niet als muur"
else
    fout "een 403 uit de applicatie zelf werd als muur geteld: ${uitvoer}"
fi

draai "$(vervang "$GOED" "$CTL" "${CTL}|503|0|")"

if [ "$status" -ne 0 ] && printf '%s\n' "$uitvoer" | grep -q '^AFWIJKING .*logius-fscctl'; then
    ok "een 503 op de controller is een afwijking, niet open"
else
    fout "een 503 op de controller leverde het verkeerde oordeel: ${uitvoer}"
fi

# --- de mesh-poorten ---------------------------------------------------------------------------

MGR="https://logius-fscmgr-fsc-logius-mpfb-8wh.${BASIS}/"

draai "$(vervang "$GOED" "$MGR" "${MGR}|200|0|<html>manager</html>")"

if [ "$status" -ne 0 ] && printf '%s\n' "$uitvoer" | grep -q '^OPEN .*logius-fscmgr'; then
    ok "een HTTP-antwoord op een mTLS-poort telt als open"
else
    fout "een getermineerde mesh-poort werd niet gemeld: ${uitvoer}"
fi

# --- wat geen ingress hoort te hebben -----------------------------------------------------------

OUTWAY="https://logius-fscoutway-fsc-logius-mpfb-8wh.${BASIS}/"

# Het antwoord dat de outway op ZAD gaf: de router termineert de TLS, de pod verwacht hem juist wel.
draai "$(vervang "$GOED" "$OUTWAY" "${OUTWAY}|400|0|Client sent an HTTP request to an HTTPS server")"

if [ "$status" -ne 0 ] && printf '%s\n' "$uitvoer" | grep -q '^OPEN .*logius-fscoutway'; then
    ok "een ingress op de outway wordt gemeld, ook als er geen verkeer doorheen komt"
else
    fout "de gepubliceerde outway werd niet gemeld: ${uitvoer}"
fi

# Een 404 uit de applicatie zelf is geen bewijs dat er geen ingress staat: de foutpagina van de
# router is dat wel.
draai "$(vervang "$GOED" "$OUTWAY" "${OUTWAY}|404|0|{\"fout\":\"onbekend pad\"}")"

if [ "$status" -ne 0 ] && printf '%s\n' "$uitvoer" | grep -q '^OPEN .*logius-fscoutway'; then
    ok "een 404 van de applicatie zelf telt niet als niet-gepubliceerd"
else
    fout "een applicatie-404 werd voor de foutpagina van de router aangezien: ${uitvoer}"
fi

# --- de catalogus ------------------------------------------------------------------------------

DIRUI="https://dirui-test-mft-tp9.${BASIS}/"

draai "$(vervang "$GOED" "$DIRUI" "${DIRUI}|500|0|")"

if [ "$status" -ne 0 ] && printf '%s\n' "$uitvoer" | grep -q '^AFWIJKING .*dirui'; then
    ok "een catalogus die niet antwoordt is een afwijking"
else
    fout "een stukke catalogus leverde het verkeerde oordeel: ${uitvoer}"
fi

# --- het filter --------------------------------------------------------------------------------
#
# Drie cardinaliteiten: een filter dat één rij raakt, een dat er meerdere raakt, en een dat er geen
# enkele raakt. Die laatste is de gevaarlijke — een typefout in een deployment-naam zou anders een
# groene run opleveren die niets heeft gecontroleerd.

draai "$GOED" dirui

if [ "$status" -eq 0 ] && [ "$(printf '%s\n' "$uitvoer" | grep -c '^OK ')" -eq 1 ]; then
    ok "een componentnaam als filter controleert één rij"
else
    fout "het filter op componentnaam raakte niet precies één rij: ${uitvoer}"
fi

draai "$GOED" fsc-logius

if [ "$status" -eq 0 ] && [ "$(printf '%s\n' "$uitvoer" | grep -c '^OK ')" -eq 7 ]; then
    ok "een deploymentnaam als filter controleert die deployment"
else
    fout "het filter op deployment raakte niet de zeven rijen van fsc-logius: ${uitvoer}"
fi

draai "$GOED" mpfm-w3h

if [ "$status" -eq 0 ] && [ "$(printf '%s\n' "$uitvoer" | grep -c '^OK ')" -eq 6 ]; then
    ok "een projectnaam als filter controleert dat project"
else
    fout "het filter op project raakte niet de zes rijen van mpfm-w3h: ${uitvoer}"
fi

draai "$GOED" fsc-magazijnb

if [ "$status" -ne 0 ] && printf '%s\n' "$uitvoer" | grep -q "raakt geen enkele regel"; then
    ok "een filter dat niets raakt faalt in plaats van groen te eindigen"
else
    fout "een filter zonder treffers eindigde niet als fout: ${uitvoer}"
fi

# --- de tabel zelf -----------------------------------------------------------------------------
#
# De validatie van de tabel wordt door geen enkele gewone run geraakt: wie het script draait, draait
# per definitie een tabel die klopt. Ze is er voor de regel die er later bij komt.

KOPIE="${WERKMAP}/met-kapotte-regel.sh"
sed 's#"mft-tp9|test|dirui|catalogus"#"mft-tp9|test|dirui"#' "$SCRIPT" > "$KOPIE"

if ! cmp -s "$SCRIPT" "$KOPIE"; then
    status=0
    uitvoer="$(PATH="${STUBMAP}:${PATH}" ZAD_BASE_DOMAIN="$BASIS" STUB_ANTWOORDEN="$GOED" \
        bash "$KOPIE" 2>&1)" || status=$?

    if [ "$status" -ne 0 ] && printf '%s\n' "$uitvoer" | grep -q "niet precies 4 velden"; then
        ok "een regel met een weggevallen veld faalt vóór er iets gemeld wordt"
    else
        fout "een regel met drie velden kwam er doorheen: ${uitvoer}"
    fi
else
    fout "de fixture voor een kapotte tabelregel greep niet aan; is de tabel hernoemd?"
fi

KOPIE_VERWACHTING="${WERKMAP}/met-onbekende-verwachting.sh"
sed 's#"mft-tp9|test|dirui|catalogus"#"mft-tp9|test|dirui|open-graag"#' "$SCRIPT" > "$KOPIE_VERWACHTING"

if ! cmp -s "$SCRIPT" "$KOPIE_VERWACHTING"; then
    status=0
    uitvoer="$(PATH="${STUBMAP}:${PATH}" ZAD_BASE_DOMAIN="$BASIS" STUB_ANTWOORDEN="$GOED" \
        bash "$KOPIE_VERWACHTING" 2>&1)" || status=$?

    if [ "$status" -ne 0 ] && printf '%s\n' "$uitvoer" | grep -q "onbekende verwachting"; then
        ok "een verwachting die het script niet kent faalt in plaats van over te slaan"
    else
        fout "een onbekende verwachting werd stil genegeerd: ${uitvoer}"
    fi
else
    fout "de fixture voor een onbekende verwachting greep niet aan; is de tabel hernoemd?"
fi

# --- zonder curl -------------------------------------------------------------------------------

LEEG="${WERKMAP}/leeg"
mkdir -p "$LEEG"

# Bash met zijn volledige pad aanroepen: met een lege PATH is `bash` zelf ook onvindbaar, en dan
# meet deze test de shell in plaats van het script.
BASH_PAD="$(command -v bash)"

status=0
uitvoer="$(PATH="$LEEG" "$BASH_PAD" "$SCRIPT" 2>&1)" || status=$?

if [ "$status" -ne 0 ] && printf '%s\n' "$uitvoer" | grep -q "curl niet gevonden"; then
    ok "zonder curl noemt het script de oorzaak"
else
    fout "zonder curl kwam er geen bruikbare melding: ${uitvoer}"
fi

echo
if [ "$fails" -eq 0 ]; then
    echo "alle tests geslaagd"
    exit 0
fi

echo "${fails} test(s) gefaald" >&2
exit 1
