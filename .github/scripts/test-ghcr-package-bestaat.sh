#!/usr/bin/env bash
# Unittests voor ghcr-package-bestaat.sh, en voor de aansluiting op cleanup-preview.yml. Geen
# netwerk: een gh-stub op het pad geeft een geregisseerd antwoord.
#
# De exitcode is het hele contract. Een 1 laat de ghcr-opruiming een package overslaan, dus alleen
# een aantoonbare afwezigheid mag 1 opleveren; elke twijfel hoort op 2 uit te komen.

set -euo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
readonly REPO_ROOT
readonly SCRIPT="$REPO_ROOT/.github/scripts/ghcr-package-bestaat.sh"
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
# doet de stub alsof gh zelf faalde. Meerdere pagina's zijn gewoon achter elkaar geplakte arrays,
# zoals `gh api --paginate` ze zonder `--jq` aflevert.
maak_stub() {
  local antwoord=$1 rc=${2:-0}

  printf '%s' "$antwoord" >"$werkmap/antwoord"
  rm -f "$werkmap/aanroepen"

  cat >"$werkmap/gh" <<STUB
#!/usr/bin/env bash
printf '%s\n' "\$*" >>"$werkmap/aanroepen"
cat "$werkmap/antwoord"
exit $rc
STUB
  chmod +x "$werkmap/gh"
}

draai() {
  RC=0
  UITVOER=$(PATH="$werkmap:$PATH" bash "$SCRIPT" "$@" 2>&1) || RC=$?
  AANROEPEN=$(cat "$werkmap/aanroepen" 2>/dev/null || true)
}

# --- bestaat en bestaat niet ---------------------------------------------------------------------
maak_stub '[{"name":"fbs-berichtenuitvraag"},{"name":"fbs-demo-console"}]'
draai minbzk fbs-demo-console
gelijk "een package in de lijst bestaat" 0 "$RC"
bevat "de containerlijst van de organisatie wordt opgevraagd" 'orgs/minbzk/packages?package_type=container' "$AANROEPEN"
bevat "over alle pagina's" '--paginate' "$AANROEPEN"

# Een naam met hetzelfde voorvoegsel: een vergelijking op voorvoegsel zou hier "bestaat" zeggen en een
# 404 op de versies als falen laten lezen, of andersom.
maak_stub '[{"name":"fbs-berichtenuitvraag"},{"name":"fbs-demo-console-oud"}]'
draai minbzk fbs-demo-console
gelijk "een package dat er niet in staat, bestaat niet" 1 "$RC"
bevat "en de melding noemt het package" 'fbs-demo-console bestaat (nog) niet' "$UITVOER"

# Een package op pagina 2 bestaat; wie alleen de eerste pagina leest, slaat het over.
maak_stub '[{"name":"fbs-berichtenuitvraag"}][{"name":"fbs-demo-console"}]'
draai minbzk fbs-demo-console
gelijk "een package op een latere pagina bestaat" 0 "$RC"

maak_stub '[{"name":"fbs-berichtenuitvraag"}][]'
draai minbzk fbs-demo-console
gelijk "een lege laatste pagina maakt de lijst niet onleesbaar" 1 "$RC"

# --- het referentiepackage als canary ------------------------------------------------------------
maak_stub '[{"name":"fbs-berichtenmagazijn"}]'
draai minbzk fbs-demo-console fbs-berichtenmagazijn
gelijk "een andere referentie werkt als canary" 1 "$RC"

draai minbzk fbs-demo-console
gelijk "zonder de standaardreferentie in de lijst is de meting onbruikbaar" 2 "$RC"
bevat "en de melding noemt de referentie die ontbrak" "'fbs-berichtenuitvraag'" "$UITVOER"

# --- niet vast te stellen ------------------------------------------------------------------------
# Elk van deze zou zonder eigen tak als "bestaat niet" kunnen lezen — en dan slaat de opruiming de
# versies over van een package dat er misschien wél staat.
for geval in gh-fout '<html>storing</html>' '' 'null' '[]' '{}' '{"message":"Not Found"}' \
  '[{"name":"fbs-berichtenuitvraag"}]{"message":"Bad credentials"}' '["fbs-berichtenuitvraag"]' \
  '{"pakket":{"name":"fbs-berichtenuitvraag"}}'; do
  if [ "$geval" = gh-fout ]; then
    maak_stub '[{"name":"fbs-berichtenuitvraag"}]' 1
  else
    maak_stub "$geval"
  fi

  draai minbzk fbs-demo-console
  gelijk "een onbruikbaar antwoord ('$geval') is niet vast te stellen" 2 "$RC"
done

# --- argumentcontrole ----------------------------------------------------------------------------
maak_stub '[{"name":"fbs-berichtenuitvraag"}]'

draai minbzk
gelijk "zonder package is het niet vast te stellen" 2 "$RC"

draai '' fbs-demo-console
gelijk "zonder organisatie is het niet vast te stellen" 2 "$RC"

# --- entrypoint ----------------------------------------------------------------------------------
if [ -x "$SCRIPT" ]; then
  ok "ghcr-package-bestaat.sh is uitvoerbaar"
else
  fout "ghcr-package-bestaat.sh is niet uitvoerbaar; de workflow roept hem zonder 'bash' aan"
fi

uitvoer=$(bash -c "source '$SCRIPT'" 2>&1)
gelijk "sourcen voert main niet uit" "" "$uitvoer"

# --- de aansluiting op cleanup-preview.yml -------------------------------------------------------
# Alleen exitcode 1 mag een package overslaan; 0 en 2 horen als mislukte opruiming te eindigen. De
# controles lezen de workflow met een YAML-parser: GitHub leest hem ook zo.
while IFS= read -r regel; do
  case "$regel" in
    OK:*) ok "${regel#OK:}" ;;
    FOUT:*) fout "${regel#FOUT:}" ;;
  esac
done < <(python3 - "$CLEANUP_YML" <<'PY' 2>&1 || echo "FOUT:de workflowcontrole zelf viel om"
import re
import sys

import yaml


def meld(goed, tekst):
    print(("OK:" if goed else "FOUT:") + tekst)


with open(sys.argv[1], encoding="utf-8") as bestand:
    job = (yaml.safe_load(bestand)["jobs"].get("cleanup-preview-images") or {})

stappen = job.get("steps") or []
sweep = next((i for i, s in enumerate(stappen) if s.get("id") == "sweep"), None)
checkout = next((i for i, s in enumerate(stappen) if "actions/checkout" in str(s.get("uses", ""))), None)
run = str(stappen[sweep].get("run", "")) if sweep is not None else ""

meld("ghcr-package-bestaat.sh" in run, "de ghcr-opruiming beslist met het script over een 404")
meld(checkout is not None and sweep is not None and checkout < sweep, "en checkt het script vóór de sweep uit")
meld((job.get("permissions") or {}).get("contents") == "read",
     "met contents: read, anders mag de checkout niet lezen")

# Het overslaan hangt aan precies exitcode 1. Een `if ! script` of `|| continue` zou ook 2 (onbepaald)
# als "bestaat niet" lezen.
meld(re.search(r'if \[ "\$rc" -eq 1 \]; then\s+echo [^\n]*\n\s+continue', run) is not None,
     "alleen exitcode 1 slaat een package over")
meld(re.search(r'ghcr-package-bestaat\.sh[^\n]*\|\| rc=\$\?', run) is not None,
     "de exitcode wordt opgevangen, niet door set -e afgebroken")
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
