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
elif vraag == "bewaren-env":
    env = next(s for s in stappen if s.get("id") == "bewaren").get("env", {})
    print(env.get("REF", ""))
    print(env.get("EVENT", ""))
elif vraag == "settings-run":
    print(stappen[0]["run"])
elif vraag == "cache-stappen":
    # Per stap: pad, soort, if, sleutel, restore-keys (gescheiden door |). Exact op de naam vóór
    # `@`: een `actions/cache/save` bewaart wel maar herstelt niet, en mag niet als "bewaren" tellen.
    soorten = {"actions/cache": "bewaren", "actions/cache/restore": "herstellen"}

    for s in stappen:
        naam = s.get("uses", "").split("@", 1)[0]

        if not naam.startswith("actions/cache"):
            continue

        if naam not in soorten:
            sys.exit(f"onbekende cache-action {naam!r} — deze suite kent alleen {sorted(soorten)}")

        met = s.get("with", {})
        restore = "|".join(regel.strip() for regel in str(met.get("restore-keys", "")).splitlines() if regel.strip())
        print("\t".join([met["path"], soorten[naam], s.get("if", ""), met["key"], restore]))
PY
}

# --- het bewaarbesluit -------------------------------------------------------------------------

BEWAREN_RUN=$(yaml_veld bewaren-run)

[ -n "$BEWAREN_RUN" ] || { echo "FAIL: stap 'bewaren' heeft geen run — deze suite meet niets." >&2; exit 1; }

# Alleen wat in $GITHUB_OUTPUT landt telt; stdout weg, anders slaagt een besluit dat naar stdout
# in plaats van naar de output schrijft, terwijl `steps.bewaren.outputs.ja` op de runner leeg blijft.
besluit() {
  local uit="$WERKMAP/output"
  : > "$uit"
  REF=$1 EVENT=$2 GITHUB_OUTPUT=$uit bash -euo pipefail -c "$BEWAREN_RUN" > /dev/null
  cat "$uit"
}

# De run krijgt REF en EVENT uit de `env:` van de stap; een verkeerde context daar zet het besluit
# stil altijd op false, en actionlint leest een composite action.yml niet.
BEWAREN_ENV=$(yaml_veld bewaren-env)

# shellcheck disable=SC2016 # de ${{ }} is hier letterlijke tekst uit de action, geen shell-expansie
if [ "$BEWAREN_ENV" = $'${{ github.ref }}\n${{ github.event_name }}' ]; then
  ok "het bewaarbesluit leest github.ref en github.event_name"
else
  fout "de env van stap 'bewaren' is niet REF=github.ref, EVENT=github.event_name — $BEWAREN_ENV"
fi

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
  if [ -z "$(cut -f5 <<<"$bewaar")" ]; then
    ok "$pad: de bewaarstap heeft geen restore-keys"
  else
    fout "$pad: de bewaarstap heeft restore-keys"
  fi
done <<<"$paden"

# --- de opbouw van de sleutels -----------------------------------------------------------------

# shellcheck disable=SC2016 # ${{ }} is letterlijke tekst uit de action
REPO_PREFIX='maven-repo-${{ runner.os }}-${{ inputs.scope }}-'
repo_herstel=$(awk -F'\t' '$1 == "~/.m2/repository" && $2 == "herstellen"' <<<"$CACHE_STAPPEN")
repo_sleutel=$(cut -f4 <<<"$repo_herstel")
wrapper_sleutel=$(awk -F'\t' '$1 == "~/.m2/wrapper" && $2 == "herstellen"' <<<"$CACHE_STAPPEN" | cut -f4)

# Zonder scope delen alle jobs één sleutel: de halfgevulde cache waar de action voor bestaat.
if [[ "$repo_sleutel" == "$REPO_PREFIX"* ]]; then
  ok "de repository-sleutel begint met os en scope"
else
  fout "de repository-sleutel begint niet met '$REPO_PREFIX' — $repo_sleutel"
fi

if [[ "$repo_sleutel" == *"'**/pom.xml'"* && "$repo_sleutel" == *".github/actions/maven-cache/settings.xml"* ]]; then
  ok "de repository-sleutel hangt aan de pom's en de settings"
else
  fout "de repository-sleutel hasht niet de pom's én de settings — $repo_sleutel"
fi

if [[ "$wrapper_sleutel" == *".mvn/wrapper/maven-wrapper.properties"* ]]; then
  ok "de wrapper-sleutel hangt aan maven-wrapper.properties"
else
  fout "de wrapper-sleutel hasht maven-wrapper.properties niet — $wrapper_sleutel"
fi

# Zonder terugval start elke PR na een pom-wijziging met een lege repository: de 429's van
# vóór deze action. Elke restore-key moet een prefix van de sleutel zijn, anders vindt hij niets.
restore_keys=$(cut -f5 <<<"$repo_herstel" | tr '|' '\n')

# Exact, in deze volgorde: eerst de eigen scope. Staat de generieke key voorop of alleen, dan krijgt
# een job na elke pom-wijziging de nieuwste cache van welke scope dan ook, vaak de halve van detekt.
# shellcheck disable=SC2016 # ${{ }} is letterlijke tekst uit de action
if [ "$restore_keys" = $'maven-repo-${{ runner.os }}-${{ inputs.scope }}-\nmaven-repo-${{ runner.os }}-' ]; then
  ok "het herstellen van de repository valt eerst terug op de eigen scope, dan op elke scope"
else
  fout "de restore-keys van de repository zijn niet [eigen scope, elke scope] — $(tr '\n' ' ' <<<"$restore_keys")"
fi

while IFS= read -r rk; do
  [ -n "$rk" ] || continue

  if [[ "$repo_sleutel" == "$rk"* ]]; then
    ok "restore-key '$rk' is een prefix van de sleutel"
  else
    fout "restore-key '$rk' is geen prefix van de sleutel '$repo_sleutel'"
  fi
done <<<"$restore_keys"

# --- de settings die de action neerzet ---------------------------------------------------------

# De stap zelf draaien, met HOME in de werkmap: zo toetst dit wat de runner krijgt, en valt een
# installatiestap die niets meer neerzet ook op.
HOME="$WERKMAP" SETTINGS="$HERE/../actions/maven-cache/settings.xml" bash -euo pipefail -c "$(yaml_veld settings-run)"

SETTINGS_FEITEN=$(python3 - "$WERKMAP/.m2/settings.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET

ns = {"s": "http://maven.apache.org/SETTINGS/1.2.0"}
wortel = ET.parse(sys.argv[1]).getroot()
actief = [e.text for e in wortel.findall("s:activeProfiles/s:activeProfile", ns)]
profielen = {p.findtext("s:id", namespaces=ns): p for p in wortel.findall("s:profiles/s:profile", ns)}

print("mirrors=" + str(wortel.find("s:mirrors", ns) is not None))
print("actief-bestaat=" + str(len(actief) == 1 and actief[0] in profielen))

profiel = profielen.get(actief[0]) if actief else None

for soort, element in (("repositories", "repository"), ("pluginRepositories", "pluginRepository")):
    repos = profiel.findall(f"s:{soort}/s:{element}", ns) if profiel is not None else []
    print(f"{soort}-volgorde=" + ",".join(r.findtext("s:id", namespaces=ns) for r in repos))
    print(f"{soort}-central=" + "".join(r.findtext("s:url", namespaces=ns) for r in repos if r.findtext("s:id", namespaces=ns) == "central"))
    print(f"{soort}-mirror=" + "".join(r.findtext("s:url", namespaces=ns) for r in repos if r.findtext("s:id", namespaces=ns) == "google-maven-central"))
    print(f"{soort}-releases=" + ",".join(r.findtext("s:releases/s:enabled", default="", namespaces=ns) for r in repos))
    print(f"{soort}-policies=" + ",".join(r.findtext("s:releases/s:checksumPolicy", default="", namespaces=ns) for r in repos))
PY
)

verwacht_feit() {
  local naam=$1 sleutel=$2 waarde=$3

  if grep -qxF "$sleutel=$waarde" <<<"$SETTINGS_FEITEN"; then
    ok "settings: $naam"
  else
    fout "settings: $naam — verwachtte $sleutel=$waarde, kreeg $(grep "^$sleutel=" <<<"$SETTINGS_FEITEN")"
  fi
}

verwacht_feit "geen <mirrors>, die zou Central vervangen" mirrors False
verwacht_feit "het actieve profiel bestaat" actief-bestaat True

for soort in repositories pluginRepositories; do
  verwacht_feit "$soort: mirror vóór Central" "$soort-volgorde" "google-maven-central,central"
  verwacht_feit "$soort: Central wijst naar repo.maven.apache.org" "$soort-central" "https://repo.maven.apache.org/maven2"
  # Een typo of `enabled false` zet de mirror stil uit: Maven valt terug op Central, de build blijft
  # groen en de 429's komen terug.
  verwacht_feit "$soort: de mirror wijst naar Google's Central-mirror" "$soort-mirror" "https://maven-central-eu.storage-download.googleapis.com/maven2"
  verwacht_feit "$soort: releases aan op beide" "$soort-releases" "true,true"
  verwacht_feit "$soort: checksumPolicy fail op beide" "$soort-policies" "fail,fail"
done

if [ "$fails" -eq 0 ]; then
  echo "Alle tests geslaagd."
fi

echo "ASSERTIES=$geslaagd"

if [ "$fails" -ne 0 ]; then
  echo "$fails assertie(s) gefaald." >&2
  exit 1
fi
