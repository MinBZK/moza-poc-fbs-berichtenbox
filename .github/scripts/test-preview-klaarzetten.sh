#!/usr/bin/env bash
# Unittests voor preview-klaarzetten.sh. Geen netwerk: een curl-stub op het pad schrijft elke
# aanroep weg en geeft per endpoint een geregisseerd antwoord. cross-domain-preview.sh draait als
# echt subproces mee, met dezelfde stub.
#
# Wat hier bewaakt wordt: dat een nieuwe preview klaarstaat zonder uit te rollen, dat een preview
# die al klaarstaat niets nieuws in Operations Manager achterlaat, en dat elke tak die "klaar" zou
# kunnen melden over iets wat niet klaarstaat, rood wordt.

set -euo pipefail

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
readonly REPO_ROOT
readonly SCRIPT="$REPO_ROOT/.github/scripts/preview-klaarzetten.sh"
readonly DEPLOY_YML="$REPO_ROOT/.github/workflows/deploy.yml"
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

bevat_niet() {
  local wat=$1 naald=$2 hooiberg=$3

  case "$hooiberg" in
    *"$naald"*) fout "$wat — '$naald' staat er toch in: $hooiberg" ;;
    *) ok "$wat" ;;
  esac
}

readonly COMPONENTEN='[{"name":"magazijna","image":"ghcr.io/o/fbs-berichtenmagazijn:pr-7-abc"},{"name":"democonsole","image":"ghcr.io/o/fbs-demo-console:pr-7-abc"}]'
readonly REGELS=(democonsole-naar-redis democonsole-naar-toxiproxy-redis)

readonly LIJST_ZONDER='{"deployments":[{"name":"test"},{"name":"pr-3"}]}'
readonly LIJST_MET='{"deployments":[{"name":"test"},{"name":"pr-7"}]}'
readonly UITGESTELD='{"task_id":"t-1","status":"completed","result":{"status":"success","processing":{"status":"skipped","reason":"rollout_disabled"}}}'
readonly PROJECTREGELS='{"target":"project","config":{"outbound":[{"name":"democonsole-naar-redis"},{"name":"democonsole-naar-toxiproxy-redis"}]}}'
readonly GEZET='{"target":"deployment","deployment":"pr-7","config":{"outbound":[{"name":"democonsole-naar-redis","to":{"deployment":"pr-7"}},{"name":"democonsole-naar-toxiproxy-redis","to":{"deployment":"pr-7"}}]}}'
readonly GEKLOOND='{"target":"deployment","deployment":"pr-7","config":{"outbound":[{"name":"democonsole-naar-redis","to":{"deployment":"test"}},{"name":"democonsole-naar-toxiproxy-redis","to":{"deployment":"test"}}]}}'

werkmap=$(mktemp -d)
trap 'rm -rf "$werkmap"' EXIT

configuratie() {
  local IFS=,

  printf '{"configurations":[%s]}' "$*"
}

# De antwoorden staan in bestanden, zodat de quotes van JSON niet door een heredoc hoeven. Met
# `lijst_rc` doet de stub alsof curl bij het ophalen van de deploymentlijst zelf faalde.
maak_stub() {
  local lijst=$1 upsert=$2 taak=$3 config=$4 lijst_rc=${5:-0}

  printf '%s' "$lijst" >"$werkmap/lijst"
  printf '%s' "$upsert" >"$werkmap/upsert"
  printf '%s' "$taak" >"$werkmap/taak"
  printf '%s' "$config" >"$werkmap/config"
  rm -f "$werkmap/aanroepen" "$werkmap/bodies"

  cat >"$werkmap/curl" <<STUB
#!/usr/bin/env bash
printf '%s\n' "\$*" >>"$werkmap/aanroepen"
for arg in "\$@"; do
  case "\$arg" in
    '{'*) printf '%s\n' "\$arg" >>"$werkmap/bodies" ;;
  esac
done

case "\$*" in
  *:upsert-deployment*) cat "$werkmap/upsert" ;;
  */tasks/*) cat "$werkmap/taak" ;;
  */config/deployment/*) printf '{"task_id":"t-2"}' ;;
  *cross-domain-access/config*) cat "$werkmap/config" ;;
  */deployments*) cat "$werkmap/lijst"; exit $lijst_rc ;;
  *) exit 22 ;;
esac
STUB
  chmod +x "$werkmap/curl"
}

# Zet $RC, $UITVOER en $AANROEPEN, zodat een test op alle drie kan toetsen.
draai() {
  RC=0
  UITVOER=$(PATH="$werkmap:$PATH" ZAD_API_KEY=sleutel ZAD_API_URL=http://api.test bash "$SCRIPT" "$@" 2>&1) || RC=$?
  AANROEPEN=$(cat "$werkmap/aanroepen" 2>/dev/null || true)
}

regelnummer() {
  grep -n -F "$1" "$werkmap/aanroepen" 2>/dev/null | head -1 | cut -d: -f1
}

# --- een nieuwe preview ---------------------------------------------------------------------------
maak_stub "$LIJST_ZONDER" '{"task_id":"t-1","status":"accepted"}' "$UITGESTELD" "$(configuratie "$PROJECTREGELS")"
draai mpfm-w3h pr-7 test "$COMPONENTEN" outbound "${REGELS[@]}"

gelijk "een nieuwe preview staat klaar" 0 "$RC"
bevat "hij wordt aangemaakt zonder uitrol" '/v2/projects/mpfm-w3h/:upsert-deployment?rollout=false' "$AANROEPEN"
gelijk "met naam, kloonbron en de componenten in de vorm van de API" \
  '{"deploymentName":"pr-7","cloneFrom":"test","components":[{"reference":"magazijna","image":"ghcr.io/o/fbs-berichtenmagazijn:pr-7-abc"},{"reference":"democonsole","image":"ghcr.io/o/fbs-demo-console:pr-7-abc"}]}' \
  "$(grep deploymentName "$werkmap/bodies" | head -1)"
bevat "zijn regels worden gezet zonder uitrol" '/config/deployment/pr-7/outbound?rollout=false' "$AANROEPEN"

# Een patch op een deployment die nog niet in het projectbestand staat, heeft niets om aan te hangen.
aanmaken=$(regelnummer ':upsert-deployment')
patchen=$(regelnummer '/config/deployment/')

if [ -n "$aanmaken" ] && [ -n "$patchen" ] && [ "$aanmaken" -lt "$patchen" ]; then
  ok "het aanmaken gaat vóór het zetten van de regels"
else
  fout "het aanmaken gaat niet vóór het zetten van de regels (aanmaken: '$aanmaken', patchen: '$patchen')"
fi

# --- een preview die al klaarstaat ---------------------------------------------------------------
# Elke uitgestelde wijziging blijft in Operations Manager als "wacht op uitrol" staan tot iemand het
# hele project herverwerkt. Een push op een bestaande preview hoort er dus geen enkele achter te
# laten.
maak_stub "$LIJST_MET" '{"task_id":"t-1"}' "$UITGESTELD" "$(configuratie "$PROJECTREGELS" "$GEZET")"
draai mpfm-w3h pr-7 test "$COMPONENTEN" outbound "${REGELS[@]}"

gelijk "een preview die al klaarstaat is geen fout" 0 "$RC"
bevat_niet "hij wordt niet opnieuw aangeboden" 'upsert-deployment' "$AANROEPEN"
bevat_niet "en zijn regels worden niet opnieuw gezet" '/config/deployment/' "$AANROEPEN"

# Een preview die onder de oude volgorde is aangemaakt, draagt de regels van `test`. Die moeten
# alsnog om, anders blijft de console onbereikbaar voor zijn eigen sessiecache.
maak_stub "$LIJST_MET" '{"task_id":"t-1"}' "$UITGESTELD" "$(configuratie "$PROJECTREGELS" "$GEKLOOND")"
draai mpfm-w3h pr-7 test "$COMPONENTEN" outbound "${REGELS[@]}"

gelijk "een bestaande preview met de regels van de kloonbron staat daarna klaar" 0 "$RC"
bevat_niet "hij wordt niet opnieuw aangeboden" 'upsert-deployment' "$AANROEPEN"
bevat "maar zijn regels worden omgezet, zonder uitrol" '/config/deployment/pr-7/outbound?rollout=false' "$AANROEPEN"

# --- stil falen ------------------------------------------------------------------------------------
# Rolt Operations Manager toch uit, dan staat de preview weer vóór zijn regels in het cluster — en
# loopt de deploy vast op precies de time-out die dit script voorkomt.
maak_stub "$LIJST_ZONDER" '{"task_id":"t-1"}' \
  '{"task_id":"t-1","status":"completed","result":{"status":"success","processing":{"status":"completed"}}}' \
  "$(configuratie "$PROJECTREGELS")"
draai mpfm-w3h pr-7 test "$COMPONENTEN" outbound "${REGELS[@]}"

gelijk "een taak die tóch uitrolde stopt het script" 1 "$RC"
bevat "en zegt dat hij uitrolde" 'rolde uit' "$UITVOER"
bevat_niet "de regels worden dan niet meer gezet" '/config/deployment/' "$AANROEPEN"

maak_stub "$LIJST_ZONDER" '{"task_id":"t-1"}' '{"task_id":"t-1","status":"failed","error_message":"kloonbron onvindbaar"}' \
  "$(configuratie "$PROJECTREGELS")"
draai mpfm-w3h pr-7 test "$COMPONENTEN" outbound "${REGELS[@]}"

gelijk "een gefaalde taak stopt het script" 1 "$RC"
bevat "en noemt de eindtoestand" "eindigde als 'failed'" "$UITVOER"
bevat "met de reden van Operations Manager" 'kloonbron onvindbaar' "$UITVOER"

# HTTP 200 met een foutmelding: curl klaagt niet, dus zonder de taak-id-controle zou het script
# hier "klaar" melden over een deployment die nooit is aangemaakt.
maak_stub "$LIJST_ZONDER" '{"detail":"nee"}' "$UITGESTELD" "$(configuratie "$PROJECTREGELS")"
draai mpfm-w3h pr-7 test "$COMPONENTEN" outbound "${REGELS[@]}"

gelijk "een antwoord zonder taak-id stopt het script" 1 "$RC"
bevat "en zegt waaróm" 'Geen taak-id' "$UITVOER"

# --- de deploymentlijst ------------------------------------------------------------------------------
# Een lijst die niet te lezen is, mag niet als "bestaat nog niet" doorgaan: dan zou het script een
# deployment aanbieden op grond van een meting die niets zegt.
for geval in "curl-fout" '<html>storing</html>' 'null' '{"deployments":"geen lijst"}' '{"deployments":[{"name":"pr-3"}]}'; do
  if [ "$geval" = curl-fout ]; then
    maak_stub "$LIJST_ZONDER" '{"task_id":"t-1"}' "$UITGESTELD" "$(configuratie "$PROJECTREGELS")" 22
  else
    maak_stub "$geval" '{"task_id":"t-1"}' "$UITGESTELD" "$(configuratie "$PROJECTREGELS")"
  fi

  draai mpfm-w3h pr-7 test "$COMPONENTEN" outbound "${REGELS[@]}"

  if [ "$RC" -eq 1 ] && [[ $AANROEPEN != *upsert-deployment* ]]; then
    ok "een onbruikbare deploymentlijst ($geval) stopt het script zonder iets aan te maken"
  else
    fout "een onbruikbare deploymentlijst ($geval) gaf rc=$RC, aanroepen: $AANROEPEN"
  fi
done

# --- de componentenlijst -----------------------------------------------------------------------------
# Vóór elk API-verkeer: een preview die met een halve lijst wordt aangemaakt, mist die componenten
# ook bij de uitrol erna.
for kapot in 'geen json' '[]' '{}' '[{"name":"magazijna"}]' '[{"name":"","image":"x"}]' '[{"name":"a","image":7}]'; do
  maak_stub "$LIJST_ZONDER" '{"task_id":"t-1"}' "$UITGESTELD" "$(configuratie "$PROJECTREGELS")"
  draai mpfm-w3h pr-7 test "$kapot" outbound "${REGELS[@]}"

  if [ "$RC" -eq 1 ] && [ -z "$AANROEPEN" ]; then
    ok "een kapotte componentenlijst ('$kapot') stopt het script vóór elke aanroep"
  else
    fout "een kapotte componentenlijst ('$kapot') gaf rc=$RC, aanroepen: $AANROEPEN"
  fi
done

# --- argumentcontrole ------------------------------------------------------------------------------
maak_stub "$LIJST_ZONDER" '{"task_id":"t-1"}' "$UITGESTELD" "$(configuratie "$PROJECTREGELS")"

draai mpfm-w3h pr-7 test "$COMPONENTEN" zijwaarts regel
gelijk "een onbekende richting stopt het script" 1 "$RC"

draai mpfm-w3h pr-7 test "$COMPONENTEN" outbound
gelijk "zonder regelnaam stopt het script" 1 "$RC"

draai mpfm-w3h pr-7 '' "$COMPONENTEN" outbound regel
gelijk "zonder kloonbron stopt het script" 1 "$RC"

draai mpfm-w3h '' test "$COMPONENTEN" outbound regel
gelijk "zonder deployment stopt het script" 1 "$RC"

RC=0
PATH="$werkmap:$PATH" ZAD_API_KEY='' ZAD_API_URL=http://api.test \
  bash "$SCRIPT" mpfm-w3h pr-7 test "$COMPONENTEN" outbound regel >/dev/null 2>&1 || RC=$?
gelijk "zonder ZAD_API_KEY stopt het script" 1 "$RC"

# --- entrypoint --------------------------------------------------------------------------------------
# De workflow roept het script zonder `bash` ervoor aan, en het script roept cross-domain-preview.sh
# en zad-deployment-bestaat.sh op dezelfde manier aan.
for uitvoerbaar in preview-klaarzetten.sh cross-domain-preview.sh zad-deployment-bestaat.sh; do
  if [ -x "$REPO_ROOT/.github/scripts/$uitvoerbaar" ]; then
    ok "$uitvoerbaar is uitvoerbaar"
  else
    fout "$uitvoerbaar is niet uitvoerbaar"
  fi
done

uitvoer=$(ZAD_API_KEY=sleutel bash -c "source '$SCRIPT'" 2>&1)
gelijk "sourcen voert main niet uit" "" "$uitvoer"

# --- de aansluiting op de workflows ------------------------------------------------------------------
# Het script lost alleen iets op als de workflow het op de goede plek en met de goede lijst aanroept.
# Deze controles lezen deploy.yml met een YAML-parser: GitHub leest het bestand ook zo.
while IFS= read -r regel; do
  case "$regel" in
    OK:*) ok "${regel#OK:}" ;;
    FOUT:*) fout "${regel#FOUT:}" ;;
  esac
done < <(python3 - "$DEPLOY_YML" "$CLEANUP_YML" <<'PY' 2>&1 || echo "FOUT:de workflowcontrole zelf viel om"
import sys

import yaml


def jobs(pad):
    with open(pad, encoding="utf-8") as bestand:
        return yaml.safe_load(bestand)["jobs"]


def meld(goed, tekst):
    print(("OK:" if goed else "FOUT:") + tekst)


def legs(job):
    include = (((job or {}).get("strategy") or {}).get("matrix") or {}).get("include") or []
    return sorted(tuple(leg.get(veld) for veld in ("naam", "project", "key")) for leg in include)


deploy, cleanup = jobs(sys.argv[1]), jobs(sys.argv[2])
klaar = deploy.get("preview-klaarzetten") or {}
klaar_legs = legs(klaar)

meld(len(klaar_legs) == 3, "de klaarzet-matrix in deploy.yml heeft drie legs")
# Een project dat alleen hier staat, laat na elke gesloten PR een preview achter.
meld(klaar_legs == legs(cleanup.get("cleanup-preview-zad")), "de klaarzet-matrix kent dezelfde projecten als de opruim-matrix")

aanroepen = [stap for stap in klaar.get("steps") or [] if "preview-klaarzetten.sh" in str(stap.get("run", ""))]
meld(len(aanroepen) == 1, "preview-klaarzetten roept het script aan")

env = (aanroepen[0].get("env") if aanroepen else None) or {}
meld(
    env.get("COMPONENTEN") == "${{ needs.meta.outputs[format('componenten-{0}', matrix.naam)] }}",
    "de klaarzetting leest de componenten per leg uit meta",
)

meta_outputs = (deploy.get("meta") or {}).get("outputs") or {}
magazijnen = deploy.get("deploy-preview-magazijnen") or {}

for naam, *_ in klaar_legs:
    meld(
        meta_outputs.get(f"componenten-{naam}") == f"${{{{ steps.componenten.outputs.{naam} }}}}",
        f"meta publiceert de componenten van {naam}",
    )

    job = deploy.get(f"deploy-preview-{naam}") or {}
    uitrol = [stap for stap in job.get("steps") or [] if "zad-actions/deploy" in str(stap.get("uses", ""))]
    components = (uitrol[0].get("with") or {}).get("components") if uitrol else None

    # Operations Manager neemt bij het aanmaken alleen de meegegeven componenten over: een lijst die
    # uiteenloopt, levert een preview op waarin de deploy een component zoekt die er niet is.
    meld(
        components == f"${{{{ needs.meta.outputs.componenten-{naam} }}}}",
        f"deploy-preview-{naam} rolt dezelfde componentenlijst uit",
    )
    meld(
        "preview-klaarzetten" in (job.get("needs") or [])
        and "needs.preview-klaarzetten.result == 'success'" in str(job.get("if", "")),
        f"deploy-preview-{naam} wacht op een geslaagde klaarzetting",
    )

# De console van de magazijnen wordt pas gezond als de inbound-policies in de twee andere projecten
# in het cluster staan.
for peer in ("deploy-preview-uitvraag", "deploy-preview-externe-stubs"):
    meld(
        peer in (magazijnen.get("needs") or []) and f"needs.{peer}.result == 'success'" in str(magazijnen.get("if", "")),
        f"de magazijnen-deploy wacht op een geslaagde {peer}",
    )
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
