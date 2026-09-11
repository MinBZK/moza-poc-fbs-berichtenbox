#!/usr/bin/env bash
# Unittests voor zad-deployment-bestaat.sh, en voor de aansluiting op cleanup-preview.yml. Geen
# netwerk: een curl-stub op het pad geeft een geregisseerd antwoord.
#
# De exitcode is het hele contract. Een 1 laat de opruiming de delete overslaan, dus alleen een
# aantoonbare afwezigheid mag 1 opleveren; elke twijfel hoort op 2 uit te komen.

set -euo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
readonly REPO_ROOT
readonly SCRIPT="$REPO_ROOT/.github/scripts/zad-deployment-bestaat.sh"
readonly CLEANUP_YML="$REPO_ROOT/.github/workflows/cleanup-preview.yml"

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

  case "$hooiberg" in
    *"$naald"*) ok "$wat" ;;
    *) fout "$wat — '$naald' ontbreekt in: $hooiberg" ;;
  esac
}

werkmap=$(mktemp -d)
trap 'rm -rf "$werkmap"' EXIT

# Het antwoord staat in een bestand, zodat de quotes van JSON niet door een heredoc hoeven. Met `rc`
# doet de stub alsof curl zelf faalde.
maak_stub() {
  local antwoord=$1 rc=${2:-0}

  printf '%s' "$antwoord" >"$werkmap/antwoord"
  rm -f "$werkmap/aanroepen"

  cat >"$werkmap/curl" <<STUB
#!/usr/bin/env bash
printf '%s\n' "\$*" >>"$werkmap/aanroepen"
cat "$werkmap/antwoord"
exit $rc
STUB
  chmod +x "$werkmap/curl"
}

draai() {
  RC=0
  UITVOER=$(PATH="$werkmap:$PATH" ZAD_API_KEY=sleutel ZAD_API_URL=http://api.test bash "$SCRIPT" "$@" 2>&1) || RC=$?
  AANROEPEN=$(cat "$werkmap/aanroepen" 2>/dev/null || true)
}

# --- bestaat en bestaat niet ---------------------------------------------------------------------
maak_stub '{"deployments":[{"name":"test"},{"name":"pr-7"}]}'
draai mpfm-w3h pr-7
gelijk "een deployment in de lijst bestaat" 0 "$RC"
bevat "de lijst van het juiste project wordt opgevraagd" 'http://api.test/v2/projects/mpfm-w3h/deployments' "$AANROEPEN"
bevat "met de API-sleutel" 'X-API-Key: sleutel' "$AANROEPEN"

# Een buurnummer met hetzelfde voorvoegsel: een vergelijking op voorvoegsel zou hier "bestaat" zeggen
# en de opruiming van pr-7 laten wachten op een preview die er niet is.
maak_stub '{"deployments":[{"name":"test"},{"name":"pr-70"}]}'
draai mpfm-w3h pr-7
gelijk "een deployment die er niet in staat, bestaat niet" 1 "$RC"

maak_stub '{"deployments":[{"name":"test"}]}'
draai mpfm-w3h pr-7
gelijk "een project met alleen de baseline heeft geen preview" 1 "$RC"

# --- de baseline als canary --------------------------------------------------------------------------
maak_stub '{"deployments":[{"name":"acceptatie"},{"name":"pr-7"}]}'
draai mpfm-w3h pr-7 acceptatie
gelijk "een andere baseline werkt als canary" 0 "$RC"

draai mpfm-w3h pr-7
gelijk "zonder de standaard-baseline in de lijst is de meting onbruikbaar" 2 "$RC"
bevat "en de melding noemt de baseline die ontbrak" "'test'" "$UITVOER"

# --- niet vast te stellen ----------------------------------------------------------------------------
# Elk van deze zou zonder eigen tak als "bestaat niet" kunnen lezen — en dan slaat de opruiming de
# delete over van een preview die er misschien wél staat.
for geval in curl-fout '<html>storing</html>' '' 'null' '[]' '{}' '{"deployments":"geen lijst"}' '{"deployments":[]}' '{"deployments":[{"name":"pr-3"}]}'; do
  if [ "$geval" = curl-fout ]; then
    maak_stub '{"deployments":[{"name":"test"}]}' 22
  else
    maak_stub "$geval"
  fi

  draai mpfm-w3h pr-7
  gelijk "een onbruikbaar antwoord ('$geval') is niet vast te stellen" 2 "$RC"
done

# --- argumentcontrole --------------------------------------------------------------------------------
maak_stub '{"deployments":[{"name":"test"}]}'

draai mpfm-w3h
gelijk "zonder deployment is het niet vast te stellen" 2 "$RC"

draai '' pr-7
gelijk "zonder project is het niet vast te stellen" 2 "$RC"

RC=0
PATH="$werkmap:$PATH" ZAD_API_KEY='' ZAD_API_URL=http://api.test bash "$SCRIPT" mpfm-w3h pr-7 >/dev/null 2>&1 || RC=$?
gelijk "zonder ZAD_API_KEY is het niet vast te stellen" 2 "$RC"

# --- entrypoint --------------------------------------------------------------------------------------
if [ -x "$SCRIPT" ]; then
  ok "zad-deployment-bestaat.sh is uitvoerbaar"
else
  fout "zad-deployment-bestaat.sh is niet uitvoerbaar; de workflows roepen hem zonder 'bash' aan"
fi

uitvoer=$(ZAD_API_KEY=sleutel bash -c "source '$SCRIPT'" 2>&1)
gelijk "sourcen voert main niet uit" "" "$uitvoer"

if grep -q 'zad-deployment-bestaat\.sh' "$REPO_ROOT/.github/scripts/preview-klaarzetten.sh"; then
  ok "preview-klaarzetten.sh stelt het bestaan met hetzelfde script vast"
else
  fout "preview-klaarzetten.sh gebruikt zad-deployment-bestaat.sh niet meer"
fi

# --- de aansluiting op cleanup-preview.yml -----------------------------------------------------------
# De opruiming leest de exitcode via een step-output. Deze controles lezen de workflow met een
# YAML-parser: GitHub leest hem ook zo.
while IFS= read -r regel; do
  case "$regel" in
    OK:*) ok "${regel#OK:}" ;;
    FOUT:*) fout "${regel#FOUT:}" ;;
  esac
done < <(python3 - "$CLEANUP_YML" <<'PY' 2>&1 || echo "FOUT:de workflowcontrole zelf viel om"
import sys

import yaml


def meld(goed, tekst):
    print(("OK:" if goed else "FOUT:") + tekst)


with open(sys.argv[1], encoding="utf-8") as bestand:
    job = (yaml.safe_load(bestand)["jobs"].get("cleanup-preview-zad") or {})

stappen = job.get("steps") or []


def index(voorwaarde):
    return next((i for i, stap in enumerate(stappen) if voorwaarde(stap)), None)


bepaal = index(lambda s: s.get("id") == "bestaat")
verwijder = index(lambda s: "zad-actions/cleanup" in str(s.get("uses", "")))
controle = index(lambda s: s.get("id") != "bestaat" and "zad-deployment-bestaat.sh" in str(s.get("run", "")))

meld(bepaal is not None and "zad-deployment-bestaat.sh" in str(stappen[bepaal].get("run", "")),
     "de opruiming bepaalt met het script of er een preview is")
meld(bepaal is not None and verwijder is not None and bepaal < verwijder,
     "dat gebeurt vóór de delete")

# Een lege output (de bepaalstap viel om) mag de delete niet overslaan: alleen `false` doet dat.
voorwaarde = str(stappen[verwijder].get("if", "")) if verwijder is not None else ""
meld("steps.bestaat.outputs.bestaat != 'false'" in voorwaarde and "!cancelled()" in voorwaarde,
     "de delete slaat alleen over bij een aantoonbare afwezigheid")

meld(verwijder is not None and controle is not None and verwijder < controle,
     "na de delete meet het script de afwezigheid na")
meld(controle is not None and "!cancelled()" in str(stappen[controle].get("if", "")),
     "die nameting draait ook als de delete faalde")

# Regels weghalen van een deployment die niet bestaat, wordt in Operations Manager een mislukte taak
# die het project vergrendelt; en van een deployment die wél bestaat, verdwijnen ze met de delete.
meld(not any("cross-domain-preview.sh" in str(stap.get("run", "")) for stap in stappen),
     "de opruiming haalt geen netwerkregels apart weg")
PY
)

echo
if [ "$mislukt" -eq 0 ]; then
  echo "Alle tests geslaagd."
else
  echo "Er zijn tests mislukt."
fi

echo "ASSERTIES=$asserties"

exit "$mislukt"
