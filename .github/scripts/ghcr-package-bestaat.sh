#!/usr/bin/env bash
# Stelt vast of een container-package in ghcr bestaat, voor zover dit token het kan zien.
#
# De exitcode is de uitkomst: 0 = bestaat, 1 = bestaat niet, 2 = niet vast te stellen. Wie op
# "bestaat niet" iets overslaat, moet 2 als "misschien wel" behandelen.
#
# Nodig omdat een 404 op de versies van een package zowel "nooit gepusht" betekent als "typefout" of
# "te weinig rechten". De packagelijst van de organisatie is wél te beoordelen: komt die terug, met
# het referentiepackage erin, dan is het een lijst die dit token kan lezen en geen leeg of afgewezen
# antwoord.
#
# Die lijst toont alleen packages waar deze repository bij mag. Dat volstaat om "geen versies van
# deze repository" te concluderen: deploy.yml pusht elke image met het GITHUB_TOKEN van deze
# repository, en dat kan alleen naar een package waar deze repository schrijfrechten op heeft — en
# dus ook leesrechten. Een package dat hier onzichtbaar is, draagt daarom geen versies van ons.
# Wordt die toegang ingetrokken nadat er al versies staan, dan blijven die hier ongezien achter;
# dat valt op doordat de volgende push van deploy.yml naar dat package faalt.
#
# Gebruik:
#   GH_TOKEN=... ghcr-package-bestaat.sh <organisatie> <package> [referentie, standaard fbs-berichtenuitvraag]

set -euo pipefail

onbepaald() {
  echo "$*" >&2

  exit 2
}

main() {
  local org=${1:-} package=${2:-} referentie=${3:-fbs-berichtenuitvraag}

  [ -n "$org" ] && [ -n "$package" ] \
    || onbepaald "Gebruik: $0 <organisatie> <package> [referentie]"

  local lijst uitkomst

  # Ruw en niet via `--jq`: met `--paginate` levert gh dan per pagina een array, en een pagina die
  # geen array is, moet als kapotte meting opvallen in plaats van als lege pagina.
  lijst=$(gh api --paginate "orgs/$org/packages?package_type=container&per_page=100") \
    || onbepaald "De packagelijst van $org is niet op te vragen."

  # Drie uitkomsten in plaats van `jq -e`: daarbij is een parsefout niet te onderscheiden van
  # 'false'. Zonder het referentiepackage is de lijst geen bewijs: dan ziet dit token niet wat het
  # zou moeten zien.
  uitkomst=$(jq -rs --arg p "$package" --arg r "$referentie" '
    if length == 0 or any(.[]; type != "array") then "onleesbaar"
    else (add | map(objects | .name)) as $namen
      | if ($namen | index($r)) == null then "onleesbaar"
        elif ($namen | index($p)) != null then "ja"
        else "nee"
        end
    end' <<<"$lijst" 2>/dev/null) || uitkomst=onleesbaar

  case "$uitkomst" in
    ja)
      echo "$package bestaat in ghcr."

      return 0
      ;;
    nee)
      echo "$package bestaat (nog) niet in ghcr."

      return 1
      ;;
    *)
      onbepaald "De packagelijst van $org is onleesbaar of mist '$referentie'; of $package bestaat, is onbekend."
      ;;
  esac
}

# Alleen uitvoeren bij directe aanroep, zodat sourcen geen aanroep naar GitHub doet.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
