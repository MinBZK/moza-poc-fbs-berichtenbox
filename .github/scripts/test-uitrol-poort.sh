#!/usr/bin/env bash
# Fixture-tests voor de uitrol-poort: de check die bepaalt of een run mag doorgaan voor een merge.
# Een stille fout is hier eenrichtingsverkeer — te streng levert een rode check op die iemand
# onderzoekt, te ruim levert een groene merge op voor een uitrol die nooit gebeurd is.
#
# De poort leest zijn uitrol-resultaten uit toJSON(needs); de tests bouwen dat object na, zodat
# ook de gevallen "job ontbreekt" en "job erbij" uitgeoefend worden.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
# shellcheck source=uitrol-poort.sh
source "$HERE/uitrol-poort.sh"

fails=0
ok()      { echo "OK: $1"; }
mislukt() { echo "FAIL: $1" >&2; fails=$((fails + 1)); }

# Bouwt een toJSON(needs)-object uit regels `jobnaam=resultaat`.
maak_needs() {
  local paren=$1 json='{}' paar

  while IFS= read -r paar; do
    [ -n "$paar" ] || continue

    json=$(jq -c --arg n "${paar%%=*}" --arg r "${paar#*=}" '.[$n] = {result: $r}' <<<"$json")
  done <<<"$paren"

  printf '%s' "$json"
}

# $1 omschrijving, $2 verwachte exitcode, $3 EVENT, $4 REF, $5 CHANGES, $6 DEPLOY, $7 GATE,
# $8 needs-paren.
verwacht_poort() {
  local omschrijving=$1 verwachting=$2 event=$3 ref=$4 changes=$5 deploy=$6 gate=$7 paren=$8

  local needs rc=0 uitvoer
  needs=$(maak_needs "$paren")
  uitvoer=$(
    export EVENT="$event" REF="$ref" CHANGES="$changes" DEPLOY="$deploy" GATE="$gate" NEEDS="$needs"
    beoordeel 2>&1
  ) || rc=$?

  if [ "$rc" = "$verwachting" ]; then
    ok "$omschrijving"
  else
    mislukt "$omschrijving
  verwachtte exitcode $verwachting, kreeg $rc
  uitvoer: $(tr '\n' ' ' <<<"$uitvoer")"
  fi
}

PR_REF=refs/pull/1/merge
MAIN=refs/heads/main

# Hulpjes die een as op één resultaat zetten; de andere as staat op het spiegelbeeld, zodat elke
# test ook bewijst dat de ongebruikte as genegeerd wordt.
previews() {
  printf 'deploy-preview-uitvraag=%s\ndeploy-preview-externe-stubs=%s\ndeploy-preview-magazijnen=%s\n' "$1" "${2-$1}" "${3-$1}"
}

testdeploys() {
  printf 'deploy-test-uitvraag=%s\ndeploy-test-externe-stubs=%s\ndeploy-test-magazijnen=%s\n' "$1" "${2-$1}" "${3-$1}"
}

# Commandosubstitutie eet de laatste nieuwe regel; die hier expliciet terugzetten, anders
# plakken de laatste preview en de eerste test-deploy aan elkaar tot één regel.
beide() { printf '%s\n%s\n' "$(previews "$1")" "$(testdeploys "$2")"; }

# --- A. de changes-poort -----------------------------------------------------------------------
for uitkomst in failure cancelled skipped ''; do
  verwacht_poort "changes='$uitkomst' blokkeert" 1 \
    pull_request "$PR_REF" "$uitkomst" true success "$(beide success skipped)"
done

# --- B. askeuze per event ----------------------------------------------------------------------
verwacht_poort "push beoordeelt de test-as, niet de previews" 0 \
  push "$MAIN" success true success "$(beide failure success)"
verwacht_poort "pull_request beoordeelt de previews, niet de test-as" 0 \
  pull_request "$PR_REF" success true success "$(beide success failure)"
verwacht_poort "push buiten main blokkeert" 1 \
  push refs/heads/thema success true success "$(beide skipped success)"

for event in '' workflow_dispatch schedule; do
  verwacht_poort "onbekend event '$event' blokkeert" 1 \
    "$event" "$MAIN" success true success "$(beide success success)"
done

# --- C. deploy-uitkomst ------------------------------------------------------------------------
# Leeg of niet-booleaans is een onbepaalde detectie, geen "niets uitrollen".
for waarde in '' True TRUE onzin; do
  verwacht_poort "deploy='$waarde' blokkeert" 1 \
    pull_request "$PR_REF" success "$waarde" skipped "$(beide skipped skipped)"
done

verwacht_poort "op push blokkeert deploy=false (detectie hoort daar true te geven)" 1 \
  push "$MAIN" success false success "$(beide skipped success)"

# --- D. tak "geen uitrol verwacht" (PR, deploy=false) ------------------------------------------
verwacht_poort "PR zonder uitrolbare wijziging: alles overgeslagen" 0 \
  pull_request "$PR_REF" success false skipped "$(beide skipped skipped)"
verwacht_poort "PR zonder uitrolbare wijziging maar gate viel om" 1 \
  pull_request "$PR_REF" success false failure "$(beide skipped skipped)"

# Het afwijkende resultaat op elke positie, anders bewijst de test alleen dat de lus bij het
# eerste element stopt.
verwacht_poort "uitrol op positie 1 draaide toch" 1 \
  pull_request "$PR_REF" success false skipped "$(previews success skipped skipped)"$'\n'"$(testdeploys skipped)"
verwacht_poort "uitrol op positie 2 draaide toch" 1 \
  pull_request "$PR_REF" success false skipped "$(previews skipped failure skipped)"$'\n'"$(testdeploys skipped)"
verwacht_poort "uitrol op positie 3 draaide toch" 1 \
  pull_request "$PR_REF" success false skipped "$(previews skipped skipped success)"$'\n'"$(testdeploys skipped)"

# --- E. tak "uitrol verwacht" ------------------------------------------------------------------
verwacht_poort "alles geslaagd" 0 \
  pull_request "$PR_REF" success true success "$(beide success skipped)"

for gate in failure skipped ''; do
  verwacht_poort "gate='$gate' bij een verwachte uitrol blokkeert" 1 \
    pull_request "$PR_REF" success true "$gate" "$(beide success skipped)"
done

# 'skipped' is hoe een gevallen build zich hier toont: die job staat niet in de needs van de
# poort, dus de uitrol-jobs slaan over via hun eigen `if`.
for resultaat in failure skipped ''; do
  verwacht_poort "uitrol-job '$resultaat' op positie 1 blokkeert" 1 \
    pull_request "$PR_REF" success true success "$(previews "$resultaat" success success)"$'\n'"$(testdeploys skipped)"
  verwacht_poort "uitrol-job '$resultaat' op positie 3 blokkeert" 1 \
    pull_request "$PR_REF" success true success "$(previews success success "$resultaat")"$'\n'"$(testdeploys skipped)"
done

# --- F. afgebroken run -------------------------------------------------------------------------
verwacht_poort "geannuleerde gate certificeert niets" 1 \
  pull_request "$PR_REF" success true cancelled "$(beide success skipped)"
verwacht_poort "geannuleerde uitrol-job certificeert niets" 1 \
  pull_request "$PR_REF" success true success "$(previews success cancelled success)"$'\n'"$(testdeploys skipped)"

# --- G. kardinaliteit: leeg, één, te weinig, te veel -------------------------------------------
verwacht_poort "nul uitrol-jobs in needs blokkeert" 1 \
  pull_request "$PR_REF" success true success "$(testdeploys skipped)"
verwacht_poort "één uitrol-job in needs blokkeert" 1 \
  pull_request "$PR_REF" success true success "deploy-preview-uitvraag=success"
verwacht_poort "twee uitrol-jobs in needs blokkeert" 1 \
  pull_request "$PR_REF" success true success 'deploy-preview-uitvraag=success
deploy-preview-externe-stubs=success'
verwacht_poort "vier uitrol-jobs in needs blokkeert" 1 \
  pull_request "$PR_REF" success true success "$(previews success)"$'\ndeploy-preview-nieuw=success'
verwacht_poort "nul uitrol-jobs blokkeert ook op de niets-verwacht-tak" 1 \
  pull_request "$PR_REF" success false skipped "$(testdeploys skipped)"

# --- H. entrypoint -----------------------------------------------------------------------------
# De workflow draait `.github/scripts/uitrol-poort.sh` zonder `bash` ervoor, en sourcen dekt de
# aanroep-as niet: een verloren uitvoerbaar-bit of een kapotte BASH_SOURCE-guard laat de poort
# nooit oordelen.
if [ -x "$HERE/uitrol-poort.sh" ]; then
  ok "uitrol-poort.sh is uitvoerbaar"
else
  mislukt "uitrol-poort.sh is niet uitvoerbaar; de workflow roept hem zonder 'bash' aan"
fi

rc=0
uitvoer=$(
  EVENT=pull_request REF="$PR_REF" CHANGES=success DEPLOY=true GATE=success \
    NEEDS="$(maak_needs "$(beide success skipped)")" "$HERE/uitrol-poort.sh" 2>&1
) || rc=$?

if [ "$rc" = 0 ]; then
  ok "directe uitvoering oordeelt"
else
  mislukt "directe uitvoering gaf exitcode $rc: $(tr '\n' ' ' <<<"$uitvoer")"
fi

# --- I. kruiscontrole met deploy.yml -----------------------------------------------------------
# De poort leidt zijn resultaten af uit `needs`, dus een uitrol-job die niet in die lijst staat
# valt buiten de beoordeling. Een fixture-test ziet dat nooit: die kennis staat in deploy.yml.
DEPLOY_YML="$REPO_ROOT/.github/workflows/deploy.yml"
poort_needs=$(sed -n '/^  uitrol-poort:/,/^    if:/p' "$DEPLOY_YML" | sed -n 's/^      - //p')

while IFS= read -r job; do
  case "$poort_needs" in
    *"$job"*) ;;
    *) mislukt "uitrol-job $job staat niet in de needs van uitrol-poort — valt buiten de beoordeling" ;;
  esac
done < <(sed -n 's/^  \(deploy-\(preview\|test\)-[a-z-]*\):$/\1/p' "$DEPLOY_YML")

for voorvoegsel in deploy-preview- deploy-test-; do
  aantal=$(grep -c "^$voorvoegsel" <<<"$poort_needs" || true)

  [ "$aantal" -eq "$VERWACHT_AANTAL" ] \
    || mislukt "uitrol-poort heeft $aantal needs met voorvoegsel $voorvoegsel, VERWACHT_AANTAL is $VERWACHT_AANTAL"
done

for job in changes gate; do
  case "$poort_needs" in
    *"$job"*) ;;
    *) mislukt "uitrol-poort heeft $job niet in zijn needs; het resultaat is dan altijd leeg" ;;
  esac
done

ok "kruiscontrole van de needs met deploy.yml"

if [ "$fails" -ne 0 ]; then
  echo "$fails test(s) gefaald." >&2
  exit 1
fi

echo "Alle tests geslaagd."
