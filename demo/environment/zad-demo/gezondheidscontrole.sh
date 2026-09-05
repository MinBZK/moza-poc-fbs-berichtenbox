#!/usr/bin/env bash
# Zet de ZAD-dienst `health-check` op elk component van de drie demo-projecten. Zonder die dienst
# controleert Kubernetes een component met een blinde TCP-connect op zijn eerste inbound-poort: een
# open poort telt dan als een gezonde dienst, en een component dat zijn database kwijt is blijft
# verkeer krijgen. Dit script maakt van die controle per component een keuze, mét de reden erbij.
#
# De keuze zelf staat in de tabel hieronder; die tabel is de bron. Hoofdstuk 9 van README.md ernaast
# geeft de achtergrond per groep en moet meebewegen als hier een regel verandert.
#
# De dienst vult drie probes uit twee paden: `liveness-path` voedt zowel de startupProbe (5s
# initiële vertraging plus 36 × 5s, dus ruim drie minuten opstartbudget) als de livenessProbe
# (30s × 3 → herstart), `readiness-path` de readinessProbe (2s × 3 → geen verkeer meer). Liveness
# hoort daarom naar een pad te wijzen dat alléén over het proces gaat: een liveness die meezakt met
# de database herstart een component dat netjes staat te wachten, en maakt zo de storing die het
# moest opmerken.
#
# Drie eigenschappen van de CLI die de vorm van dit script bepalen, alle drie uit `zadctl service
# describe health-check` en de `--help` van de gebruikte commando's:
#
#   - `service config set` schrijft het HELE document: een veld dat je niet noemt wordt verwijderd,
#     niet met rust gelaten. Daarom mag een tcp- of none-regel gewoon zijn paden weglaten; ze
#     verdwijnen dan bij een component dat ze eerder wél had. Het commando vraagt bevestiging vóór
#     het iets weggooit, dus `--yes` hoort erbij in een reeks van zevenentwintig.
#   - `service assign` is idempotent (een component dat de dienst al draagt houdt zijn configuratie)
#     en selecteert de dienst meteen op projectniveau, zodat een losse `service add` niet nodig is.
#   - Beide rollen standaard meteen uit naar de cluster. Dat zou 54 uitrollen betekenen, met tussen
#     het binden en het instellen van elk component een moment waarop het de dienst draagt zonder
#     configuratie. `--no-rollout` schuift dat op; één `project refresh` per project aan het eind
#     laat alles in één keer landen.
#
# De configuratielaag hangt aan het COMPONENT binnen het project (`components[*]/services{health-
# check}`), niet aan een deployment. Elke deployment die het component draait — `test` en elke
# preview — leest dus dezelfde instelling. Dat strookt met de meting op `toxiproxy-redis`, waar
# `mpfb-8wh/test` en `mpfb-8wh/pr-290` dezelfde drie httpGet-probes dragen.
#
# Twee dingen die dit script NIET kan aantonen, en die de eerste apply moet uitwijzen (stap 10 van
# verify-zad.md leest ze af uit het gerenderde manifest):
#   - dat de dienst óók aanslaat op een component dat al bestond. Poorten en aliassen doen dat niet;
#     de dienstconfiguratie is een eigen laag bij OM, dus het hoort te werken, maar bewezen is het
#     pas als het manifest verandert.
#   - dat een probe-poort die niet in `ports.inbound` staat, gerenderd wordt. Kubernetes staat een
#     httpGet naar elke geopende poort toe en de dienstbeschrijving noemt "je gezondheidsendpoint
#     zit op een andere poort dan je functionele poort" als reden om de dienst te kiezen — maar geen
#     enkel component in deze projecten doet het vandaag. Het raakt alleen de FSC-regels.
#
# Usage:
#   zadctl login
#   demo/environment/zad-demo/gezondheidscontrole.sh plan              # alle drie de projecten
#   demo/environment/zad-demo/gezondheidscontrole.sh apply mpfpsm-lcl  # één project
#   demo/environment/zad-demo/gezondheidscontrole.sh apply fsc-logius  # één deployment
#
# Draai dit NIET terwijl er een uitrol loopt: OM vergrendelt op project, en een gelijktijdige taak
# overruled de wachtstap van die uitrol. `gh run list --workflow "Deploy ZAD"` toont het.

set -euo pipefail

# `mapfile` en een lege array onder `set -u` vragen allebei bash 4.4. De bash die macOS meelevert is
# 3.2 en zou hier struikelen op een melding die de oorzaak niet noemt.
if [ "${BASH_VERSINFO[0]}" -lt 4 ] || { [ "${BASH_VERSINFO[0]}" -eq 4 ] && [ "${BASH_VERSINFO[1]}" -lt 4 ]; }; then
    echo "dit script vraagt bash 4.4 of nieuwer; deze is ${BASH_VERSION}" >&2
    echo "op macOS: 'brew install bash' en opnieuw draaien" >&2
    exit 1
fi

gebruik() {
    echo "usage: gezondheidscontrole.sh <plan|apply> [project-of-deployment=alle]" >&2
    echo "  plan   toont elke aanroep zonder te muteren, en toetst de tabel tegen wat er staat" >&2
    echo "  apply  zet de instellingen door en rolt ze per project in één keer uit" >&2
    exit 1
}

[ "$#" -ge 1 ] || gebruik

MODE="$1"
FILTER="${2:-alle}"

case "$MODE" in
    plan) DRYRUN=(--dry-run) ;;
    apply) DRYRUN=() ;;
    *) echo "onbekende modus '$MODE'" >&2; gebruik ;;
esac

for hulp in zadctl python3; do
    command -v "$hulp" >/dev/null || {
        echo "$hulp niet gevonden; dit script heeft het nodig" >&2
        echo "zadctl: https://github.com/RijksICTGilde/zad-cli/releases/latest" >&2
        exit 1
    }
done

# De regels: project | deployment | component | scheme | poort | liveness-pad | readiness-pad
#
# De groepen staan op soort, want de reden hoort bij de soort. Dat is niet de volgorde waarin je ze
# wilt uitrollen — een kale `apply` wisselt van project — dus gebruik het tweede argument om ze
# gefaseerd te doen: eerst `mpfpsm-lcl`, dan `mpfm-w3h`, dan `mpfb-8wh`, en de FSC-deployments apart.

# De twee WireMock-stubs. /__admin/health hoort bij de admin-API en wordt vóór de stub-mappings
# afgehandeld, dus geen mapping kan hem overnemen. Nagemeten op wiremock/wiremock:3.13.2 — het image
# uit wiremock/externe-stubs/Dockerfile: HTTP 200 met {"status":"healthy"}. Er is geen apart
# liveness-signaal, dus beide paden wijzen hierheen; een WireMock zonder werkende admin-API is stuk.
STUBS=(
    "mpfpsm-lcl|test|profiel|http|8080|/__admin/health|/__admin/health"
    "mpfpsm-lcl|test|notificatie|http|8080|/__admin/health|/__admin/health"
)

# De vier storingsknoppen. De probe wijst naar de admin-API op 8474 en niet naar de proxy die de knop
# dichtzet: die poort sluit Toxiproxy zodra je een proxy uitzet, en een probe daarop zou de pod
# anderhalve minuut later herstarten — mét verlies van álle proxies. Readiness hoort daar juist ook
# op 8474: de pod blijft Ready terwijl de proxy dicht is, de router antwoordt 503, en de demo laat
# zien wat een weggevallen dienst doet.
TOXIPROXY=(
    "mpfpsm-lcl|test|toxiproxy-profiel|http|8474|/version|/version"
    "mpfpsm-lcl|test|toxiproxy-notificatie|http|8474|/version|/version"
    "mpfb-8wh|test|toxiproxy-aanmeld|http|8474|/version|/version"
    "mpfb-8wh|test|toxiproxy-redis|http|8474|/version|/version"
)

# Onze eigen Kotlin/Quarkus-componenten. `quarkus-smallrye-health` levert /q/health/live (alleen het
# proces) en /q/health/ready (proces plus de afhankelijkheden die Quarkus zelf aanmeldt). Readiness
# zakt dus mee met PostgreSQL en Redis, liveness niet. Wat readiness precies meetelt verschilt per
# component: de console sluit zijn twee magazijn-datasources bewust uit (`health-exclude`), zodat een
# magazijn dat wegvalt het paneel niet uit de endpoints haalt — daar blijft Redis over.
KOTLIN=(
    "mpfm-w3h|test|demopersonas|http|8098|/q/health/live|/q/health/ready"
    "mpfm-w3h|test|democonsole|http|8095|/q/health/live|/q/health/ready"
    "mpfm-w3h|test|magazijna|http|8090|/q/health/live|/q/health/ready"
    "mpfm-w3h|test|magazijnb|http|8090|/q/health/live|/q/health/ready"
    "mpfm-w3h|test|magazijnsimulator|http|8092|/q/health/live|/q/health/ready"
    "mpfb-8wh|test|uitvraag|http|8086|/q/health/live|/q/health/ready"
)

# Wat geen HTTP spreekt. Een TCP-connect is hier een eerlijke probe: Redis en PostgreSQL beginnen
# allebei met een connect die het serverproces zelf accepteert, en geen van beide logt een
# afgebroken poging als fout. De regel legt de keuze vast; het gerenderde manifest verandert niet.
#
# `proeftuin` staat hier om een andere reden: hij spreekt wél HTTP, maar zijn /health proxyt in het
# proeftuin-image naar een chat-backend die in dit project niet bestaat. Een httpGet daarop faalt
# gegarandeerd en herstart de pod anderhalve minuut later.
TCP=(
    "mpfm-w3h|test|proeftuin|tcp|8080||"
    "mpfb-8wh|test|redis|tcp|6379||"
    "mpfb-8wh|fsc-logius|logius-fscpg|tcp|5432||"
    "mpfm-w3h|fsc-magazijna|magazijna-fscpg|tcp|5432||"
)

# De FSC-componenten. Op de manager, de inway, de outway en de txlog is de functionele poort (8443)
# een TLS-luisteraar, en de standaardcontrole opent daar elke twee seconden een socket die hij meteen
# weer sluit: `http: TLS handshake error ... EOF`, dag en nacht, zonder dat er iets aan de hand is.
# De twee controllers hebben dat probleem niet — hun eerste poort is de plain-HTTP UI op 8080 — maar
# ze volgen dezelfde keuze, want `/health/ready` zegt meer dan een open UI-poort.
#
# Alle vijf de FSC-images bedienen op hun MONITORING_ADDRESS /health/live en /health/ready. Nagemeten
# op v2.5.2 in de lokale harness, met de txlog-api stilgezet: live blijft 200 terwijl ready op inway
# en outway naar 503 zakt, en beide komen terug zodra de txlog er weer is. Dat is precies de
# scheiding die we willen — een outway zonder txlog krijgt geen verkeer meer, maar wordt niet
# herstart.
#
# De monitoring-poort staat niet in `ports.inbound`; zie de kanttekening bovenaan. De manager
# luistert op 8080, de rest op 8081 (de MONITORING_ADDRESS-regels in
# demo/environment/{logius,magazijn-a}/deploy/zad/upsert-peer.sh zijn de bron).
#
# magazijn-a heeft geen outway: die peer is aan de aanbiedende kant en draait manager, controller,
# inway, txlog en Postgres. Komt hij er ooit (zie cutover-interne-outway.md), dan hoort hier een
# regel bij.
FSC=(
    "mpfb-8wh|fsc-logius|logius-fscmgr|http|8080|/health/live|/health/ready"
    "mpfb-8wh|fsc-logius|logius-fscctl|http|8081|/health/live|/health/ready"
    "mpfb-8wh|fsc-logius|logius-fscinway|http|8081|/health/live|/health/ready"
    "mpfb-8wh|fsc-logius|logius-fscoutway|http|8081|/health/live|/health/ready"
    "mpfb-8wh|fsc-logius|logius-fsctxlog|http|8081|/health/live|/health/ready"
    "mpfm-w3h|fsc-magazijna|magazijna-fscmgr|http|8080|/health/live|/health/ready"
    "mpfm-w3h|fsc-magazijna|magazijna-fscctl|http|8081|/health/live|/health/ready"
    "mpfm-w3h|fsc-magazijna|magazijna-fscinway|http|8081|/health/live|/health/ready"
    "mpfm-w3h|fsc-magazijna|magazijna-fsctxlog|http|8081|/health/live|/health/ready"
)

# De twee bootstrap-componenten draaien eenmalig en openen geen inbound poort. Zonder poort rendert
# ZAD nu al geen enkele probe; `none` maakt daar een opgeschreven keuze van in plaats van een gevolg.
# Poort en paden blijven leeg: het schema laat ze weg, en een waarde invullen zou suggereren dat er
# iets gecontroleerd wordt.
GEEN=(
    "mpfb-8wh|fsc-logius|logius-fscbootstrap|none|||"
    "mpfm-w3h|fsc-magazijna|magazijna-fscbootstrap|none|||"
)

REGELS=("${STUBS[@]}" "${TOXIPROXY[@]}" "${KOTLIN[@]}" "${TCP[@]}" "${FSC[@]}" "${GEEN[@]}")

PROJECTEN=(mpfb-8wh mpfm-w3h mpfpsm-lcl)

bevat() {
    local naald="$1" kandidaat
    shift

    for kandidaat in "$@"; do
        [ "$kandidaat" = "$naald" ] && return 0
    done

    return 1
}

# De tabel is handwerk, en een typefout erin faalt op een manier die de oorzaak niet noemt: een
# verkeerd project slaat regels stil over, een pad bij een tcp-regel zou een httpGet zetten op een
# pad dat gegarandeerd faalt, en twee regels voor hetzelfde component laten stil de laatste winnen.
# Daarom eerst de hele tabel toetsen, vóór er één aanroep uitgaat.
declare -A GEZIEN=()

regel=0

for r in "${REGELS[@]}"; do
    regel=$((regel + 1))

    # De sluitwaarde erachter is er omdat `read` lege velden aan het eind wegzuigt: een tcp-regel
    # eindigt op twee lege paden, en zonder deze truc telt een regel met een weggevallen scheidings-
    # teken even lang als een goede. Blijft de sluitwaarde staan, dan waren het precies zeven velden.
    IFS='|' read -r project deployment component scheme poort liveness readiness rest <<<"$r|EIND"

    [ "$rest" = EIND ] || {
        echo "tabelregel $regel heeft niet precies 7 velden: $r" >&2
        exit 1
    }

    bevat "$project" "${PROJECTEN[@]}" || {
        echo "tabelregel $regel noemt onbekend project '$project': $r" >&2
        exit 1
    }

    [ -n "$deployment" ] && [ -n "$component" ] || {
        echo "tabelregel $regel mist een deployment of een componentnaam: $r" >&2
        exit 1
    }

    sleutel="$project|$deployment|$component"

    [ -z "${GEZIEN[$sleutel]:-}" ] || {
        echo "tabelregel $regel herhaalt $project/$deployment $component (eerder op regel ${GEZIEN[$sleutel]})" >&2
        echo "  twee regels voor hetzelfde component laten stil de laatste winnen." >&2
        exit 1
    }

    GEZIEN[$sleutel]="$regel"

    case "$scheme" in
        http|https)
            [ -n "$liveness" ] && [ -n "$readiness" ] || {
                echo "tabelregel $regel is '$scheme' maar mist een pad: $r" >&2
                exit 1
            }
            ;;
        tcp)
            [ -z "$liveness" ] && [ -z "$readiness" ] || {
                echo "tabelregel $regel is 'tcp' en kent geen paden: $r" >&2
                exit 1
            }
            ;;
        none)
            [ -z "$poort" ] && [ -z "$liveness" ] && [ -z "$readiness" ] || {
                echo "tabelregel $regel is 'none' en hoort poort noch paden te noemen: $r" >&2
                exit 1
            }
            ;;
        *)
            echo "tabelregel $regel noemt onbekend scheme '$scheme': $r" >&2
            exit 1
            ;;
    esac

    # Het schema kent voor beide paden `^/[A-Za-z0-9/_.\-]*$`. Een pad met een query-string of een
    # relatief pad zou pas bij de aanroep struikelen, halverwege een reeks die de rest al gemuteerd
    # heeft — dezelfde reden als bij de poortgrens hieronder.
    for pad in "$liveness" "$readiness"; do
        [ -z "$pad" ] && continue

        case "$pad" in
            /*[!A-Za-z0-9/_.-]*|[!/]*)
                echo "tabelregel $regel heeft een pad dat het schema niet toelaat: $pad" >&2
                exit 1
                ;;
        esac
    done

    # Het schema laat 1024-65535 toe en vult zonder waarde de eerste inbound-poort in. Die default
    # willen we nergens: hij zou een probe op de functionele poort zetten bij precies de componenten
    # waar dat de fout is.
    if [ "$scheme" != none ]; then
        case "$poort" in
            ''|*[!0-9]*)
                echo "tabelregel $regel heeft geen numerieke poort: $r" >&2
                exit 1
                ;;
        esac

        if [ "$poort" -lt 1024 ] || [ "$poort" -gt 65535 ]; then
            echo "tabelregel $regel heeft poort $poort, buiten het toegestane 1024-65535: $r" >&2
            exit 1
        fi
    fi
done

# De filter mag een project of een deployment noemen. Een project is de eenheid waarop OM
# vergrendelt; een deployment is de eenheid waarin de FSC-runbooks denken. `test` bestaat in alle
# drie de projecten en selecteert ze dus alle drie.
SELECTIE=()

for r in "${REGELS[@]}"; do
    IFS='|' read -r project deployment _ <<<"$r"

    if [ "$FILTER" = alle ] || [ "$FILTER" = "$project" ] || [ "$FILTER" = "$deployment" ]; then
        SELECTIE+=("$r")
    fi
done

if [ "${#SELECTIE[@]}" -eq 0 ]; then
    echo "'$FILTER' selecteert geen enkele regel. Kies 'alle', een project (${PROJECTEN[*]})" >&2
    echo "  of een deploymentnaam (test, fsc-logius, fsc-magazijna). Let op: 'test' bestaat in alle" >&2
    echo "  drie de projecten en selecteert ze dus alle drie." >&2
    exit 1
fi

# Exitcode 2 is platform of netwerk en dus de moeite van opnieuw proberen waard; 1 en 3 niet. Boven
# 128 is het een signaal — meestal Ctrl-C — en dan is er geen melding van zadctl om naar te wijzen.
duid_exitcode() {
    local status="$1"

    if [ "$status" -ge 128 ]; then
        echo "  afgebroken door signaal $((status - 128))." >&2
    elif [ "$status" -eq 2 ] && [ "$MODE" = apply ]; then
        echo "  platform of netwerk (exit 2). Vaak een uitrol die op dit project al loopt — kijk met" >&2
        echo "  'gh run list --workflow \"Deploy ZAD\"' en draai daarna opnieuw." >&2
    else
        echo "  exit $status; zie de melding van zadctl hierboven." >&2
    fi
}

# Wie halverwege afbreekt moet weten waar hij staat: het script kent geen rollback, en de regels die
# nog niet aan de beurt waren houden de blinde TCP-controle waar dit script juist vanaf wil.
gedaan=0
half=""
stand_gemeld=0

meld_stand() {
    [ "$MODE" = apply ] || return 0
    [ "$stand_gemeld" -eq 0 ] || return 0

    stand_gemeld=1

    echo "  $gedaan van ${#SELECTIE[@]} componenten waren ingesteld toen dit afbrak;" >&2
    echo "  opnieuw draaien is veilig — elke aanroep schrijft het hele document opnieuw." >&2

    if [ -n "$half" ]; then
        echo "  LET OP: $half draagt de dienst nu wél maar is niet ingesteld. Wat die component" >&2
        echo "  daarmee doet is niet vastgesteld; draai apply opnieuw vóór de volgende uitrol." >&2
    fi

    if [ "${#DRYRUN[@]}" -eq 0 ]; then
        echo "  Er is niets uitgerold: dit script schrijft met --no-rollout en rolt pas aan het eind" >&2
        echo "  uit. Wil je de opgeslagen stand tóch kwijt, kijk dan met 'zadctl project pending'." >&2
    fi
}

# De handler moet zelf afsluiten: bash draait hem tussen twee commando's door en gaat daarna gewoon
# verder. Zonder de exit meldt het script "afgebroken" en muteert het de resterende componenten
# alsnog — het tegenovergestelde van wat de operator net vroeg.
trap 'echo >&2; echo "afgebroken." >&2; meld_stand; exit 130' INT
trap 'echo >&2; echo "afgebroken." >&2; meld_stand; exit 143' TERM HUP

# Vooraf ophalen wat er in elke geselecteerde deployment staat. Dat doet drie dingen die `plan` zelf
# niet kan: het bewijst dat de sessie geldig is (--dry-run bereikt OM niet, dus een verlopen login
# komt anders pas bij de eerste echte aanroep boven), het vangt een component dat niet bestaat
# vóórdat de helft gemuteerd is, en het laat zien welke componenten géén regel hebben — precies de
# componenten die stilzwijgend op de standaardcontrole blijven staan.
#
# Die tweede richting kijkt alleen ín de deployments die de tabel noemt. Een héle deployment zonder
# regel valt er dus buiten; die vind je met `zadctl -p <project> deployment list`.
declare -A AANWEZIG=()

PAREN=()

for r in "${SELECTIE[@]}"; do
    IFS='|' read -r project deployment _ <<<"$r"
    paar="$project|$deployment"

    bevat "$paar" "${PAREN[@]}" || PAREN+=("$paar")
done

echo "== vooraf: wat staat er in ${#PAREN[@]} deployment(s)?"

for paar in "${PAREN[@]}"; do
    IFS='|' read -r project deployment <<<"$paar"

    # `--strict` erbij, want zonder die vlag telt "aangenomen, maar er ging iets mis" — een taak die
    # door een gelijktijdige uitrol overruled is — als succes. Geen 2>/dev/null: niet-ingelogd of
    # een lock bij OM zou anders als "niet gevonden" langskomen.
    status=0
    beschrijving="$(zadctl --strict -p "$project" deployment describe "$deployment" -o json)" || status=$?

    if [ "$status" -ne 0 ]; then
        echo "zadctl kon deployment '$deployment' in '$project' niet beschrijven" >&2
        duid_exitcode "$status"
        exit "$status"
    fi

    # Gestructureerd lezen en niet greppen: een grep op `"name": "x"` hangt aan de opmaak die de CLI
    # niet belooft, en matcht bovendien elke andere benoemde zaak in het antwoord. Bewust zónder
    # default op `components`: een antwoord dat die sleutel niet draagt is een veranderde CLI-uitvoer,
    # en dat mag geen "de tabel klopt niet" worden.
    status=0
    namen="$(printf '%s' "$beschrijving" | python3 -c "
import json, sys

print('\n'.join(c['name'] for c in json.load(sys.stdin)['components']))
")" || status=$?

    if [ "$status" -ne 0 ]; then
        echo "componentenlijst niet uit het describe-antwoord van '$deployment' te lezen" >&2
        duid_exitcode "$status"
        exit "$status"
    fi

    [ -n "$namen" ] || {
        echo "describe van '$project/$deployment' noemt geen enkel component." >&2
        echo "  Controleer de CLI-uitvoer, niet de tabel — een lege lijst zou hieronder elke regel" >&2
        echo "  ten onrechte als 'component bestaat niet' aanwijzen." >&2
        exit 1
    }

    AANWEZIG["$paar"]="$namen"
done

# Beide richtingen verzamelen vóór er één afsluit: wie de voorbeschouwing draait wil alles zien wat
# er mis is, niet de eerste klasse fouten en de tweede pas na een tweede ronde.
ontbreekt=0

for r in "${SELECTIE[@]}"; do
    IFS='|' read -r project deployment component _ <<<"$r"

    printf '%s\n' "${AANWEZIG["$project|$deployment"]}" | grep -qxF -- "$component" || {
        echo "de tabel noemt '$component', maar dat component staat niet in $project/$deployment" >&2
        ontbreekt=$((ontbreekt + 1))
    }
done

# De andere richting: een component dat er wél staat maar geen regel heeft, houdt de blinde
# TCP-controle. Dat is geen fout in dit script maar wel precies wat dit werk wil uitsluiten, dus het
# hoort luid gemeld te worden in plaats van stil te blijven.
zonder_regel=0

for paar in "${PAREN[@]}"; do
    IFS='|' read -r project deployment <<<"$paar"

    while read -r aanwezig; do
        [ -n "$aanwezig" ] || continue

        [ -n "${GEZIEN["$project|$deployment|$aanwezig"]:-}" ] && continue

        echo "LET OP: $project/$deployment draait '$aanwezig' zonder regel in dit script;" >&2
        echo "  dat component houdt de blinde TCP-controle op zijn eerste poort." >&2
        zonder_regel=$((zonder_regel + 1))
    done <<<"${AANWEZIG["$paar"]}"
done

if [ "$ontbreekt" -ne 0 ]; then
    echo "$ontbreekt regel(s) noemen een component dat niet bestaat; corrigeer de tabel in dit" >&2
    echo "  script voor je verder gaat. Er is nog niets gewijzigd." >&2
    exit 1
fi

echo "== ${#SELECTIE[@]} regels, $zonder_regel component(en) zonder regel"
echo

# In plan-modus is doorgaan na een fout het punt: wie de voorbeschouwing draait wil álle regels zien,
# niet de eerste die struikelt. In apply-modus is stoppen het punt: doorgaan zou de rest muteren
# terwijl er iets niet klopt.
mislukt=0

for r in "${SELECTIE[@]}"; do
    IFS='|' read -r project deployment component scheme poort liveness readiness <<<"$r"

    echo "== [$MODE] $project/$deployment $component -> $scheme ${poort:+:$poort} ${liveness:-(geen paden)} ${readiness:-}"

    # `--no-rollout`: elke aanroep zou anders meteen naar de cluster rollen, dus 54 uitrollen én een
    # moment per component waarop het de dienst draagt zonder configuratie. Aan het eind rolt één
    # `project refresh` per project alles in één keer uit.
    ZAD=(zadctl --strict --no-rollout -p "$project")

    INSTELLING=(--set "scheme=$scheme")

    [ -n "$poort" ] && INSTELLING+=(--set "port=$poort")

    if [ -n "$liveness" ]; then
        INSTELLING+=(--set "liveness-path=$liveness" --set "readiness-path=$readiness")
    fi

    rij_mislukt=0

    # De status apart opvangen en niet met `if ! ...; then status=$?`: binnen die tak is `$?` de
    # uitkomst van het `if` zelf (0), niet die van zadctl, en dan zou een mislukte apply met een
    # nul-exitcode eindigen.
    status=0
    "${ZAD[@]}" service assign health-check -c "$component" "${DRYRUN[@]}" || status=$?

    if [ "$status" -ne 0 ]; then
        echo "health-check binden aan $project/$deployment $component mislukte" >&2
        duid_exitcode "$status"

        if [ "$MODE" = apply ]; then
            meld_stand
            exit "$status"
        fi

        rij_mislukt=1
    else
        half="$project/$deployment $component"
    fi

    # `--yes`: `service config set` schrijft het hele document en vraagt bevestiging vóór het een
    # veld weggooit. Bij een regel die van http naar tcp gaat zou de reeks daar op stdin blijven
    # hangen, halverwege zevenentwintig componenten.
    status=0
    "${ZAD[@]}" service config set health-check -c "$component" --yes "${INSTELLING[@]}" "${DRYRUN[@]}" || status=$?

    if [ "$status" -ne 0 ]; then
        echo "health-check instellen op $project/$deployment $component mislukte" >&2
        duid_exitcode "$status"

        if [ "$MODE" = apply ]; then
            meld_stand
            exit "$status"
        fi

        rij_mislukt=1
    fi

    half=""

    if [ "$rij_mislukt" -ne 0 ]; then
        mislukt=$((mislukt + 1))
        continue
    fi

    gedaan=$((gedaan + 1))
done

if [ "$MODE" = "plan" ]; then
    echo
    echo "Dit was een plan; $gedaan van ${#SELECTIE[@]} regels kwamen door, $mislukt niet."
    echo "Alleen de voorbeschouwing hierboven heeft OM gesproken: --dry-run stuurt de aanroepen zelf"
    echo "niet, dus wat OM van de waarden vindt blijkt pas bij apply."

    [ "$zonder_regel" -eq 0 ] || echo "En $zonder_regel component(en) houden de blinde TCP-controle."

    [ "$mislukt" -eq 0 ] || exit 1

    exit 0
fi

# Alles staat opgeslagen maar nog niet uitgerold. Eén refresh per project laat het in één keer
# landen, in plaats van 54 losse uitrollen op projecten waarop OM project-breed vergrendelt.
PROJECTEN_GEDAAN=()

for paar in "${PAREN[@]}"; do
    IFS='|' read -r project _ <<<"$paar"

    bevat "$project" "${PROJECTEN_GEDAAN[@]}" && continue

    PROJECTEN_GEDAAN+=("$project")

    echo "== uitrollen: $project"

    status=0
    zadctl --strict -p "$project" project refresh || status=$?

    if [ "$status" -ne 0 ]; then
        echo "uitrollen van '$project' mislukte; de instellingen staan opgeslagen" >&2
        duid_exitcode "$status"
        echo "  'zadctl -p $project project pending' toont wat nog wacht; refresh kan opnieuw." >&2
        exit "$status"
    fi
done

cat <<KLAAR

Klaar: $gedaan componenten ingesteld en uitgerold over ${#PROJECTEN_GEDAAN[@]} project(en).
$( [ "$zonder_regel" -eq 0 ] || echo "LET OP: $zonder_regel component(en) houden de blinde TCP-controle — zie de regels hierboven." )

Verifiëren doe je niet in de UI maar in het gerenderde manifest — dat is wat Argo synct:

  gh api repos/RijksICTGilde/rig-cluster-application-test/contents/\\
odcn-production/<project>/<deployment>/<component>-deployment.yaml --jq '.content' | base64 -d

Verwacht een startupProbe, livenessProbe en readinessProbe die de gekozen vorm dragen, en bij een
tcp- of none-regel géén achtergebleven pad. Argo heeft een sync-ronde nodig; blijft het manifest
ongewijzigd, kijk dan eerst of er een uitrol liep.

Stap 10 van verify-zad.md ernaast loopt dat na, samen met wat er daarna met de hand te controleren
valt: readiness die meezakt zonder herstart, en de storingsknoppen die blijven werken.
KLAAR
