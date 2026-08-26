#!/usr/bin/env bash
# Fixture-tests voor de uitrol-poort: de check die bepaalt of een run een merge mag dragen. Een
# stille fout is hier eenrichtingsverkeer — te streng levert een rode check op die iemand
# onderzoekt, te ruim levert een groene merge op voor een uitrol die nooit gebeurd is.
#
# De poort leest zijn resultaten uit toJSON(needs); de tests bouwen dat object na, zodat ook de
# gevallen "job ontbreekt" en "job erbij" uitgeoefend worden. Naast de exitcode toetsen ze de
# foutmelding: een poort die alleen nog rood wordt zonder te zeggen waaróm is voor een
# required check net zo onbruikbaar als een groene.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
# shellcheck source=uitrol-poort.sh
source "$HERE/uitrol-poort.sh"

fails=0
geslaagd=0
ok()      { geslaagd=$((geslaagd + 1)); echo "OK: $1"; }
mislukt() { echo "FAIL: $1" >&2; fails=$((fails + 1)); }

# Bouwt een toJSON(needs)-object uit regels `jobnaam=resultaat`.
needs_json() {
  local paren=$1 json='{}' paar

  while IFS= read -r paar; do
    [ -n "$paar" ] || continue

    json=$(jq -c --arg n "${paar%%=*}" --arg r "${paar#*=}" '.[$n] = {result: $r}' <<<"$json")
  done <<<"$paren"

  printf '%s' "$json"
}

# Drie previews, drie test-deploys en drie bouw-jobs uit telkens drie resultaten. `-` staat voor
# een leeg resultaat, zodat woordsplitsing die waarde niet opslokt.
fixture() {
  local -a p t b
  read -r -a p <<<"$1"
  read -r -a t <<<"$2"
  read -r -a b <<<"${3:-success success success}"

  local -a namen=(
    "deploy-preview-uitvraag=${p[0]}" "deploy-preview-externe-stubs=${p[1]}" "deploy-preview-magazijnen=${p[2]}"
    "deploy-test-uitvraag=${t[0]}" "deploy-test-externe-stubs=${t[1]}" "deploy-test-magazijnen=${t[2]}"
    "build=${b[0]}" "build-externe-stubs=${b[1]}" "build-contract-bootstrap=${b[2]}"
  )

  printf '%s\n' "${namen[@]}" | sed 's/=-$/=/'
}

# $1 omschrijving, $2 verwachte exitcode, $3 verwacht patroon in de uitvoer ('' = niet toetsen),
# $4 EVENT, $5 REF, $6 CANCELLED, $7 CHANGES, $8 DEPLOY, $9 GATE, ${10} needs-paren.
verwacht_poort() {
  local omschrijving=$1 code=$2 patroon=$3 event=$4 ref=$5 geannuleerd=$6
  local changes=$7 deploy=$8 gate=$9 paren=${10}

  local needs rc=0 uitvoer
  needs=$(needs_json "$paren")
  uitvoer=$(
    export EVENT="$event" REF="$ref" CANCELLED="$geannuleerd" CHANGES="$changes" \
      DEPLOY="$deploy" GATE="$gate" NEEDS="$needs"
    beoordeel 2>&1
  ) || rc=$?

  if [ "$rc" != "$code" ]; then
    mislukt "$omschrijving
  verwachtte exitcode $code, kreeg $rc
  uitvoer: $(tr '\n' ' ' <<<"$uitvoer")"

    return
  fi

  if [ -n "$patroon" ] && [[ $uitvoer != *"$patroon"* ]]; then
    mislukt "$omschrijving
  uitvoer bevat '$patroon' niet
  uitvoer: $(tr '\n' ' ' <<<"$uitvoer")"

    return
  fi

  ok "$omschrijving"
}

PR_REF=refs/pull/1/merge
MAIN=refs/heads/main
DRIE_OK='success success success'
DRIE_UIT='skipped skipped skipped'

# --- A. afgebroken run ---------------------------------------------------------------------------
# De vorm die een annulering aanneemt is niet van een legitieme uitkomst te onderscheiden: `gate`
# en de uitrol-jobs dragen zelf `!cancelled()` en rapporteren dan 'skipped'. Zonder de expliciete
# CANCELLED-invoer zou juist de docs-only-vorm hieronder groen zijn.
verwacht_poort "afgebroken run op de uitrol-tak blokkeert" 1 "run is afgebroken" \
  pull_request "$PR_REF" true success true success "$(fixture "$DRIE_OK" "$DRIE_UIT")"
verwacht_poort "afgebroken run op de niets-verwacht-tak blokkeert" 1 "run is afgebroken" \
  pull_request "$PR_REF" true success false skipped "$(fixture "$DRIE_UIT" "$DRIE_UIT")"
verwacht_poort "afgebroken run op een push blokkeert" 1 "run is afgebroken" \
  push "$MAIN" true success true success "$(fixture "$DRIE_UIT" "$DRIE_OK")"

# --- B. de changes-poort -------------------------------------------------------------------------
for uitkomst in failure cancelled skipped ''; do
  verwacht_poort "changes='$uitkomst' blokkeert" 1 "wijzigingsdetectie eindigde als" \
    pull_request "$PR_REF" false "$uitkomst" true success "$(fixture "$DRIE_OK" "$DRIE_UIT")"
done

# --- C. askeuze per event ------------------------------------------------------------------------
verwacht_poort "push beoordeelt de test-as" 0 "Alle 3 uitrol-jobs geslaagd" \
  push "$MAIN" false success true success "$(fixture "$DRIE_UIT" "$DRIE_OK")"
verwacht_poort "pull_request beoordeelt de previews" 0 "Alle 3 uitrol-jobs geslaagd" \
  pull_request "$PR_REF" false success true success "$(fixture "$DRIE_OK" "$DRIE_UIT")"
verwacht_poort "push buiten main blokkeert" 1 "alleen main" \
  push refs/heads/thema false success true success "$(fixture "$DRIE_UIT" "$DRIE_OK")"

for event in '' workflow_dispatch schedule merge_group; do
  verwacht_poort "onbekend event '$event' blokkeert" 1 "Onbekend event" \
    "$event" "$MAIN" false success true success "$(fixture "$DRIE_OK" "$DRIE_OK")"
done

# De as die bij dit event niet hoort te draaien moet volledig stil zijn; draait daar tóch iets,
# dan matcht de `if` van die jobs breder dan bedoeld.
verwacht_poort "een draaiende test-deploy op een PR blokkeert" 1 "niet hoort te draaien" \
  pull_request "$PR_REF" false success true success "$(fixture "$DRIE_OK" "skipped success skipped")"
verwacht_poort "een draaiende preview op een push blokkeert" 1 "niet hoort te draaien" \
  push "$MAIN" false success true success "$(fixture "skipped skipped failure" "$DRIE_OK")"

# --- D. deploy-uitkomst --------------------------------------------------------------------------
for waarde in '' True TRUE onzin; do
  verwacht_poort "deploy='$waarde' blokkeert" 1 "in plaats van true/false" \
    pull_request "$PR_REF" false success "$waarde" skipped "$(fixture "$DRIE_UIT" "$DRIE_UIT")"
done

# Op een push valt de detectie altijd terug op alles-aan; deploy=false betekent daar dat ze is
# omgevallen. Beide gevallen zijn beslissend: zonder de invariant meldt de poort hier groen.
verwacht_poort "push met deploy=false en overgeslagen test-deploys blokkeert" 1 "detectie omgevallen" \
  push "$MAIN" false success false success "$(fixture "$DRIE_UIT" "$DRIE_UIT")"
verwacht_poort "push met deploy=false en overgeslagen gate blokkeert" 1 "detectie omgevallen" \
  push "$MAIN" false success false skipped "$(fixture "$DRIE_UIT" "$DRIE_UIT")"

# --- E. tak "geen uitrol verwacht" (PR, deploy=false) ---------------------------------------------
verwacht_poort "PR zonder uitrolbare wijziging: alles overgeslagen" 0 "geen uitrol verwacht" \
  pull_request "$PR_REF" false success false skipped "$(fixture "$DRIE_UIT" "$DRIE_UIT")"
verwacht_poort "PR zonder uitrolbare wijziging maar gate viel om" 1 "kwaliteitspoort eindigde als 'failure'" \
  pull_request "$PR_REF" false success false failure "$(fixture "$DRIE_UIT" "$DRIE_UIT")"
verwacht_poort "PR zonder uitrolbare wijziging maar gate geannuleerd" 1 "kwaliteitspoort eindigde als 'cancelled'" \
  pull_request "$PR_REF" false success false cancelled "$(fixture "$DRIE_UIT" "$DRIE_UIT")"

# Het afwijkende resultaat op elke positie, anders bewijst de test alleen dat de lus bij het
# eerste element stopt.
verwacht_poort "uitrol op positie 1 draaide toch" 1 "'deploy-preview-uitvraag'" \
  pull_request "$PR_REF" false success false skipped "$(fixture "success skipped skipped" "$DRIE_UIT")"
verwacht_poort "uitrol op positie 2 draaide toch" 1 "'deploy-preview-externe-stubs'" \
  pull_request "$PR_REF" false success false skipped "$(fixture "skipped failure skipped" "$DRIE_UIT")"
verwacht_poort "uitrol op positie 3 draaide toch" 1 "'deploy-preview-magazijnen'" \
  pull_request "$PR_REF" false success false skipped "$(fixture "skipped skipped success" "$DRIE_UIT")"

# --- F. tak "uitrol verwacht" ---------------------------------------------------------------------
for gate in failure skipped cancelled ''; do
  verwacht_poort "gate='$gate' bij een verwachte uitrol blokkeert" 1 "er is niet uitgerold" \
    pull_request "$PR_REF" false success true "$gate" "$(fixture "$DRIE_OK" "$DRIE_UIT")"
done

# 'skipped' is hoe een gevallen of verdrongen build zich hier toont: die jobs staan wél in de
# needs (voor de diagnose) maar niet op de uitrol-as, dus de uitrol-jobs slaan over via hun `if`.
for resultaat in failure skipped cancelled ''; do
  verwacht_poort "uitrol-job '$resultaat' op positie 1 blokkeert" 1 "'deploy-preview-uitvraag'" \
    pull_request "$PR_REF" false success true success "$(fixture "${resultaat:--} success success" "$DRIE_UIT")"
  verwacht_poort "uitrol-job '$resultaat' op positie 2 blokkeert" 1 "'deploy-preview-externe-stubs'" \
    pull_request "$PR_REF" false success true success "$(fixture "success ${resultaat:--} success" "$DRIE_UIT")"
  verwacht_poort "uitrol-job '$resultaat' op positie 3 blokkeert" 1 "'deploy-preview-magazijnen'" \
    pull_request "$PR_REF" false success true success "$(fixture "success success ${resultaat:--}" "$DRIE_UIT")"
done

# De bouw-jobs zijn de gebruikelijke oorzaak van een overgeslagen uitrol; hun stand hoort in de
# melding te staan, anders wijst de fout naar het gevolg in plaats van naar de oorzaak.
verwacht_poort "een verdrongen build staat in de melding" 1 "bouw: build=cancelled" \
  pull_request "$PR_REF" false success true success \
  "$(fixture "$DRIE_UIT" "$DRIE_UIT" "cancelled success success")"

# --- G. kardinaliteit, op beide assen -------------------------------------------------------------
DRIE_BOUW='build=success
build-externe-stubs=success
build-contract-bootstrap=success'

verwacht_poort "nul previews in needs blokkeert" 1 "in plaats van 3" \
  pull_request "$PR_REF" false success true success "$DRIE_BOUW"
verwacht_poort "één preview in needs blokkeert" 1 "in plaats van 3" \
  pull_request "$PR_REF" false success true success "deploy-preview-uitvraag=success
$DRIE_BOUW"
verwacht_poort "twee previews in needs blokkeert" 1 "in plaats van 3" \
  pull_request "$PR_REF" false success true success "deploy-preview-uitvraag=success
deploy-preview-externe-stubs=success
$DRIE_BOUW"
verwacht_poort "vier previews in needs blokkeert" 1 "in plaats van 3" \
  pull_request "$PR_REF" false success true success "$(fixture "$DRIE_OK" "$DRIE_UIT")
deploy-preview-nieuw=success"
verwacht_poort "nul test-deploys in needs blokkeert op een push" 1 "in plaats van 3" \
  push "$MAIN" false success true success "$DRIE_BOUW"
verwacht_poort "twee test-deploys in needs blokkeert op een push" 1 "in plaats van 3" \
  push "$MAIN" false success true success "deploy-test-uitvraag=success
deploy-test-externe-stubs=success
$DRIE_BOUW"
verwacht_poort "een ontbrekende stille as blokkeert ook" 1 "in plaats van 3" \
  pull_request "$PR_REF" false success true success "deploy-preview-uitvraag=success
deploy-preview-externe-stubs=success
deploy-preview-magazijnen=success
$DRIE_BOUW"

# --- H. onbruikbare invoer -------------------------------------------------------------------------
for kapot in '' '{oeps' 'null' '[1,2,3]'; do
  rc=0
  uitvoer=$(
    export EVENT=pull_request REF="$PR_REF" CANCELLED=false CHANGES=success DEPLOY=true \
      GATE=success NEEDS="$kapot"
    "$HERE/uitrol-poort.sh" 2>&1
  ) || rc=$?

  if [ "$rc" -ne 0 ] && [[ $uitvoer == *"::error::"* ]]; then
    ok "onbruikbare NEEDS ('$kapot') blokkeert met een annotatie"
  else
    mislukt "onbruikbare NEEDS ('$kapot') gaf rc=$rc zonder ::error::: $(tr '\n' ' ' <<<"$uitvoer")"
  fi
done

rc=0
uitvoer=$(EVENT=pull_request "$HERE/uitrol-poort.sh" 2>&1) || rc=$?

if [ "$rc" -ne 0 ] && [[ $uitvoer == *"::error::"* ]]; then
  ok "ontbrekende omgevingsvariabelen blokkeren met een annotatie"
else
  mislukt "ontbrekende omgeving gaf rc=$rc zonder ::error::: $(tr '\n' ' ' <<<"$uitvoer")"
fi

# --- I. entrypoint ---------------------------------------------------------------------------------
# De workflow draait `.github/scripts/uitrol-poort.sh` zonder `bash` ervoor, en sourcen dekt die
# as niet. Beide richtingen toetsen: een script dat `beoordeel` niet aanroept of dat de exitcode
# wegslikt, levert bij alléén een succes-fixture nog steeds rc=0.
if [ -x "$HERE/uitrol-poort.sh" ]; then
  ok "uitrol-poort.sh is uitvoerbaar"
else
  mislukt "uitrol-poort.sh is niet uitvoerbaar; de workflow roept hem zonder 'bash' aan"
fi

direct() {
  local changes=$1 deploy=$2 previews=$3

  EVENT=pull_request REF="$PR_REF" CANCELLED=false CHANGES="$changes" DEPLOY="$deploy" \
    GATE=success NEEDS="$(needs_json "$(fixture "$previews" "$DRIE_UIT")")" \
    "$HERE/uitrol-poort.sh" 2>&1
}

rc=0
uitvoer=$(direct success true "$DRIE_OK") || rc=$?

if [ "$rc" = 0 ] && [[ $uitvoer == *"Alle 3 uitrol-jobs geslaagd"* ]]; then
  ok "directe uitvoering velt het positieve oordeel"
else
  mislukt "directe uitvoering gaf rc=$rc zonder verdict: $(tr '\n' ' ' <<<"$uitvoer")"
fi

rc=0
uitvoer=$(direct failure true "$DRIE_OK") || rc=$?

if [ "$rc" = 1 ] && [[ $uitvoer == *"::error::"* ]]; then
  ok "directe uitvoering blokkeert en annoteert"
else
  mislukt "directe uitvoering blokkeerde niet: rc=$rc, $(tr '\n' ' ' <<<"$uitvoer")"
fi

if [ -z "$(bash -c "source '$HERE/uitrol-poort.sh'" 2>/dev/null)" ]; then
  ok "sourcen velt geen oordeel"
else
  mislukt "sourcen voert beoordeel uit; de BASH_SOURCE-guard is weg"
fi

# --- J. kruiscontrole met deploy.yml -----------------------------------------------------------------
# De poort leidt zijn resultaten af uit `needs`, dus een uitrol-job die niet in die lijst staat
# valt buiten de beoordeling én buiten de telling. Een fixture-test ziet dat nooit: die kennis
# staat in deploy.yml.
DEPLOY_YML="$REPO_ROOT/.github/workflows/deploy.yml"
poort_needs=$(sed -n '/^  uitrol-poort:/,/^    if:/p' "$DEPLOY_YML" | sed -n 's/^      - //p')
kruis_fouten=$fails

bevat_regel() {
  case $'\n'"$1"$'\n' in
    *$'\n'"$2"$'\n'*) return 0 ;;
    *) return 1 ;;
  esac
}

# Ontdekken op wát een job doet en niet op hoe hij heet: een uitrol-job die de naamconventie niet
# volgt (`deploy-acceptatie-…`) zou anders buiten deze controle vallen, en dan certificeert de
# poort een uitrol die hij nooit beoordeeld heeft. De zad-actions-deploy-stap is het kenmerk.
uitrol_jobs=$(awk '
  /^  [a-z0-9_-]+:$/ { job = $1; sub(/:$/, "", job) }
  /uses: RijksICTGilde\/zad-actions\/deploy@/ { if (job != "") print job }
' "$DEPLOY_YML" | sort -u)

if [ -z "$uitrol_jobs" ]; then
  mislukt "geen enkele uitrol-job gevonden in $DEPLOY_YML; deze controle meet niets"
else
  while IFS= read -r job; do
    bevat_regel "$poort_needs" "$job" \
      || mislukt "uitrol-job $job staat niet in de needs van uitrol-poort — valt buiten de beoordeling"
  done <<<"$uitrol_jobs"
fi

for voorvoegsel in deploy-preview- deploy-test-; do
  aantal=$(grep -c "^$voorvoegsel" <<<"$poort_needs" || true)

  [ "$aantal" -eq "$VERWACHT_AANTAL" ] \
    || mislukt "uitrol-poort heeft $aantal needs met voorvoegsel $voorvoegsel, VERWACHT_AANTAL is $VERWACHT_AANTAL"
done

# `changes` en `gate` leveren het oordeel, de bouw-jobs de diagnose; ontbreekt er één, dan leest
# zijn resultaat als leeg.
for job in changes gate build build-externe-stubs build-contract-bootstrap; do
  bevat_regel "$poort_needs" "$job" \
    || mislukt "uitrol-poort heeft $job niet in zijn needs; het resultaat is dan altijd leeg"
done

[ "$fails" -ne "$kruis_fouten" ] \
  || ok "kruiscontrole van de needs met deploy.yml"

# Een stap draait niet meer zodra de run is afgebroken, tenzij zijn `if:` `always()` of
# `cancelled()` noemt. Blijft het oordeel daardoor liggen, dan eindigt de job groen — de job-brede
# `always()` houdt alleen de runner aan, niet de stappen erin.
stap_fouten=$fails

while IFS=$'\t' read -r stap bewaakt; do
  [ "$bewaakt" = 1 ] \
    || mislukt "stap '$stap' van uitrol-poort mist always()/cancelled() en wordt bij een afbreking overgeslagen"
done < <(awk '
  /^  uitrol-poort:/            { job = 1 }
  job && /^    steps:$/         { stappen = 1; next }
  stappen && /^  [a-z]/         { stappen = 0 }
  stappen && /^      - / {
    if (label != "") print label "\t" bewaakt
    label = $0; bewaakt = 0; next
  }
  stappen && /^        if:/ && /(always|cancelled)\(\)/ { bewaakt = 1 }
  END { if (label != "") print label "\t" bewaakt }
' "$DEPLOY_YML")

[ "$fails" -ne "$stap_fouten" ] \
  || ok "elke stap van uitrol-poort overleeft een afbreking"

if [ "$fails" -ne 0 ]; then
  echo "$fails test(s) gefaald." >&2
  exit 1
fi

echo "Alle tests geslaagd."
echo "ASSERTIES=$geslaagd"
