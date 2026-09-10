#!/usr/bin/env bash
# Zet een preview-deployment en zijn cross-domain-regels klaar in het projectbestand, zonder uit te
# rollen. De deploy-actie die daarna draait, vindt bij zijn eerste uitrol alles al op zijn plek.
#
# Waarom dit bestaat: Operations Manager lost een cross-domain-regel op op het moment dat hij de
# deployment rendert. Bestaat de peer-deployment dan nog niet, dan slaat hij de regel over
# (`resolve.py`: "deployment ... not found in project ..., skipped") en rendert hij hem niet opnieuw
# zodra die peer er wél is. Een nieuwe preview ontstaat in drie projecten en noemt in elk de andere
# twee, dus wie ze één voor één uitrolt, laat bij de eerste de netwerkregels weg. Daar komt bij dat
# een gekloonde preview de regels van `test` erft, met `test` als peer: de console van de magazijnen
# bereikt dan de sessiecache niet, wordt nooit gezond, en de deploy loopt vast op de sync-wacht van
# Operations Manager (300 s).
#
# Met `rollout=false` schrijft Operations Manager een wijziging alleen weg. De resolver leest het
# projectbestand, dus een deployment die zo is aangemaakt, telt voor de andere projecten al als
# bestaand.
#
# Alleen wat ontbreekt: een bestaande deployment wordt niet opnieuw aangeboden, en regels die al
# goed staan worden niet opnieuw gezet. Een uitgestelde wijziging blijft in Operations Manager als
# "wacht op uitrol" meetellen tot iemand het hele project herverwerkt, ook nadat de deploy hem heeft
# uitgerold. Een volgende commit hoeft niets klaar te zetten: zijn deploy draagt een nieuwe
# image-tag, rendert de deployment dus opnieuw, en de peers bestaan dan al.
#
# Gebruik:
#   ZAD_API_KEY=... preview-klaarzetten.sh <project> <deployment> <kloonbron> <componenten-json> \
#     inbound|outbound <regel>...
#
# <componenten-json> is de lijst die ook de deploy-actie krijgt: [{"name": ..., "image": ...}].
# Operations Manager neemt bij het aanmaken alleen de meegegeven componenten over en kopieert ze
# niet van de kloonbron.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly HERE
readonly STANDAARD_API_URL='https://operations-manager.rig.prd1.gn2.quattro.rijksapps.nl/api'

# Overschrijfbaar zodat de unittests het HTTP-verkeer kunnen onderscheppen zonder netwerk.
CURL=${ZAD_CURL:-curl}

fout() {
  echo "::error::$*" >&2

  exit 1
}

# De body voor `:upsert-deployment`, uit de componentenlijst van de deploy-actie. Faalt op alles wat
# geen niet-lege lijst met een naam en image per component is: een preview die met een halve lijst
# wordt aangemaakt, mist die componenten ook bij de uitrol erna.
upsert_body() {
  local deployment=$1 bron=$2 componenten=$3

  jq -c --arg d "$deployment" --arg b "$bron" '
    if type != "array" or length == 0 then error("geen niet-lege lijst")
    elif any(.[]; (.name | type) != "string" or .name == "" or (.image | type) != "string" or .image == "")
      then error("component zonder name of image")
    else {deploymentName: $d, cloneFrom: $b, components: [.[] | {reference: .name, image: .image}]}
    end' <<<"$componenten" 2>/dev/null
}

# 0 = de deployment bestaat, 1 = hij bestaat niet, 2 = dat is niet vast te stellen.
#
# De lijst en niet het item: een 404 op een item-URL betekent ook "verkeerd project" of "verkeerd
# pad". En de kloonbron moet in die lijst staan, anders is het antwoord geen bruikbare meting — een
# lege lijst zou dan als "bestaat niet" lezen, en het aanmaken zou daarna toch falen.
deployment_bestaat() {
  local api_url=$1 api_key=$2 project=$3 deployment=$4 bron=$5 lijst uitkomst

  lijst=$("$CURL" -sf -H "X-API-Key: $api_key" "$api_url/v2/projects/$project/deployments") || return 2

  uitkomst=$(jq -r --arg d "$deployment" --arg b "$bron" '
    if (.deployments | type) != "array" then "onleesbaar"
    elif ([.deployments[].name] | index($b)) == null then "onleesbaar"
    elif any(.deployments[]; .name == $d) then "ja"
    else "nee"
    end' <<<"$lijst" 2>/dev/null) || return 2

  case "$uitkomst" in
    ja) return 0 ;;
    nee) return 1 ;;
    *) return 2 ;;
  esac
}

# Wacht op de taak en eist dat hij níet heeft uitgerold. Rolt Operations Manager toch uit — een
# API die de vlag negeert of een pad dat hem niet kent — dan staat de preview weer vóór zijn regels
# in het cluster, en eindigt de deploy na 300 s op precies de time-out die dit script voorkomt.
wacht_op_uitgestelde_taak() {
  local api_url=$1 api_key=$2 taak=$3 antwoord status verwerking

  for _ in $(seq 120); do
    antwoord=$("$CURL" -sf -H "X-API-Key: $api_key" "$api_url/tasks/$taak") \
      || fout "Taak $taak niet op te vragen; of de deployment klaarstaat is onbekend."

    status=$(jq -r '.status // ""' <<<"$antwoord" 2>/dev/null) || status=""

    case "$status" in
      completed)
        verwerking=$(jq -r '.result.processing.status // "ontbreekt"' <<<"$antwoord" 2>/dev/null) \
          || verwerking=onleesbaar

        [ "$verwerking" = skipped ] \
          || fout "Taak $taak rolde uit (verwerking: $verwerking) terwijl rollout=false gevraagd was."

        return 0
        ;;
      failed | cancelled)
        fout "Taak $taak eindigde als '$status': $(jq -r '.error_message // .result.error // ""' <<<"$antwoord" 2>/dev/null)"
        ;;
    esac

    sleep 1
  done

  fout "Taak $taak was na twee minuten nog niet klaar; de deployment staat mogelijk niet klaar."
}

main() {
  local project=${1:-} deployment=${2:-} bron=${3:-} componenten=${4:-} richting=${5:-}
  [ "$#" -ge 5 ] && shift 5 || shift "$#"

  [ -n "$project" ] || fout "Geen project opgegeven."
  [ -n "$deployment" ] || fout "Geen deployment opgegeven."
  [ -n "$bron" ] || fout "Geen kloonbron opgegeven."

  case "$richting" in
    inbound | outbound) ;;
    *) fout "Richting moet inbound of outbound zijn, was '$richting'." ;;
  esac

  [ "$#" -gt 0 ] || fout "Geen regelnaam opgegeven."

  local api_key=${ZAD_API_KEY:-}
  [ -n "$api_key" ] || fout "ZAD_API_KEY ontbreekt; zonder sleutel valt er niets klaar te zetten."

  local api_url=${ZAD_API_URL:-$STANDAARD_API_URL}

  # Vóór het eerste API-verkeer, zodat een kapotte lijst niets half achterlaat.
  local body
  body=$(upsert_body "$deployment" "$bron" "$componenten") \
    || fout "De componentenlijst is geen niet-lege JSON-lijst met een name en image per component."

  local rc=0
  deployment_bestaat "$api_url" "$api_key" "$project" "$deployment" "$bron" || rc=$?

  case "$rc" in
    0)
      echo "$project/$deployment bestaat al; niet opnieuw aangeboden."
      ;;
    1)
      local antwoord taak

      antwoord=$("$CURL" -sf -X POST \
        -H "X-API-Key: $api_key" -H 'Content-Type: application/json' \
        "$api_url/v2/projects/$project/:upsert-deployment?rollout=false" -d "$body") \
        || fout "Het aanmaken van $project/$deployment is geweigerd."

      taak=$(jq -r '.task_id // ""' <<<"$antwoord" 2>/dev/null) || taak=""
      [ -n "$taak" ] || fout "Geen taak-id in het antwoord van de API: $antwoord"

      wacht_op_uitgestelde_taak "$api_url" "$api_key" "$taak"

      echo "$project/$deployment aangemaakt zonder uitrol (taak $taak)."
      ;;
    *)
      fout "De deploymentlijst van $project is niet te lezen of mist de kloonbron '$bron'; of $deployment bestaat, is onbekend."
      ;;
  esac

  ZAD_ROLLOUT=false "$HERE/cross-domain-preview.sh" zet "$project" "$deployment" "$richting" "$@"
}

# Alleen uitvoeren bij directe aanroep, zodat de unittests de functies kunnen sourcen.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
