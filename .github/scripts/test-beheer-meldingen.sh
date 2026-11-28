#!/usr/bin/env bash
# Draait de toetsen van de alertregels (beheer/meldingen/toetsen.yml) met promtool, in het image van
# Prometheus zelf. Daarna mutaties op een kopie, die moeten falen; anders bewijst een groene uitkomst
# niets.
#
# Vóór promtool controleert een Python-stap de opzet:
#   - `rule_files` noemt precies de regelbestanden in beheer/meldingen/, zodat een nieuwe dienst niet
#     buiten de toetsen valt (promtool zelf geeft bij een ontbrekend bestand alleen een warning);
#   - elke regel heeft minstens één toets waarin hij afgaat: een toets met `exp_alerts: []` bewijst
#     niet dat een regel kán melden;
#   - de annotaties van een verwachte melding vult hij aan uit de regel zelf (promtool vergelijkt ze
#     exact), met `{{ $labels.x }}` uit de verwachte labels. Noemt een sjabloon een label dat de
#     toets niet verwacht, dan is dat een fout en geen lege plek in de melding.
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

# Ophalen met een eigen grens: een hangende pull zou anders de hele job tot zijn timeout vasthouden,
# met de uitkomst van elke andere suite erbij.
if ! "${CONTAINER[@]}" image inspect "$PROMETHEUS_IMAGE" >/dev/null 2>&1; then
  if ! timeout 180 "${CONTAINER[@]}" pull -q "$PROMETHEUS_IMAGE" >/dev/null; then
    fout "kon $PROMETHEUS_IMAGE niet binnen drie minuten ophalen"
    echo "ASSERTIES=$asserties"
    exit 1
  fi
fi

# Kopie van de regels plus een aangevulde toetsen.yml in $1; drukt per regel "positief <naam>" af.
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

bestanden = {p.name for p in doel.glob("*.yml")} - {"toetsen.yml"}
if set(toetsen["rule_files"]) != bestanden:
    sys.exit(f"rule_files {sorted(toetsen['rule_files'])} is niet gelijk aan de regelbestanden {sorted(bestanden)}")

regels = {}
for naam in toetsen["rule_files"]:
    for groep in yaml.safe_load((doel / naam).read_text())["groups"]:
        for regel in groep["rules"]:
            regels[regel["alert"]] = regel.get("annotations", {})


def vul(tekst, labels, wat):
    def label(m):
        if m.group(1) not in labels:
            sys.exit(f"{wat}: het sjabloon noemt $labels.{m.group(1)}, dat de toets niet verwacht")
        return str(labels[m.group(1)])

    return re.sub(r"\{\{\s*\$labels\.(\w+)\s*\}\}", label, tekst)


positief = set()
for toets in toetsen["tests"]:
    for geval in toets.get("alert_rule_test", []):
        naam = geval["alertname"]
        if naam not in regels:
            sys.exit(f"toets '{toets['name']}' noemt onbekende melding {naam}")

        for verwacht in geval.get("exp_alerts") or []:
            positief.add(naam)
            if "exp_annotations" not in verwacht:
                labels = verwacht.get("exp_labels", {})
                verwacht["exp_annotations"] = {k: vul(v, labels, f"{toets['name']} / {naam}") for k, v in regels[naam].items()}

zonder = sorted(set(regels) - positief)
if zonder:
    sys.exit(f"regels zonder toets waarin ze afgaan: {', '.join(zonder)}")

for naam in sorted(regels):
    print(f"positief {naam}")

(doel / "toetsen.yml").write_text(yaml.safe_dump(toetsen, allow_unicode=True, sort_keys=False))
PY
}

promtool() {
  local map=$1

  "${CONTAINER[@]}" run --rm --pull=never -v "$map:/r:ro,z" -w /r --entrypoint /bin/promtool "$PROMETHEUS_IMAGE" test rules toetsen.yml
}

# --- de echte regels ------------------------------------------------------------------------------

if ! opzet=$(klaarzetten "$WERK/echt" 2>&1); then
  fout "de opzet van de toetsen klopt niet: $opzet"
  echo "ASSERTIES=$asserties"
  exit 1
fi

while IFS= read -r regel; do
  ok "${regel#positief } heeft een toets waarin hij afgaat"
done < <(grep '^positief ' <<<"$opzet")

if uitvoer=$(promtool "$WERK/echt" 2>&1); then
  ok "promtool: alle toetsen van de alertregels slagen"
else
  fout "promtool faalt op de alertregels:"
  echo "$uitvoer"
  echo "ASSERTIES=$asserties"
  # Zonder een groene basis zegt een mislukte mutatie niets.
  exit 1
fi

# --- mutaties: een kapotte regel moet een toets laten falen ---------------------------------------

muteer() {
  local wat=$1 bestand=$2 van=$3 naar=$4 map uitvoer

  map="$WERK/mutatie-$asserties"
  klaarzetten "$map" >/dev/null

  python3 - "$map/$bestand" "$van" "$naar" <<'PY'
import sys
pad, van, naar = sys.argv[1:]
tekst = open(pad).read()
assert tekst.count(van) == 1, (pad, van)
open(pad, "w").write(tekst.replace(van, naar))
PY

  # Alleen een falende toets telt: een parsefout of een container die niet start, faalt ook, maar
  # bewijst niet dat de toetsen deze regel bewaken.
  if uitvoer=$(promtool "$map" 2>&1); then
    fout "mutatie '$wat' blijft groen: de toetsen bewaken dit niet"
  elif grep -q 'FAILED:' <<<"$uitvoer" && ! grep -qi 'parse error\|error loading' <<<"$uitvoer"; then
    ok "mutatie '$wat' wordt gevangen"
  else
    fout "mutatie '$wat' faalt om een andere reden dan een toets: $(head -c 300 <<<"$uitvoer")"
  fi
}

muteer "drempel foutpercentage 50%" berichtenmagazijn.yml 'omgeving!~"pr-.*", status!="429"}[5m])) > 0.05' 'omgeving!~"pr-.*", status!="429"}[5m])) > 0.5'
muteer "previews niet uitgesloten" stelselregister.yml 'fbs_stelseldocument_geldig_tot_seconds{dienst="stelselregister", omgeving!~"pr-.*"}' 'fbs_stelseldocument_geldig_tot_seconds{dienst="stelselregister"}'
muteer "429 telt als fout" berichtenuitvraag.yml 'omgeving!~"pr-.*", status=~"5.."}' 'omgeving!~"pr-.*", status=~"[45].."}'
muteer "magazijnen samengenomen" berichtenmagazijn.yml 'min by (omgeving, magazijn) (fbs_retentie_achterstand_oudste_seconds' 'min by (omgeving) (fbs_retentie_achterstand_oudste_seconds'
muteer "4xx telt als uitval" berichtenuitvraag.yml 'outcome=~"SERVER_ERROR|UNKNOWN"' 'outcome!="SUCCESS"'

echo
echo "ASSERTIES=$asserties"

[ "$mislukt" -eq 0 ] || { echo "Er zijn fouten"; exit 1; }
