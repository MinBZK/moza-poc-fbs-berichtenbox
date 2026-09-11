#!/usr/bin/env bash
# Stelt vast of een deployment in een ZAD-project bestaat.
#
# De exitcode is de uitkomst: 0 = bestaat, 1 = bestaat niet, 2 = niet vast te stellen. Wie op
# "bestaat niet" iets overslaat, moet 2 als "misschien wel" behandelen: een onleesbaar antwoord is
# geen bewijs van afwezigheid.
#
# De lijst en niet het item: een 404 op een item-URL betekent óók "onbekend project" of "verkeerd
# pad", en dan zou een typefout als "bestaat niet" lezen. Een lijst die met HTTP 200 terugkomt, de
# verwachte vorm heeft en de baseline-deployment bevat, is een bruikbare meting. Die baseline is de
# canary: elk project houdt `test` permanent, dus een lijst zonder `test` is geen leeg project maar
# een kapotte meting.
#
# Gebruik:
#   ZAD_API_KEY=... zad-deployment-bestaat.sh <project> <deployment> [baseline, standaard test]

set -euo pipefail

readonly STANDAARD_API_URL='https://operations-manager.rig.prd1.gn2.quattro.rijksapps.nl/api'

# Overschrijfbaar zodat de unittests het HTTP-verkeer kunnen onderscheppen zonder netwerk.
CURL=${ZAD_CURL:-curl}

onbepaald() {
  echo "$*" >&2

  exit 2
}

main() {
  local project=${1:-} deployment=${2:-} baseline=${3:-test}

  [ -n "$project" ] && [ -n "$deployment" ] \
    || onbepaald "Gebruik: $0 <project> <deployment> [baseline]"

  local api_key=${ZAD_API_KEY:-}
  [ -n "$api_key" ] || onbepaald "ZAD_API_KEY ontbreekt; de deploymentlijst van $project is niet op te vragen."

  local api_url=${ZAD_API_URL:-$STANDAARD_API_URL} lijst uitkomst

  # `--max-time` zodat een stille TCP-stall niet tot de job-timeout doorloopt; `--retry` zodat een
  # 5xx-hik geen onbepaalde uitkomst oplevert.
  lijst=$("$CURL" -sSf --max-time 30 --retry 3 --retry-connrefused \
    -H "X-API-Key: $api_key" -H 'Accept: application/json' \
    "$api_url/v2/projects/$project/deployments") \
    || onbepaald "De deploymentlijst van $project is niet op te vragen."

  # Drie uitkomsten in plaats van `jq -e`: daarbij is een parsefout niet te onderscheiden van
  # 'false'. De vorm wordt afgedwongen, want `null | .x` is in jq geen fout maar `null`; een antwoord
  # dat geen object is, laat jq zelf falen en komt via `||` op "onleesbaar" uit.
  uitkomst=$(jq -r --arg d "$deployment" --arg b "$baseline" '
    if (.deployments | type) != "array" then "onleesbaar"
    elif ([.deployments[].name] | index($b)) == null then "onleesbaar"
    elif any(.deployments[]; .name == $d) then "ja"
    else "nee"
    end' <<<"$lijst" 2>/dev/null) || uitkomst=onleesbaar

  case "$uitkomst" in
    ja)
      echo "$deployment bestaat in $project."

      return 0
      ;;
    nee)
      echo "$deployment bestaat niet in $project."

      return 1
      ;;
    *)
      onbepaald "Het antwoord van $project is geen deploymentlijst met '$baseline' erin; of $deployment bestaat, is onbekend."
      ;;
  esac
}

# Alleen uitvoeren bij directe aanroep, zodat de unittests de functies kunnen sourcen.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
