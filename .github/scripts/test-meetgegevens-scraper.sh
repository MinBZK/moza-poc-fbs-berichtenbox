#!/usr/bin/env bash
# Unittests voor meetgegevens-scraper.sh. Geen netwerk: stubs voor curl en zadctl schrijven elke
# aanroep weg en geven een geregisseerd antwoord.
#
# Wat hier bewaakt wordt: dat de controle elke afwijking meldt en alleen faalt als het projectbestand
# niet te lezen is, dat de key niet op de commandoregel staat, dat inrichten per project bindt,
# instelt en één keer uitrolt, en dat de tabel klopt met de poorten van de diensten zelf.

set -euo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
readonly REPO_ROOT
readonly SCRIPT="$REPO_ROOT/.github/scripts/meetgegevens-scraper.sh"
readonly DEPLOY_YML="$REPO_ROOT/.github/workflows/deploy.yml"

asserties=0
mislukt=0

ok() {
  asserties=$((asserties + 1))
  echo "OK: $1"
}

fout() {
  mislukt=1
  echo "FOUT: $1"
}

gelijk() {
  local wat=$1 verwacht=$2 gemeten=$3

  if [ "$verwacht" = "$gemeten" ]; then
    ok "$wat"
  else
    fout "$wat — verwacht '$verwacht', gemeten '$gemeten'"
  fi
}

bevat() {
  local wat=$1 naald=$2 hooiberg=$3

  if [[ "$hooiberg" == *"$naald"* ]]; then
    ok "$wat"
  else
    fout "$wat — '$naald' ontbreekt in: ${hooiberg:0:300}"
  fi
}

mist() {
  local wat=$1 naald=$2 hooiberg=$3

  if [[ "$hooiberg" != *"$naald"* ]]; then
    ok "$wat"
  else
    fout "$wat — '$naald' hoort er niet in: $hooiberg"
  fi
}

WERK=$(mktemp -d)
trap 'rm -rf "$WERK"' EXIT

# De curl-stub: schrijft zijn argumenten en de inhoud van elk header-bestand weg, en levert
# $STUB_BODY met status $STUB_STATUS. `-o` en `-w` volgen het gebruik in het script.
cat >"$WERK/curl" <<'STUB'
#!/usr/bin/env bash
uit=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    -o) uit=$2; shift 2 ;;
    -H) printf 'H %s\n' "$2" >>"$STUB_LOG"
        if [ "${2:0:1}" = @ ]; then cat "${2:1}" >>"$STUB_HEADERS"; fi
        shift 2 ;;
    *) printf 'A %s\n' "$1" >>"$STUB_LOG"; shift ;;
  esac
done
[ "${STUB_FAAL:-0}" = 1 ] && exit 7
printf '%s' "$STUB_BODY" >"$uit"
printf '%s' "${STUB_STATUS:-200}"
STUB

# zadctl-stub: faalt met exit 3 op een aanroep die $STUB_ZADCTL_FAAL bevat.
cat >"$WERK/zadctl" <<'STUB'
#!/usr/bin/env bash
printf '%s | %s\n' "$(basename "$PWD")" "$*" >>"$STUB_LOG"
if [ -n "${STUB_ZADCTL_FAAL:-}" ] && [[ "$*" == *"$STUB_ZADCTL_FAAL"* ]]; then exit 3; fi
STUB

# gh-stub: het aantal runs per status uit $STUB_GH_<STATUS>, of falen met $STUB_GH_FAAL.
cat >"$WERK/gh" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$STUB_GH_LOG"
[ "${STUB_GH_FAAL:-0}" = 1 ] && exit 1
case "$*" in
  *"--status in_progress"*) printf '%s\n' "${STUB_GH_IN_PROGRESS:-0}" ;;
  *"--status queued"*) printf '%s\n' "${STUB_GH_QUEUED:-0}" ;;
esac
STUB

chmod +x "$WERK/curl" "$WERK/zadctl" "$WERK/gh"

export ZAD_CURL="$WERK/curl" ZAD_ZADCTL="$WERK/zadctl" ZAD_GH="$WERK/gh" STUB_LOG="$WERK/log" STUB_HEADERS="$WERK/headers" STUB_GH_LOG="$WERK/gh-log"

draai() {
  : >"$STUB_LOG"
  : >"$STUB_HEADERS"
  : >"$STUB_GH_LOG"
  set +e
  UIT=$(bash "$SCRIPT" "$@" 2>&1)
  STATUS=$?
  set -e
}

gebruik() {
  local component=$1 config=$2

  printf '{"target":"component","component":"%s","config":%s}' "$component" "$config"
}

diensten() {
  local usages
  usages=$(IFS=,; echo "$*")
  printf '{"project":"p","services":[{"name":"postgresql-database","usages":[{"target":"project","config":null}]},{"name":"metrics-scraper","usages":[%s]}]}' "$usages"
}

# --- controle -----------------------------------------------------------------------------------

export ZAD_API_KEY=geheime-sleutel-123

STUB_BODY=$(diensten "$(gebruik magazijna '{"port":28090,"path":"/q/metrics"}')" "$(gebruik magazijnb '{"port":28090,"path":"/q/metrics"}')")
export STUB_BODY
draai controle mpfm-w3h
gelijk "controle: alles goed is exit 0" 0 "$STATUS"
mist "controle: alles goed geeft geen waarschuwing" "::warning" "$UIT"
bevat "controle: noemt elk component" "mpfm-w3h/magazijnb: metrics-scraper op poort 28090" "$UIT"
bevat "controle: leest de diensten van het project" "A https://operations-manager.rig.prd1.gn2.quattro.rijksapps.nl/api/v2/projects/mpfm-w3h/services" "$(cat "$STUB_LOG")"
mist "controle: de key staat niet op de commandoregel" "geheime-sleutel-123" "$(cat "$STUB_LOG")"
bevat "controle: de key gaat via een header-bestand" "X-API-Key: geheime-sleutel-123" "$(cat "$STUB_HEADERS")"

# Eén van twee ontbreekt: precies die ene waarschuwing, en de uitrol gaat door.
STUB_BODY=$(diensten "$(gebruik magazijna '{"port":28090,"path":"/q/metrics"}')")
draai controle mpfm-w3h
gelijk "controle: een ontbrekende koppeling is geen fout" 0 "$STATUS"
bevat "controle: meldt het ontbrekende component" "::warning title=Geen meetgegevens::mpfm-w3h/magazijnb" "$UIT"
mist "controle: meldt het goede component niet" "::warning title=Geen meetgegevens::mpfm-w3h/magazijna" "$UIT"
bevat "controle: telt de afwijkingen" "1 afwijking(en) in mpfm-w3h" "$UIT"

# Geen enkele usage van de dienst, en de dienst zelf afwezig.
STUB_BODY='{"services":[]}'
draai controle mpfb-8wh
gelijk "controle: dienst afwezig is geen fout" 0 "$STATUS"
bevat "controle: dienst afwezig wordt gemeld" "::warning title=Geen meetgegevens::mpfb-8wh/uitvraag" "$UIT"

# Een usage op een ander niveau telt niet: de annotaties komen van de componentlaag.
STUB_BODY='{"services":[{"name":"metrics-scraper","usages":[{"target":"project","config":null}]}]}'
draai controle mpfb-8wh
bevat "controle: alleen projectniveau telt niet als koppeling" "::warning title=Geen meetgegevens::mpfb-8wh/uitvraag" "$UIT"

# Gekoppeld zonder poort scrapet de applicatiepoort, waar /q/metrics een 404 geeft.
for config in 'null' '{}' '{"port":8086,"path":"/q/metrics"}' '{"port":28086,"path":"/metrics"}' '{"port":28086}'; do
  STUB_BODY=$(diensten "$(gebruik uitvraag "$config")")
  draai controle mpfb-8wh
  bevat "controle: config $config is het verkeerde adres" "::warning title=Meetgegevens op het verkeerde adres::mpfb-8wh/uitvraag" "$UIT"
  gelijk "controle: config $config blokkeert niet" 0 "$STATUS"
done

STUB_BODY=$(diensten "$(gebruik uitvraag 'null')")
draai controle mpfb-8wh
bevat "controle: zonder poort noemt hij de applicatiepoort" "poort applicatiepoort, pad /metrics" "$UIT"

# Niet te lezen is wél een fout: dan weet niemand of de koppeling er staat.
for status in 401 403 404 500; do
  STUB_STATUS=$status STUB_BODY='{"detail":"nee"}' draai controle mpfs-rab
  gelijk "controle: HTTP $status is exit 1" 1 "$STATUS"
  bevat "controle: HTTP $status noemt de status" "(HTTP $status)" "$UIT"
done

STUB_BODY='geen json' draai controle mpfs-rab
gelijk "controle: geen JSON is exit 1" 1 "$STATUS"

STUB_BODY='{"services":{}}' draai controle mpfs-rab
gelijk "controle: services geen lijst is exit 1" 1 "$STATUS"

# Een andere vorm dan het schema is onleesbaar, niet "koppeling ontbreekt": anders waarschuwt elke
# run en leert iedereen de waarschuwing te negeren.
for vorm in '{"services":[{"name":"metrics-scraper"}]}' '{"services":[{"name":"metrics-scraper","usages":null}]}' '{"services":[{"usages":[]}]}'; do
  STUB_BODY=$vorm draai controle mpfs-rab
  gelijk "controle: vorm $vorm is exit 1" 1 "$STATUS"
  bevat "controle: vorm $vorm meldt onleesbaar" "::error::De diensten van mpfs-rab zijn niet te lezen" "$UIT"
done

# Opgeslagen maar niet uitgerold: de koppeling staat in het bestand, niet op het cluster.
STUB_BODY='{"services":[{"name":"metrics-scraper","usages":[{"target":"component","component":"stelselregister","config":{"port":28094,"path":"/q/metrics"}}]}],"pending_rollout":{"count":2,"rollout_in_progress":false,"since":"2026-11-28T10:00:00Z"}}'
draai controle mpfs-rab
gelijk "controle: niet uitgerold blokkeert niet" 0 "$STATUS"
bevat "controle: niet uitgerold wordt gemeld" "::warning title=Niet uitgerold::mpfs-rab heeft 2 opgeslagen wijziging(en) die niet op het cluster staan (sinds 2026-11-28T10:00:00Z)" "$UIT"

STUB_BODY='{"services":[{"name":"metrics-scraper","usages":[{"target":"component","component":"stelselregister","config":{"port":28094,"path":"/q/metrics"}}]}],"pending_rollout":{"count":2,"rollout_in_progress":true}}'
draai controle mpfs-rab
mist "controle: een lopende uitrol is geen gat" "::warning" "$UIT"

STUB_BODY='{"services":[{"name":"metrics-scraper","usages":[{"target":"component","component":"stelselregister","config":{"port":28094,"path":"/q/metrics"}}]}],"pending_rollout":{"count":0}}'
draai controle mpfs-rab
mist "controle: niets wachtend geeft geen waarschuwing" "::warning" "$UIT"

STUB_FAAL=1 draai controle mpfs-rab
gelijk "controle: curl faalt is exit 1" 1 "$STATUS"
bevat "controle: curl faalt meldt HTTP 000" "(HTTP 000)" "$UIT"

draai controle mpfpsm-lcl
gelijk "controle: een project buiten de tabel is exit 1" 1 "$STATUS"

ZAD_API_KEY='' draai controle mpfs-rab
gelijk "controle: zonder key exit 1" 1 "$STATUS"
gelijk "controle: zonder key geen aanroep" "" "$(cat "$STUB_LOG")"

draai controle
gelijk "controle: zonder project is gebruik" 64 "$STATUS"

# --- plan en apply ------------------------------------------------------------------------------

unset ZAD_API_KEY
BASIS="$WERK/basis"
for p in mpfb-8wh mpfm-w3h mpfs-rab; do
  mkdir -p "$BASIS/$p"
  : >"$BASIS/$p/.env.zadctl"
done

draai plan "$BASIS" mpfm-w3h
gelijk "plan: exit 0" 0 "$STATUS"
LOG=$(cat "$STUB_LOG")
gelijk "plan: bindt en stelt elk component in, alleen droog" "mpfm-w3h | --no-rollout service assign metrics-scraper -c magazijna --dry-run
mpfm-w3h | --no-rollout service config set metrics-scraper -c magazijna --set port=28090 --set path=/q/metrics --yes --dry-run
mpfm-w3h | --no-rollout service assign metrics-scraper -c magazijnb --dry-run
mpfm-w3h | --no-rollout service config set metrics-scraper -c magazijnb --set port=28090 --set path=/q/metrics --yes --dry-run" "$LOG"
bevat "plan: noemt de uitrol zonder hem te doen" "zou uitrollen: mpfm-w3h" "$UIT"

draai apply "$BASIS"
gelijk "apply: exit 0" 0 "$STATUS"
LOG=$(cat "$STUB_LOG")
mist "apply: niets droog" "--dry-run" "$LOG"
gelijk "apply: één uitrol per project, ná zijn componenten" "mpfb-8wh | project refresh
mpfm-w3h | project refresh
mpfs-rab | project refresh" "$(grep 'project refresh' <<<"$LOG")"
gelijk "apply: de volgorde per project" "mpfb-8wh|assign
mpfb-8wh|config
mpfb-8wh|refresh
mpfm-w3h|assign
mpfm-w3h|config
mpfm-w3h|assign
mpfm-w3h|config
mpfm-w3h|refresh
mpfs-rab|assign
mpfs-rab|config
mpfs-rab|refresh" "$(sed -E 's/^([^ ]+) \| .*(assign|config|refresh).*/\1|\2/' <<<"$LOG")"

bevat "apply: vraagt naar lopende uitrollen" "--workflow Deploy ZAD --status in_progress" "$(cat "$STUB_GH_LOG")"
bevat "apply: vraagt naar wachtende uitrollen" "--workflow Deploy ZAD --status queued" "$(cat "$STUB_GH_LOG")"

draai plan "$BASIS"
gelijk "plan: vraagt niet naar uitrollen" "" "$(cat "$STUB_GH_LOG")"

for status in IN_PROGRESS QUEUED; do
  export "STUB_GH_$status=1"
  draai apply "$BASIS"
  gelijk "apply: een uitrol op $status weigert" 1 "$STATUS"
  gelijk "apply: een uitrol op $status muteert niets" "" "$(cat "$STUB_LOG")"
  unset "STUB_GH_$status"
done

STUB_GH_IN_PROGRESS=1 MEETGEGEVENS_FORCEER=1 draai apply "$BASIS" mpfs-rab
gelijk "apply: MEETGEGEVENS_FORCEER slaat de controle over" 0 "$STATUS"
gelijk "apply: MEETGEGEVENS_FORCEER vraagt gh niets" "" "$(cat "$STUB_GH_LOG")"

STUB_GH_FAAL=1 draai apply "$BASIS"
gelijk "apply: gh faalt is weigeren" 1 "$STATUS"
gelijk "apply: gh faalt muteert niets" "" "$(cat "$STUB_LOG")"

ZAD_GH="$WERK/bestaat-niet" draai apply "$BASIS"
gelijk "apply: zonder gh weigeren" 1 "$STATUS"

# Halverwege een project faalt een stap: geen uitrol, en de melding noemt project en stap.
STUB_ZADCTL_FAAL="config set metrics-scraper -c magazijnb" draai apply "$BASIS"
gelijk "apply: een falende stap breekt af" 3 "$STATUS"
mist "apply: na een falende stap geen uitrol van dat project" "mpfm-w3h | project refresh" "$(cat "$STUB_LOG")"
bevat "apply: het vorige project is wel uitgerold" "mpfb-8wh | project refresh" "$(cat "$STUB_LOG")"
bevat "apply: noemt project en stap" "apply afgebroken bij service config set magazijnb in mpfm-w3h (exit 3)" "$UIT"
mist "apply: het stelselregister is niet aangeraakt" "mpfs-rab" "$(cat "$STUB_LOG")"

STUB_ZADCTL_FAAL="project refresh" draai apply "$BASIS" mpfs-rab
gelijk "apply: een falende uitrol breekt af" 3 "$STATUS"
bevat "apply: een falende uitrol noemt het project" "apply afgebroken bij project refresh in mpfs-rab" "$UIT"

STUB_ZADCTL_FAAL="config set" draai plan "$BASIS" mpfs-rab
mist "plan: een falende stap meldt geen open project" "apply afgebroken" "$UIT"

# Een ontbrekend token bij een later project: geen enkele mutatie, ook niet bij de eerdere.
rm "$BASIS/mpfs-rab/.env.zadctl"
draai apply "$BASIS"
gelijk "apply: zonder projecttoken exit 1" 1 "$STATUS"
gelijk "apply: zonder projecttoken geen aanroep, ook niet bij eerdere projecten" "" "$(cat "$STUB_LOG")"

draai apply "$BASIS" mpfpsm-lcl
gelijk "apply: een project buiten de tabel is exit 1" 1 "$STATUS"

draai apply "$WERK/bestaat-niet"
gelijk "apply: een basismap die niet bestaat is exit 1" 1 "$STATUS"

draai verwijder "$BASIS"
gelijk "onbekende modus is gebruik" 64 "$STATUS"

# --- de tabel tegen de repo ---------------------------------------------------------------------

# De poort is de applicatiepoort met een 2 ervoor. Een tabel die daarvan afwijkt, scrapet een poort
# waar niemand luistert, en dat ziet alleen de Prometheus.
while IFS='|' read -r component dienst; do
  regel=$(grep -E "^    \"[a-z0-9-]+\|$component\|" "$SCRIPT")
  poort=$(cut -d'|' -f3 <<<"$regel")
  app=$(sed -n 's/^quarkus\.http\.port=//p' "$REPO_ROOT/services/$dienst/src/main/resources/application.properties")

  gelijk "tabel: $component scrapet de beheerpoort van $dienst" "2$app" "$poort"
  bevat "tabel: $component staat in de componentenlijst van deploy.yml" "{name: \"$component\"" "$(cat "$DEPLOY_YML")"
done <<'PAREN'
uitvraag|berichtenuitvraag
magazijna|berichtenmagazijn
magazijnb|berichtenmagazijn
stelselregister|stelselregister
PAREN

bevat "deploy.yml draait de controle" ".github/scripts/meetgegevens-scraper.sh controle" "$(cat "$DEPLOY_YML")"

# De project-ids zijn die van deploy.yml; een id die daar wijzigt, hoort hier mee.
while IFS='|' read -r variabele project; do
  gelijk "tabel: $variabele in deploy.yml is $project" "$project" "$(sed -n "s/^  $variabele: //p" "$DEPLOY_YML")"
  bevat "tabel: $project staat in de tabel" "\"$project|" "$(cat "$SCRIPT")"
done <<'PROJECTEN'
PROJECT_UITVRAAG|mpfb-8wh
PROJECT_MAGAZIJNEN|mpfm-w3h
PROJECT_STELSELREGISTER|mpfs-rab
PROJECTEN

echo
echo "ASSERTIES=$asserties"

[ "$mislukt" -eq 0 ] || { echo "Er zijn fouten"; exit 1; }
