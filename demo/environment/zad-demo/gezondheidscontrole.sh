#!/usr/bin/env bash
# Zet de ZAD-dienst `health-check` op elk component van de drie demo-projecten. Zonder die dienst
# controleert Kubernetes een component met een blinde TCP-connect op zijn eerste inbound-poort: een
# open poort telt dan als een gezonde dienst, en een component dat zijn database kwijt is blijft
# verkeer krijgen. Dit script maakt van die controle per component een keuze, mét de reden erbij.
#
# De keuze zelf staat in de tabel hieronder; hoofdstuk 9 van README.md ernaast geeft de achtergrond
# per groep. Wijzigt de tabel, werk dan dat hoofdstuk bij — de tabel is de bron.
#
# De dienst vult drie probes uit twee paden: `liveness-path` voedt zowel de startupProbe (36 × 5s =
# 180 seconden opstartbudget) als de livenessProbe (30s × 3 → herstart), `readiness-path` de
# readinessProbe (2s × 3 → geen verkeer meer). Liveness hoort daarom naar een pad te wijzen dat
# alléén over het proces gaat: een liveness die meezakt met de database herstart een component dat
# netjes staat te wachten, en maakt zo de storing die het moest opmerken.
#
# `service assign` is idempotent (een component dat de dienst al draagt houdt zijn configuratie), en
# `service config set` overschrijft per component. Het script mag dus zo vaak draaien als nodig.
#
# Anders dan bij poorten en aliassen is hier geen hercreatie nodig: de dienstconfiguratie is een
# eigen laag bij OM, geen component-creatie-payload. De configuratie komt bovendien mee in de
# `clone-from: test` die previews aanmaakt — nagemeten op toxiproxy-redis, waar `test` en `pr-290`
# dezelfde drie httpGet-probes dragen.
#
# Usage:
#   zadctl login
#   demo/environment/zad-demo/gezondheidscontrole.sh plan             # alle drie de projecten
#   demo/environment/zad-demo/gezondheidscontrole.sh apply mpfm-w3h   # één project
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

MODE="${1:?usage: gezondheidscontrole.sh <plan|apply> [project=alle]}"
FILTER="${2:-alle}"

case "$MODE" in
    plan) DROOG=(--dry-run) ;;
    apply) DROOG=() ;;
    *) echo "onbekende modus '$MODE'; gebruik plan of apply" >&2; exit 1 ;;
esac

command -v zadctl >/dev/null || {
    echo "zadctl niet gevonden; dit script heeft het nodig" >&2
    echo "zadctl: https://github.com/RijksICTGilde/zad-cli/releases/latest" >&2
    exit 1
}

# De regels: project | component | scheme | poort | liveness-pad | readiness-pad
#
# Onze eigen Kotlin/Quarkus-componenten. `quarkus-smallrye-health` levert /q/health/live (alleen het
# proces) en /q/health/ready (proces plus datasource, berichtenopslag en de andere afhankelijkheden
# die Quarkus zelf aanmeldt). Readiness zakt dus mee met Redis en PostgreSQL, liveness niet.
KOTLIN=(
    "mpfb-8wh|uitvraag|http|8086|/q/health/live|/q/health/ready"
    "mpfm-w3h|magazijna|http|8090|/q/health/live|/q/health/ready"
    "mpfm-w3h|magazijnb|http|8090|/q/health/live|/q/health/ready"
    "mpfm-w3h|democonsole|http|8095|/q/health/live|/q/health/ready"
    "mpfm-w3h|demopersonas|http|8098|/q/health/live|/q/health/ready"
    "mpfm-w3h|magazijnsimulator|http|8092|/q/health/live|/q/health/ready"
)

# De vier storingsknoppen. De probe wijst naar de admin-API op 8474 en niet naar de stroom die de
# knop dichtzet: die poort sluit Toxiproxy zodra je een proxy uitzet, en een probe daarop zou de pod
# anderhalve minuut later herstarten — mét verlies van álle proxies. Readiness hoort daar juist ook
# op 8474: de pod blijft Ready terwijl de stroom dicht is, de router antwoordt 503, en de demo laat
# zien wat een weggevallen dienst doet.
TOXIPROXY=(
    "mpfb-8wh|toxiproxy-aanmeld|http|8474|/version|/version"
    "mpfb-8wh|toxiproxy-redis|http|8474|/version|/version"
    "mpfpsm-lcl|toxiproxy-profiel|http|8474|/version|/version"
    "mpfpsm-lcl|toxiproxy-notificatie|http|8474|/version|/version"
)

# De twee WireMock-stubs. /__admin/health hoort bij de admin-API en wordt vóór de stub-mappings
# afgehandeld, dus geen mapping kan hem overnemen. Nagemeten op wiremock/wiremock:3.13.2 — het image
# uit wiremock/externe-stubs/Dockerfile: HTTP 200 met {"status":"healthy"}. Er is geen apart
# liveness-signaal, dus beide paden wijzen hierheen; een WireMock zonder werkende admin-API is stuk.
STUBS=(
    "mpfpsm-lcl|profiel|http|8080|/__admin/health|/__admin/health"
    "mpfpsm-lcl|notificatie|http|8080|/__admin/health|/__admin/health"
)

# Wat geen HTTP spreekt. Een TCP-connect is hier een eerlijke probe: Redis en PostgreSQL beginnen
# allebei met een verbinding die het serverproces zelf accepteert, en geen van beide logt een
# afgebroken poging als fout. De regel legt de keuze vast; het gerenderde manifest verandert niet.
#
# `proeftuin` staat hier om een andere reden: hij spreekt wél HTTP, maar zijn /health proxyt in het
# proeftuin-image naar een chat-backend die in dit project niet bestaat. Een httpGet daarop faalt
# gegarandeerd en herstart de pod anderhalve minuut later.
TCP=(
    "mpfb-8wh|redis|tcp|6379||"
    "mpfm-w3h|proeftuin|tcp|8080||"
    "mpfb-8wh|logius-fscpg|tcp|5432||"
    "mpfm-w3h|magazijna-fscpg|tcp|5432||"
)

# De FSC-componenten. Hun functionele poort (8443) spreekt TLS, en een blinde TCP-probe elke twee
# seconden logt daar `http: TLS handshake error ... EOF`: dertig regels per minuut die geen fout
# zijn. Alle vijf de FSC-images bedienen op hun MONITORING_ADDRESS /health/live en /health/ready.
# Nagemeten op v2.5.2 in de lokale harness: live blijft 200 als een afhankelijkheid wegvalt, ready
# zakt naar 503 en komt terug op 200 zodra die terug is. Dat is precies de scheiding die we willen —
# een outway zonder txlog krijgt geen verkeer meer, maar wordt niet herstart.
#
# De monitoring-poort staat niet in `ports.inbound` van het component. Dat hoeft ook niet: een
# httpGet-probe mag naar elke poort die de container opent, en zo blijft de poort cluster-intern.
# De manager luistert op 8080, de rest op 8081 (zie de MONITORING_ADDRESS-regels in
# demo/environment/{logius,magazijn-a}/deploy/zad/upsert-peer.sh).
FSC=(
    "mpfb-8wh|logius-fscmgr|http|8080|/health/live|/health/ready"
    "mpfb-8wh|logius-fscctl|http|8081|/health/live|/health/ready"
    "mpfb-8wh|logius-fscinway|http|8081|/health/live|/health/ready"
    "mpfb-8wh|logius-fscoutway|http|8081|/health/live|/health/ready"
    "mpfb-8wh|logius-fsctxlog|http|8081|/health/live|/health/ready"
    "mpfm-w3h|magazijna-fscmgr|http|8080|/health/live|/health/ready"
    "mpfm-w3h|magazijna-fscctl|http|8081|/health/live|/health/ready"
    "mpfm-w3h|magazijna-fscinway|http|8081|/health/live|/health/ready"
    "mpfm-w3h|magazijna-fsctxlog|http|8081|/health/live|/health/ready"
)

# De twee bootstrap-componenten draaien eenmalig en openen geen inbound poort. Zonder poort rendert
# ZAD nu al geen enkele probe; `none` maakt daar een opgeschreven keuze van in plaats van een
# gevolg. De poort is verplicht in het schema en betekent hier niets.
GEEN=(
    "mpfb-8wh|logius-fscbootstrap|none|8443||"
    "mpfm-w3h|magazijna-fscbootstrap|none|8443||"
)

REGELS=("${KOTLIN[@]}" "${TOXIPROXY[@]}" "${STUBS[@]}" "${TCP[@]}" "${FSC[@]}" "${GEEN[@]}")

case "$FILTER" in
    alle|mpfb-8wh|mpfm-w3h|mpfpsm-lcl) ;;
    *)
        echo "onbekend project '$FILTER'; kies mpfb-8wh, mpfm-w3h, mpfpsm-lcl of alle" >&2
        exit 1
        ;;
esac

# Exitcode 2 is platform of netwerk en dus de moeite van opnieuw proberen waard; 1 en 3 niet. Dat
# onderscheid doorgeven scheelt zoeken in een configuratie waar niets mis mee is.
meld_zadctl_fout() {
    local status="$1" wat="$2"

    if [ "$status" -eq 2 ]; then
        echo "$wat: platform of netwerk (exit 2). Vaak een uitrol die op dit project al loopt —" >&2
        echo "  kijk met 'gh run list --workflow \"Deploy ZAD\"' en draai daarna opnieuw." >&2
    else
        echo "$wat (exit $status); zie de melding hierboven" >&2
    fi

    exit "$status"
}

gedaan=0

for regel in "${REGELS[@]}"; do
    IFS='|' read -r project component scheme poort liveness readiness <<<"$regel"

    if [ "$FILTER" != alle ] && [ "$FILTER" != "$project" ]; then
        continue
    fi

    # `--strict` erbij, want zonder die vlag telt "aangenomen, maar er ging iets mis" — een taak die
    # door een gelijktijdige uitrol overruled is — als succes.
    ZAD=(zadctl --strict -p "$project")

    echo "== $project / $component -> $scheme :$poort ${liveness:-(geen paden)} ${readiness:-}"

    "${ZAD[@]}" service assign health-check -c "$component" "${DROOG[@]}" ||
        meld_zadctl_fout $? "health-check binden aan '$component' mislukte"

    INSTELLING=(--set "scheme=$scheme" --set "port=$poort")

    # tcp en none kennen geen paden. Ze meesturen zou het schema niet breken, maar het zou wél
    # suggereren dat er een pad gecontroleerd wordt.
    if [ -n "$liveness" ]; then
        INSTELLING+=(--set "liveness-path=$liveness" --set "readiness-path=$readiness")
    fi

    "${ZAD[@]}" service config set health-check -c "$component" "${INSTELLING[@]}" "${DROOG[@]}" ||
        meld_zadctl_fout $? "health-check instellen op '$component' mislukte"

    gedaan=$((gedaan + 1))
done

if [ "$gedaan" -eq 0 ]; then
    echo "geen enkele regel gedraaid — klopt de projectfilter '$FILTER'?" >&2
    exit 1
fi

if [ "$MODE" = "plan" ]; then
    echo
    echo "Dit was een plan; $gedaan regels getoond, niets gewijzigd. Draai 'apply' om het door te zetten."
    exit 0
fi

cat <<KLAAR

Klaar: $gedaan componenten ingesteld.

Verifiëren doe je niet in de UI maar in het gerenderde manifest — dat is wat Argo synct:

  gh api repos/RijksICTGilde/rig-cluster-application-test/contents/\\
odcn-production/<project>/test/<component>-deployment.yaml --jq '.content' | base64 -d

Verwacht een startupProbe, livenessProbe en readinessProbe die de gekozen vorm dragen. Argo heeft
een sync-ronde nodig; blijft het manifest ongewijzigd, kijk dan eerst of er een uitrol liep.

Hoofdstuk 9 van README.md ernaast beschrijft wat er daarna nog met de hand te controleren valt:
readiness die meezakt zonder herstart, en de storingsknoppen die blijven werken.
KLAAR
