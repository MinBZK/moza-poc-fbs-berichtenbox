#!/usr/bin/env bash
# Toetst wat aan .github/actions/maven-cache zonder runner te toetsen is: het besluit of een run de
# cache bewaart, en de samenhang tussen de bewaar- en herstelstappen.
#
# Beide falen stil. Een bewaarbesluit dat ook bij `pull_request_target` of `workflow_run` "ja" zegt,
# laat PR-code de cache van main vullen. Een sleutel die tussen bewaren en herstellen uiteenloopt,
# laat PR's de cache van main nooit meer vinden; zonder `restore-keys` haalt mvnw de distributie dan
# weer zonder retry van Central, en alles blijft groen.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ACTION="$HERE/../actions/maven-cache/action.yml"

fails=0
geslaagd=0
ok()   { geslaagd=$((geslaagd + 1)); echo "OK: $1"; }
fout() { echo "FAIL: $1" >&2; fails=$((fails + 1)); }

WERKMAP=$(mktemp -d)
trap 'rm -rf "$WERKMAP"' EXIT

# Leest één veld uit de action via een echte YAML-parser; zie jdk-handtekening.py voor waarom
# geen regex. Een ontbrekende stap of een onleesbaar bestand breekt de suite af in plaats van een
# leeg veld op te leveren dat de asserties hieronder stil laat slagen.
yaml_veld() {
  python3 - "$ACTION" "$1" <<'PY'
import sys, yaml

actie = yaml.safe_load(open(sys.argv[1], encoding="utf-8"))
stappen = actie["runs"]["steps"]
vraag = sys.argv[2]

if vraag == "bewaren-run":
    print(next(s for s in stappen if s.get("id") == "bewaren")["run"])
elif vraag == "cache-stappen":
    # Per stap: pad, soort (bewaren/herstellen), if, sleutel, en of er restore-keys zijn.
    for s in stappen:
        uses = s.get("uses", "")

        if not uses.startswith("actions/cache"):
            continue

        soort = "herstellen" if uses.startswith("actions/cache/restore@") else "bewaren"
        met = s.get("with", {})
        print("\t".join([met["path"], soort, s.get("if", ""), met["key"], str("restore-keys" in met)]))
PY
}

# --- het bewaarbesluit -------------------------------------------------------------------------

BEWAREN_RUN=$(yaml_veld bewaren-run)

[ -n "$BEWAREN_RUN" ] || { echo "FAIL: stap 'bewaren' heeft geen run — deze suite meet niets." >&2; exit 1; }

besluit() {
  local uit="$WERKMAP/output"
  : > "$uit"
  REF=$1 EVENT=$2 GITHUB_OUTPUT=$uit bash -euo pipefail -c "$BEWAREN_RUN"
  cat "$uit"
}

verwacht_besluit() {
  local ref=$1 event=$2 verwacht=$3 kreeg

  kreeg=$(besluit "$ref" "$event")

  if [ "$kreeg" = "ja=$verwacht" ]; then
    ok "bewaren bij ref='$ref' event='$event' is $verwacht"
  else
    fout "bewaren bij ref='$ref' event='$event': verwachtte ja=$verwacht, kreeg '$kreeg'"
  fi
}

verwacht_besluit refs/heads/main push true
verwacht_besluit refs/heads/main schedule true
verwacht_besluit refs/heads/main workflow_dispatch true

# Ref main, maar PR-code kan meedraaien.
verwacht_besluit refs/heads/main pull_request_target false
verwacht_besluit refs/heads/main workflow_run false
verwacht_besluit refs/heads/main merge_group false

verwacht_besluit refs/heads/gh-readonly-queue/main/pr-1-abc merge_group false
verwacht_besluit refs/pull/1/merge pull_request false

# Lijkt op main, is het niet.
verwacht_besluit refs/heads/mainx push false
verwacht_besluit refs/heads/feature/main push false
verwacht_besluit refs/tags/main push false
verwacht_besluit "" "" false

# --- bewaar- en herstelstappen horen bij elkaar ------------------------------------------------

CACHE_STAPPEN=$(yaml_veld cache-stappen)
paden=$(cut -f1 <<<"$CACHE_STAPPEN" | sort -u)

# Twee paden: de repository en de wrapper. Minder betekent dat er een is weggevallen of
# samengevoegd, en dan meet de controle per pad hieronder minder dan hij lijkt.
if [ "$(wc -l <<<"$paden")" -eq 2 ]; then
  ok "de action cachet precies twee paden"
else
  fout "verwachtte twee gecachete paden, kreeg: $(tr '\n' ' ' <<<"$paden")"
fi

while IFS= read -r pad; do
  stappen=$(awk -F'\t' -v p="$pad" '$1 == p' <<<"$CACHE_STAPPEN")
  bewaar=$(awk -F'\t' '$2 == "bewaren"' <<<"$stappen")
  herstel=$(awk -F'\t' '$2 == "herstellen"' <<<"$stappen")

  if [ "$(grep -c . <<<"$bewaar")" -eq 1 ] && [ "$(grep -c . <<<"$herstel")" -eq 1 ]; then
    ok "$pad: precies één bewaar- en één herstelstap"
  else
    fout "$pad: verwachtte één bewaar- en één herstelstap — $stappen"
    continue
  fi

  if [ "$(cut -f4 <<<"$bewaar")" = "$(cut -f4 <<<"$herstel")" ]; then
    ok "$pad: bewaren en herstellen gebruiken dezelfde sleutel"
  else
    fout "$pad: sleutels wijken af — bewaren '$(cut -f4 <<<"$bewaar")', herstellen '$(cut -f4 <<<"$herstel")'"
  fi

  # Precies één van de twee draait: de herstelstap is de ontkenning van de bewaarstap.
  if [ "$(cut -f3 <<<"$bewaar")" = "steps.bewaren.outputs.ja == 'true'" ] \
    && [ "$(cut -f3 <<<"$herstel")" = "steps.bewaren.outputs.ja != 'true'" ]; then
    ok "$pad: bewaren en herstellen hangen complementair aan het bewaarbesluit"
  else
    fout "$pad: de if's zijn geen complement — '$(cut -f3 <<<"$bewaar")' en '$(cut -f3 <<<"$herstel")'"
  fi

  # Met restore-keys zou main de vorige pom-stand meenemen en de cache ongemerkt laten groeien.
  if [ "$(cut -f5 <<<"$bewaar")" = "False" ]; then
    ok "$pad: de bewaarstap heeft geen restore-keys"
  else
    fout "$pad: de bewaarstap heeft restore-keys"
  fi
done <<<"$paden"

if [ "$fails" -eq 0 ]; then
  echo "Alle tests geslaagd."
fi

echo "ASSERTIES=$geslaagd"

if [ "$fails" -ne 0 ]; then
  echo "$fails assertie(s) gefaald." >&2
  exit 1
fi
