#!/usr/bin/env bash
# Draait de toetsen van de alertregels (beheer/meldingen/toetsen.yml) met promtool, in het image van
# Prometheus zelf: zo toetst dezelfde versie die de regels ook laadt. Daarna een mutatie op een kopie,
# die moet falen; anders bewijst een groene uitkomst niets.
#
# promtool vergelijkt de annotaties van een verwachte melding exact. Om ze niet in elke toets over
# te schrijven, vult dit script ze aan uit de regel zelf (met `{{ $labels.x }}` uit de verwachte
# labels). Een toets die ze wél noemt, houdt zijn eigen waarden.
#
# Containerprogramma: podman als CONTAINER_HOST gezet is (lokaal), anders docker (de CI-runner).

set -euo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
readonly REPO_ROOT
readonly MELDINGEN="$REPO_ROOT/beheer/meldingen"

# De multi-arch index; dezelfde pin als de Prometheus van de demo-stack (compose.yaml), en
# infra-image-pins in pin-consistency.yml eist dat die twee gelijk blijven.
readonly PROMETHEUS_IMAGE='docker.io/prom/prometheus:v3.5.0@sha256:63805ebb8d2b3920190daf1cb14a60871b16fd38bed42b857a3182bc621f4996'

if [ -n "${CONTAINER_HOST:-}" ]; then
  CONTAINER=(podman --remote)
else
  CONTAINER=(docker)
fi

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

WERK=$(mktemp -d)
trap 'rm -rf "$WERK"' EXIT

# Kopie van de regels plus een aangevulde toetsen.yml in $1.
klaarzetten() {
  local doel=$1

  mkdir -p "$doel"
  cp "$MELDINGEN"/*.yml "$doel/"

  python3 - "$doel" <<'PY'
import re
import sys
from pathlib import Path

import yaml

doel = Path(sys.argv[1])
toetsen = yaml.safe_load((doel / "toetsen.yml").read_text())
regels = {}

for naam in toetsen["rule_files"]:
    for groep in yaml.safe_load((doel / naam).read_text())["groups"]:
        for regel in groep["rules"]:
            regels[regel["alert"]] = regel.get("annotations", {})


def vul(tekst, labels):
    return re.sub(r"\{\{\s*\$labels\.(\w+)\s*\}\}", lambda m: labels.get(m.group(1), ""), tekst)


for toets in toetsen["tests"]:
    for geval in toets.get("alert_rule_test", []):
        if geval["alertname"] not in regels:
            sys.exit(f"toets '{toets['name']}' noemt onbekende melding {geval['alertname']}")

        for verwacht in geval.get("exp_alerts") or []:
            if "exp_annotations" not in verwacht:
                labels = verwacht.get("exp_labels", {})
                verwacht["exp_annotations"] = {k: vul(v, labels) for k, v in regels[geval["alertname"]].items()}

(doel / "toetsen.yml").write_text(yaml.safe_dump(toetsen, allow_unicode=True, sort_keys=False))
PY
}

promtool() {
  local map=$1

  "${CONTAINER[@]}" run --rm -v "$map:/r:ro,z" -w /r --entrypoint /bin/promtool "$PROMETHEUS_IMAGE" test rules toetsen.yml
}

# --- de echte regels ------------------------------------------------------------------------------

klaarzetten "$WERK/echt"

if uitvoer=$(promtool "$WERK/echt" 2>&1); then
  ok "promtool: alle toetsen van de alertregels slagen"
else
  fout "promtool faalt op de alertregels:"
  echo "$uitvoer"
fi

toetsen=$(grep -c '^  - name: ' "$MELDINGEN/toetsen.yml")

if [ "$toetsen" -ge 10 ]; then
  ok "toetsen.yml heeft $toetsen toetsen"
else
  fout "toetsen.yml heeft maar $toetsen toetsen; is er een weggevallen?"
fi

# Elke regel komt in minstens één toets voor: een regel zonder toets meldt misschien nooit.
while IFS= read -r regel; do
  if grep -q "alertname: $regel\$" "$MELDINGEN/toetsen.yml"; then
    ok "$regel heeft een toets"
  else
    fout "$regel komt in geen enkele toets voor"
  fi
done < <(sed -n 's/^      - alert: //p' "$MELDINGEN"/berichtenmagazijn.yml "$MELDINGEN"/berichtenuitvraag.yml "$MELDINGEN"/stelselregister.yml)

# --- mutaties: een kapotte regel moet de toetsen laten falen --------------------------------------

muteer() {
  local wat=$1 bestand=$2 van=$3 naar=$4 map

  map="$WERK/mutatie-$asserties"
  klaarzetten "$map"

  python3 - "$map/$bestand" "$van" "$naar" <<'PY'
import sys
pad, van, naar = sys.argv[1:]
tekst = open(pad).read()
assert tekst.count(van) == 1, (pad, van)
open(pad, "w").write(tekst.replace(van, naar))
PY

  if promtool "$map" >/dev/null 2>&1; then
    fout "mutatie '$wat' blijft groen: de toetsen bewaken dit niet"
  else
    ok "mutatie '$wat' wordt gevangen"
  fi
}

muteer "drempel foutpercentage 50%" berichtenmagazijn.yml 'omgeving!~"pr-.*"}[5m])) > 0.05' 'omgeving!~"pr-.*"}[5m])) > 0.5'
muteer "previews niet uitgesloten" stelselregister.yml 'fbs_stelseldocument_geldig_tot_seconds{dienst="stelselregister", omgeving!~"pr-.*"}' 'fbs_stelseldocument_geldig_tot_seconds{dienst="stelselregister"}'
muteer "429 telt als fout" berichtenuitvraag.yml 'omgeving!~"pr-.*", status=~"5.."}' 'omgeving!~"pr-.*", status=~"[45].."}'

echo
echo "ASSERTIES=$asserties"

[ "$mislukt" -eq 0 ] || { echo "Er zijn fouten"; exit 1; }
