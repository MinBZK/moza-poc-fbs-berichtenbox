#!/usr/bin/env bash
# Toetst van BUITEN de cluster wat elk FSC-component op ZAD aan een bezoeker zonder account laat
# zien. De beheer-UI van een controller draait met AUTHN_TYPE=none: wie de pagina opent is meteen
# beheerder, en de adressen zijn af te leiden uit de publieke repository. De afscherming zit daarom
# niet in het component maar ervóór, in de ZAD-dienst `authorization-wall`. Die binding is
# componentconfiguratie bij OM en staat in geen enkel bestand van deze repo — zonder een controle
# als deze merkt niemand het wanneer hij bij een nieuwe peer of na een hercreatie ontbreekt.
#
# Het script heeft geen ZAD-sessie en geen API-key nodig: het kijkt precies zoals een buitenstaander
# kijkt, over de publieke ingress. Dat is ook de reden dat het niets mag concluderen uit een
# component dat het niet ziet — zie de kanttekening bij `geen-ingress` hieronder.
#
# De tabel is de bron. Komt er een peer bij, dan hoort zijn rij hier; anders staat zijn beheer-UI
# open zonder dat een controle daalt.
#
# Usage:
#   demo/environment/zad-demo/beheertoegang.sh             # alle rijen
#   demo/environment/zad-demo/beheertoegang.sh fsc-logius  # één project, deployment of component
#
# Exitcode 0 betekent: elk component antwoordde zoals de tabel het beschrijft. Elke afwijking is
# exitcode 1, want zowel "de muur staat er niet" als "de muur staat er waar hij niet hoort" vraagt
# om iemand die kijkt.

set -euo pipefail

BASIS_DOMEIN="${ZAD_BASE_DOMAIN:-rig.prd1.gn2.quattro.rijksapps.nl}"
TIMEOUT="${ZAD_HTTP_TIMEOUT:-15}"

FILTER="${1:-alle}"

case "$FILTER" in
    -h|--help|help)
        echo "usage: beheertoegang.sh [project-of-deployment-of-component=alle]" >&2
        exit 1
        ;;
esac

command -v curl >/dev/null || {
    echo "curl niet gevonden; dit script heeft het nodig" >&2
    exit 1
}

# De regels: project | deployment | component | verwachting
#
# De vier verwachtingen, en wat ze toetsen:
#
#   muur           het component draagt een beheer-UI en hoort achter de oauth2-proxy te staan.
#                  Bewijs: HTTP 403 op `/` (de proxy antwoordt een sessieloze aanvraag met de
#                  inlogpagina in de body, niet met een redirect) én HTTP 401 op `/oauth2/auth`.
#                  Die tweede aanroep is wat de muur van een 403 uit de applicatie zelf scheidt.
#   mesh           de poort spreekt mTLS en de ingress doet SNI-passthrough. Een aanvraag zonder
#                  clientcertificaat hoort te stranden in de handshake: curl krijgt geen HTTP-code.
#                  Komt er wél een code terug, dan termineert de router de TLS en landt er platte
#                  HTTP op een poort die een peer-identiteit hoort te eisen.
#   geen-ingress   het component hoort helemaal niet op het web te staan. Bewijs: de foutpagina van
#                  de router ("Applicatie niet gevonden"). LET OP: een hostnaam die niet bestaat
#                  geeft exact dezelfde pagina. Deze uitkomst zegt dus "van buiten niet te
#                  bereiken" en niet "het component draait" — dat laatste leest de
#                  gezondheidscontrole ernaast.
#   catalogus      publiek zonder login, en dat is de bedoeling: een FSC-directory is een
#                  deelnemerscatalogus. De regel staat er zodat de keuze is opgeschreven en een
#                  latere beheerfunctie op datzelfde adres opvalt.

PEER_LOGIUS=(
    "mpfb-8wh|fsc-logius|logius-fscctl|muur"
    "mpfb-8wh|fsc-logius|logius-fscmgr|mesh"
    "mpfb-8wh|fsc-logius|logius-fscinway|mesh"
    "mpfb-8wh|fsc-logius|logius-fscoutway|geen-ingress"
    "mpfb-8wh|fsc-logius|logius-fsctxlog|geen-ingress"
    "mpfb-8wh|fsc-logius|logius-fscpg|geen-ingress"
    "mpfb-8wh|fsc-logius|logius-fscbootstrap|geen-ingress"
)

PEER_MAGAZIJNA=(
    "mpfm-w3h|fsc-magazijna|magazijna-fscctl|muur"
    "mpfm-w3h|fsc-magazijna|magazijna-fscmgr|mesh"
    "mpfm-w3h|fsc-magazijna|magazijna-fscinway|mesh"
    "mpfm-w3h|fsc-magazijna|magazijna-fsctxlog|geen-ingress"
    "mpfm-w3h|fsc-magazijna|magazijna-fscpg|geen-ingress"
    "mpfm-w3h|fsc-magazijna|magazijna-fscbootstrap|geen-ingress"
)

# De gedeelde testomgeving (MinBZK/moza-fsc-testnet). Die repo levert de images, maar de peers
# bepalen zelf hun instellingen; het directory-project draait manager plus directory-UI en geen
# controller, dus er is daar geen beheer-UI om af te schermen.
TESTNET=(
    "mft-tp9|test|dirmgr|mesh"
    "mft-tp9|test|dirui|catalogus"
    "mft-tp9|test|dirtxlog|geen-ingress"
)

REGELS=("${PEER_LOGIUS[@]}" "${PEER_MAGAZIJNA[@]}" "${TESTNET[@]}")

BODY_BESTAND="$(mktemp)"
trap 'rm -f "$BODY_BESTAND"' EXIT

# Eén aanroep, drie uitkomsten: de HTTP-code (000 als er geen antwoord kwam), de exitcode van curl
# en het antwoord zelf in $BODY_BESTAND. `-k` staat erop omdat een certificaatfout hier geen oordeel is:
# op de mesh-poorten hoort de handshake juist te mislukken, en het onderscheid dat dit script maakt
# is "kwam er een HTTP-antwoord" en niet "was het certificaat geldig".
haal() {  # $1=url; zet $code en $curl_status
    code="$(curl -sSk -m "$TIMEOUT" -o "$BODY_BESTAND" -w '%{http_code}' "$1" 2>/dev/null)" && curl_status=0 || curl_status=$?
}

router_foutpagina() {
    grep -q 'Applicatie niet gevonden' "$BODY_BESTAND"
}

echo "== beheertoegang op ${BASIS_DOMEIN}"
echo

afwijkingen=0
gecontroleerd=0

for r in "${REGELS[@]}"; do
    # De sluitwaarde vangt een weggevallen scheidingsteken: blijft EIND staan, dan waren het precies
    # vier velden.
    IFS='|' read -r project deployment component verwachting rest <<<"$r|EIND"

    [ "$rest" = EIND ] || {
        echo "tabelregel heeft niet precies 4 velden: $r" >&2
        exit 1
    }

    case "$FILTER" in
        alle|"$project"|"$deployment"|"$component") ;;
        *) continue ;;
    esac

    gecontroleerd=$((gecontroleerd + 1))

    host="${component}-${deployment}-${project}.${BASIS_DOMEIN}"
    haal "https://${host}/"

    oordeel=""
    uitleg=""

    case "$verwachting" in
        muur)
            if [ "$code" = 403 ]; then
                haal "https://${host}/oauth2/auth"

                if [ "$code" = 401 ]; then
                    oordeel=OK
                    uitleg="403 op / en 401 op /oauth2/auth — de oauth2-proxy staat ervoor"
                else
                    oordeel=AFWIJKING
                    uitleg="403 op / maar /oauth2/auth gaf ${code}; die 403 komt niet van de muur"
                fi
            elif [ "$code" -ge 200 ] && [ "$code" -lt 400 ]; then
                # Ook een 3xx telt als open: de controller beantwoordt `/` met een 307 naar
                # /directory, en die pagina is de beheeromgeving zelf.
                oordeel=OPEN
                uitleg="HTTP ${code} zonder login — de beheer-UI antwoordt zelf, er staat geen muur voor"
            else
                oordeel=AFWIJKING
                uitleg="verwacht 403 van de muur, kreeg ${code} (curl ${curl_status})"
            fi
            ;;
        mesh)
            if [ "$code" = 000 ] && [ "$curl_status" -ne 0 ]; then
                oordeel=OK
                uitleg="geen HTTP-antwoord (curl ${curl_status}) — passthrough, de handshake eist een clientcertificaat"
            else
                oordeel=OPEN
                uitleg="HTTP ${code} op een mTLS-poort — de router termineert de TLS"
            fi
            ;;
        geen-ingress)
            if [ "$code" = 404 ] && router_foutpagina; then
                oordeel=OK
                uitleg="foutpagina van de router — niet gepubliceerd"
            else
                oordeel=OPEN
                uitleg="HTTP ${code} (curl ${curl_status}) — er staat een ingress op een component dat er geen hoort te hebben"
            fi
            ;;
        catalogus)
            if [ "$code" = 200 ]; then
                oordeel=OK
                uitleg="200 zonder login, zoals bedoeld voor een deelnemerscatalogus"
            else
                oordeel=AFWIJKING
                uitleg="verwacht 200, kreeg ${code} (curl ${curl_status})"
            fi
            ;;
        *)
            echo "tabelregel noemt onbekende verwachting '${verwachting}': $r" >&2
            exit 1
            ;;
    esac

    printf '%-10s %-12s %-24s %-14s %s\n' "$oordeel" "$verwachting" "$component" "$deployment" "$uitleg"

    [ "$oordeel" = OK ] || afwijkingen=$((afwijkingen + 1))
done

[ "$gecontroleerd" -ne 0 ] || {
    echo "filter '${FILTER}' raakt geen enkele regel" >&2
    exit 1
}

echo

if [ "$afwijkingen" -eq 0 ]; then
    echo "${gecontroleerd} component(en) gecontroleerd; alles antwoordde zoals de tabel het beschrijft."
    exit 0
fi

echo "${afwijkingen} van ${gecontroleerd} component(en) wijken af." >&2

# Aanhalingstekens om het heredoc-einde: de namen van de ZAD-diensten staan hieronder tussen backticks,
# en zonder die aanhalingstekens voert de shell ze uit in plaats van ze te tonen.
cat >&2 <<'AFWIJKEND'

Staat een beheer-UI open, dan ontbreekt de binding van `keycloak` op dat component: zonder die
binding rendert OM geen oauth2-proxy-sidecar, ook als `authorization-wall` op het project is
geselecteerd. Hoofdstuk 10 van README.md ernaast zet beide, en noemt wat er verder aan vastzit.

Staat er een ingress op een component dat er geen hoort te hebben, haal dan `publish-on-web` van dat
component. Een hercreatie van een component brengt zo'n publicatie terug, want het is
componentconfiguratie en geen bestand in deze repo.
AFWIJKEND

exit 1
