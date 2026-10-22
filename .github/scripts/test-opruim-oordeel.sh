#!/usr/bin/env bash
# Fixture-tests voor opruim-oordeel.sh, plus de kruiscontrole met cleanup-preview.yml.
#
# Het oordeel leest alles uit toJSON(needs); de tests bouwen dat object na. Naast de exitcode toetsen
# ze de melding: "mislukt" zonder reden is net zo onbruikbaar als een stille groene run, en "niets te
# doen" waar wel iets verwijderd is, verbergt precies de resten waar deze job voor bestaat.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"
CLEANUP_YML="$REPO_ROOT/.github/workflows/cleanup-preview.yml"
# shellcheck source=opruim-oordeel.sh
source "$HERE/opruim-oordeel.sh"

fails=0
geslaagd=0
ok()      { geslaagd=$((geslaagd + 1)); echo "OK: $1"; }
mislukt_test() { echo "FAIL: $1" >&2; fails=$((fails + 1)); }

werkmap=$(mktemp -d)
trap 'rm -rf "$werkmap"' EXIT

# Een geslaagde opruiming zonder iets aan te treffen: de basis waar elk geval één ding aan wijzigt.
LEEG='{
  "doel": {"result": "success", "outputs": {"pr": "42", "owner": "minbzk"}},
  "cleanup-preview-zad": {"result": "success", "outputs": {
    "bestond-uitvraag": "false", "bestond-externe-stubs": "false",
    "bestond-magazijnen": "false", "bestond-stelselregister": "false"}},
  "cleanup-preview-github": {"result": "success", "outputs": {"comments": "0", "deployments": "0", "omgeving": "404"}},
  "cleanup-preview-images": {"result": "success", "outputs": {"versies": "0"}}
}'

# $1 omschrijving, $2 verwachte exitcode, $3 verwacht patroon, $4 jq-bewerking op LEEG, $5 CANCELLED.
verwacht() {
  local omschrijving=$1 code=$2 patroon=$3 bewerking=$4 geannuleerd=${5:-false}
  local needs rc=0 uitvoer

  needs=$(jq -c "$bewerking" <<<"$LEEG")
  : >"$werkmap/samenvatting"
  uitvoer=$(
    export CANCELLED="$geannuleerd" NEEDS="$needs" GITHUB_STEP_SUMMARY="$werkmap/samenvatting"
    beoordeel 2>&1
  ) || rc=$?

  if [ "$rc" != "$code" ]; then
    mislukt_test "$omschrijving
  verwachtte exitcode $code, kreeg $rc
  uitvoer: $(tr '\n' ' ' <<<"$uitvoer")"

    return
  fi

  if [[ $uitvoer != *"$patroon"* ]]; then
    mislukt_test "$omschrijving
  uitvoer bevat '$patroon' niet
  uitvoer: $(tr '\n' ' ' <<<"$uitvoer")"

    return
  fi

  # Precies één uitkomstregel in de samenvatting, welke tak er ook genomen is.
  if [ "$(grep -c '^\*\*Opruiming' "$werkmap/samenvatting" || true)" != 1 ]; then
    mislukt_test "$omschrijving
  de samenvatting heeft niet precies één uitkomstregel: $(cat "$werkmap/samenvatting")"

    return
  fi

  ok "$omschrijving"
}

# --- A. niets aangetroffen ------------------------------------------------------------------------
# Een preview die nooit bestond (docs-PR, fork-PR, gestapelde PR) is een geldige toestand: groen.
verwacht "niets aangetroffen is 'niets te doen'" 0 "niets te doen" '.'
verwacht "een omgeving die niet bestond (404) is geen werk" 0 "niets te doen" \
  '."cleanup-preview-github".outputs.omgeving = "404"'

# --- B. iets opgeruimd ----------------------------------------------------------------------------
verwacht "een ZAD-deployment die bestond is 'gelukt'" 0 "ZAD-deployment in magazijnen" \
  '."cleanup-preview-zad".outputs."bestond-magazijnen" = "true"'
verwacht "een onbekende stand met geslaagde nameting telt als werk" 0 "Opruiming gelukt" \
  '."cleanup-preview-zad".outputs."bestond-uitvraag" = "onbekend"'
verwacht "een verwijderde comment is 'gelukt'" 0 "1 deploy-comment(s)" \
  '."cleanup-preview-github".outputs.comments = "1"'
verwacht "verwijderde GitHub-deployments zijn 'gelukt'" 0 "31 GitHub-deployment(s)" \
  '."cleanup-preview-github".outputs.deployments = "31"'
verwacht "een verwijderde omgeving (204) is 'gelukt'" 0 "GitHub-omgeving" \
  '."cleanup-preview-github".outputs.omgeving = "204"'
verwacht "verwijderde ghcr-versies zijn 'gelukt'" 0 "3 ghcr-versie(s)" \
  '."cleanup-preview-images".outputs.versies = "3"'
verwacht "alles tegelijk noemt elk onderdeel" 0 "ZAD-deployment in uitvraag, ZAD-deployment in stelselregister, 2 deploy-comment(s), 4 GitHub-deployment(s), GitHub-omgeving, 9 ghcr-versie(s)" \
  '."cleanup-preview-zad".outputs."bestond-uitvraag" = "true"
   | ."cleanup-preview-zad".outputs."bestond-stelselregister" = "true"
   | ."cleanup-preview-github".outputs = {"comments": "2", "deployments": "4", "omgeving": "204"}
   | ."cleanup-preview-images".outputs.versies = "9"'

# --- C. afgebroken run ----------------------------------------------------------------------------
# Aan de resultaten is een annulering niet te herkennen: ook een volledig geslaagde vorm is dan rood.
verwacht "een afgebroken run is 'mislukt'" 1 "afgebroken" '.' true

# --- D. doel ---------------------------------------------------------------------------------------
for uitkomst in failure cancelled skipped; do
  verwacht "doel='$uitkomst' is 'mislukt'" 1 "Het doel eindigde als '$uitkomst'" \
    ".doel.result = \"$uitkomst\""
done

verwacht "een ontbrekend doel is 'mislukt'" 1 "Het doel eindigde als 'ontbreekt'" 'del(.doel)'
verwacht "een doel zonder PR-nummer is 'mislukt'" 1 "er is niets opgeruimd" '.doel.outputs.pr = ""'

# Precies de vorm die deze job moet vangen: doel faalt, alles eronder staat op 'skipped'.
verwacht "doel faalt en alles eronder slaat over" 1 "Het doel eindigde als 'failure'" \
  '.doel.result = "failure" | .doel.outputs = {} | (.[] | select(.result == "success")).result = "skipped"'

# --- E. opruimjobs --------------------------------------------------------------------------------
for job in cleanup-preview-zad cleanup-preview-github cleanup-preview-images; do
  for uitkomst in failure cancelled skipped; do
    verwacht "$job='$uitkomst' is 'mislukt'" 1 "$job=$uitkomst" ".\"$job\".result = \"$uitkomst\""
  done

  verwacht "een ontbrekende $job is 'mislukt'" 1 "$job=ontbreekt" "del(.\"$job\")"
done

verwacht "een mislukte opruiming noemt het herstelcommando" 1 "gh workflow run cleanup-preview.yml -f pr=42" \
  '."cleanup-preview-images".result = "failure"'

# Een mislukte job weegt zwaarder dan wat er wél verwijderd is.
verwacht "deels opgeruimd met een mislukte job is 'mislukt'" 1 "cleanup-preview-zad=failure" \
  '."cleanup-preview-zad".result = "failure" | ."cleanup-preview-images".outputs.versies = "5"'

# --- F. het outputs-contract ----------------------------------------------------------------------
# Een ontbrekend aantal mag niet als "nul verwijderd" doorgaan: dan leest een opruiming die iets
# wegnam als "niets te doen".
for output in comments deployments omgeving; do
  verwacht "een ontbrekende output $output is 'mislukt'" 1 "als $output in plaats van een aantal" \
    "del(.\"cleanup-preview-github\".outputs.$output)"
done

verwacht "een ontbrekende output versies is 'mislukt'" 1 "als versies in plaats van een aantal" \
  'del(."cleanup-preview-images".outputs.versies)'
verwacht "een aantal dat geen getal is, is 'mislukt'" 1 "meldde 'drie' als versies" \
  '."cleanup-preview-images".outputs.versies = "drie"'
verwacht "een negatief aantal is 'mislukt'" 1 "meldde '-1' als comments" \
  '."cleanup-preview-github".outputs.comments = "-1"'
verwacht "een ZAD-job zonder enige bestond-output is 'mislukt'" 1 "voor geen enkel project" \
  '."cleanup-preview-zad".outputs = {}'
verwacht "een onbekende ZAD-stand is 'mislukt'" 1 "meldde 'misschien' voor uitvraag" \
  '."cleanup-preview-zad".outputs."bestond-uitvraag" = "misschien"'

# --- G. het omgevingscontract ---------------------------------------------------------------------
uitvoer=$(CANCELLED=false NEEDS='geen json' bash "$HERE/opruim-oordeel.sh" 2>&1) && rc=0 || rc=$?
[ "$rc" = 1 ] && [[ $uitvoer == *"geen bruikbare toJSON(needs)"* ]] \
  && ok "een NEEDS die geen JSON is, is 'mislukt'" \
  || mislukt_test "een NEEDS die geen JSON is: rc=$rc, $uitvoer"

for ontbreekt in CANCELLED NEEDS; do
  uitvoer=$(env -u "$ontbreekt" CANCELLED=false NEEDS="$LEEG" bash -c "unset $ontbreekt; '$HERE/opruim-oordeel.sh'" 2>&1) && rc=0 || rc=$?
  [ "$rc" = 1 ] && [[ $uitvoer == *"$ontbreekt ontbreekt"* ]] \
    && ok "zonder $ontbreekt meldt het script het verbroken contract" \
    || mislukt_test "zonder $ontbreekt: rc=$rc, $uitvoer"
done

uitvoer=$(CANCELLED=false NEEDS="$LEEG" bash "$HERE/opruim-oordeel.sh" 2>&1) && rc=0 || rc=$?
[ "$rc" = 0 ] && [[ $uitvoer == *"niets te doen"* ]] \
  && ok "directe aanroep geeft hetzelfde oordeel als de functie" \
  || mislukt_test "directe aanroep: rc=$rc, $uitvoer"

# --- H. kruiscontrole met cleanup-preview.yml -----------------------------------------------------
# Een YAML-parser en geen regex: GitHub leest YAML-vorm. Eén regel per bevinding, OK:/FOUT:.
while IFS= read -r regel; do
  case "$regel" in
    OK:*) ok "${regel#OK:}" ;;
    FOUT:*) mislukt_test "${regel#FOUT:}" ;;
    *) mislukt_test "onverwachte uitvoer van de kruiscontrole: $regel" ;;
  esac
done < <(OPRUIM_JOBS="$OPRUIM_JOBS" python3 - "$CLEANUP_YML" <<'PY' 2>&1 || echo "FOUT:de kruiscontrole zelf viel om"
import os
import re
import sys

import yaml


def meld(goed, tekst):
    print(("OK:" if goed else "FOUT:") + tekst)


with open(sys.argv[1], encoding="utf-8") as bestand:
    jobs = yaml.safe_load(bestand)["jobs"]

oordeel = jobs.get("opruim-oordeel") or {}
needs = oordeel.get("needs") or []
needs = [needs] if isinstance(needs, str) else needs
overige = [naam for naam in jobs if naam != "opruim-oordeel"]

# Een opruimjob die niet onder het oordeel hangt, kan stil overslaan zonder dat iemand het ziet.
meld(sorted(needs) == sorted(overige), "opruim-oordeel hangt aan elke andere job van cleanup-preview.yml")
meld(
    sorted(os.environ["OPRUIM_JOBS"].split()) == sorted(n for n in overige if n != "doel"),
    "OPRUIM_JOBS in opruim-oordeel.sh noemt precies de opruimjobs",
)
meld(str(oordeel.get("if", "")).replace(" ", "") == "${{always()}}", "opruim-oordeel draait op always()")

stappen = oordeel.get("steps") or []
meld(
    bool(stappen) and all(re.search(r"(always|cancelled)\(\)", str(s.get("if", ""))) for s in stappen),
    "elke stap van opruim-oordeel overleeft een afbreking",
)
aanroep = [s for s in stappen if "opruim-oordeel.sh" in str(s.get("run", ""))]
meld(
    len(aanroep) == 1 and (aanroep[0].get("env") or {}).get("NEEDS") == "${{ toJSON(needs) }}",
    "opruim-oordeel geeft toJSON(needs) door aan het script",
)


def stap(job, stap_id):
    return next((s for s in job.get("steps") or [] if s.get("id") == stap_id), {})


# De ZAD-matrix: één output per leg, en de stap die ze zet.
zad = jobs.get("cleanup-preview-zad") or {}
legs = [leg.get("naam") for leg in zad.get("strategy", {}).get("matrix", {}).get("include") or []]
verwacht = {f"bestond-{naam}": f"${{{{ steps.bestaat.outputs.bestond-{naam} }}}}" for naam in legs}
meld(bool(legs) and (zad.get("outputs") or {}) == verwacht, "cleanup-preview-zad heeft per matrix-leg een bestond-output")
bestaat = stap(zad, "bestaat")
meld(
    (bestaat.get("env") or {}).get("NAAM") == "${{ matrix.naam }}"
    and 'bestond-$NAAM=' in str(bestaat.get("run", "")),
    "de stap 'bestaat' zet de bestond-output van zijn eigen leg",
)

# De GitHub-kant en de images: elke output verwijst naar een stap die hem ook zet.
for job_naam, outputs in (
    ("cleanup-preview-github", {"comments": ("comment", "verwijderd"), "deployments": ("deployments", "verwijderd"), "omgeving": ("omgeving", "status")}),
    ("cleanup-preview-images", {"versies": ("sweep", "versies")}),
):
    job = jobs.get(job_naam) or {}
    for output, (stap_id, sleutel) in outputs.items():
        meld(
            (job.get("outputs") or {}).get(output) == f"${{{{ steps.{stap_id}.outputs.{sleutel} }}}}",
            f"{job_naam} publiceert {output} uit stap {stap_id}",
        )
        meld(
            f"{sleutel}=" in str(stap(job, stap_id).get("run", "")),
            f"stap {stap_id} van {job_naam} zet {sleutel}",
        )
PY
)

if [ "$fails" -ne 0 ]; then
  echo "$fails test(s) gefaald." >&2
  exit 1
fi

echo "Alle tests geslaagd."
echo "ASSERTIES=$geslaagd"
