#!/usr/bin/env bash
# Plaatst — of werkt bij — de comment met de preview-URL's op een PR: één comment met de URL's van
# álle ZAD-projecten die samen de preview vormen, gegroepeerd per kopje.
#
# Waarom niet de comment van zad-actions/deploy zelf: die schrijft per aanroep de HELE body en
# zoekt de bestaande comment op `startswith(<header>)`. De preview beslaat meer dan één project,
# dus zou de tweede aanroep de URL's van de eerste overschrijven; met een eigen header per project
# krijgt de PR twee comments, en matcht de kortste header ook de comment van de ander. Eén comment,
# ná de laatste deploy, is de enige vorm die alle URL's bij elkaar houdt.
#
# De header moet gelijk blijven aan die in cleanup-preview.yml: die zoekt de comment bij het
# sluiten van de PR op `startswith` van dezelfde tekst. Drift laat de comment achter op een
# gesloten PR.
#
# Gebruik:
#   GH_TOKEN=... GITHUB_REPOSITORY=owner/repo preview-comment.sh <pr-nummer> <kopje>=<json-map>...
#
# waarbij <json-map> de `urls`-uitvoer van zad-actions/deploy is: {"<component>":"https://..."}.

set -euo pipefail

readonly STANDAARD_HEADER='## 🚀 Preview Deployment'

fout() {
  echo "::error::$*" >&2

  exit 1
}

# De sectie van één project. Een lege map is hier een fout en geen lege sectie: de deploy-action
# levert `urls` alleen leeg op wanneer ze de URL's niet uit het OM-antwoord kon halen, en een
# comment die dat project stilzwijgend weglaat leest als "die hoort niet bij deze preview".
sectie() {
  local kopje=$1 urls=$2

  printf '%s' "$urls" | jq -e 'type == "object" and length > 0' >/dev/null 2>&1 \
    || fout "De URL's voor '$kopje' zijn geen gevulde JSON-map: $urls"

  printf '### %s\n\n' "$kopje"

  # Via jq en niet via een shell-lus: een component-naam of URL met een teken waar printf eigen
  # betekenis aan geeft, hoort de regel niet te kunnen vervormen.
  printf '%s' "$urls" | jq -r 'to_entries[] | "- **\(.key):** \(.value)"'

  printf '\n'
}

comment_body() {
  local header=$1
  shift

  printf '%s\n\n' "$header"
  printf 'De preview-omgeving van deze PR:\n\n'

  local paar kopje urls
  for paar in "$@"; do
    case "$paar" in
      *=*) ;;
      *) fout "Verwachtte '<kopje>=<json-map>', kreeg '$paar'." ;;
    esac

    kopje=${paar%%=*}
    urls=${paar#*=}

    [ -n "$kopje" ] || fout "Een lege sectienaam is geen sectienaam."

    sectie "$kopje" "$urls"
  done

  printf 'Deze preview wordt opgeruimd zodra de PR sluit.\n'
}

# De id's van de comments die er al staan, oudste eerst. `--paginate`, want op een PR met veel
# comments valt de preview-comment buiten de eerste pagina; zonder paginering zou deze stap er dan
# bij elke push een tweede plaatsen.
bestaande_comments() {
  local repo=$1 pr=$2 header=$3

  # De header via `--arg` i.p.v. in het jq-programma interpoleren: een quote of backslash erin zou
  # het programma anders breken.
  gh api "repos/$repo/issues/$pr/comments?per_page=100" --paginate \
    | jq -r --arg h "$header" '.[] | select(.body | startswith($h)) | .id'
}

main() {
  local pr=${1:-}
  [ "$#" -ge 1 ] && shift || true

  # Bretels om het lege PR-nummer: dat maakt het pad `/issues//comments`, en dát pad levert bij
  # GitHub de comments van de HÉLE repo. Eén routing-wijziging en deze stap zou de comment van een
  # willekeurige andere PR bijwerken.
  [[ "$pr" =~ ^[0-9]+$ ]] || fout "PR-nummer moet een getal zijn, was '$pr'."

  [ "$#" -gt 0 ] || fout "Geen enkele sectie opgegeven; een comment zonder URL's zegt niets."

  local repo=${GITHUB_REPOSITORY:-}
  [ -n "$repo" ] || fout "GITHUB_REPOSITORY ontbreekt; zonder repo valt er niets te plaatsen."

  local header=${COMMENT_HEADER:-$STANDAARD_HEADER}
  [ -n "$header" ] || fout "Een lege header is bij het opruimen niet te herkennen."

  local body
  body=$(comment_body "$header" "$@")

  local ids
  ids=$(bestaande_comments "$repo" "$pr" "$header") \
    || fout "De bestaande comments van PR $pr zijn niet op te vragen."

  if [ -z "$ids" ]; then
    gh api "repos/$repo/issues/$pr/comments" -X POST -f body="$body" --silent \
      || fout "De preview-comment op PR $pr is niet geplaatst."

    echo "Preview-comment geplaatst op PR $pr."

    return 0
  fi

  # De oudste, zodat opeenvolgende pushes dezelfde comment bijwerken. Meer dan één treffer hoort
  # niet voor te komen — deze stap plaatst er hooguit één — maar een achtergebleven comment uit een
  # eerdere vorm zou anders stil naast de bijgewerkte blijven staan.
  local id
  id=$(printf '%s\n' "$ids" | head -1)

  if [ "$(printf '%s\n' "$ids" | wc -l)" -gt 1 ]; then
    echo "::warning::Meer dan één comment met deze header op PR $pr; alleen $id is bijgewerkt."
  fi

  gh api "repos/$repo/issues/comments/$id" -X PATCH -f body="$body" --silent \
    || fout "De preview-comment $id op PR $pr is niet bijgewerkt."

  echo "Preview-comment $id bijgewerkt op PR $pr."
}

# Alleen uitvoeren bij directe aanroep, zodat de unittests de functies kunnen sourcen.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
