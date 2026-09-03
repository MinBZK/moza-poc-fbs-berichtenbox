#!/usr/bin/env bash
# Plaatst — of werkt bij — de comment met de preview-URL's op een PR: één comment met een sectie
# per opgegeven groep URL's.
#
# Waarom niet de comment van zad-actions/deploy zelf: die schrijft per aanroep de HELE body en
# zoekt de bestaande comment op `startswith(<header>)`. De preview beslaat meer dan één project,
# dus zou de tweede aanroep de URL's van de eerste overschrijven; en een eigen header per project
# helpt niet, want de action bouwt die als `<header> — <component>` — met hetzelfde prefix, dus de
# kortste header matcht ook de comment van de ander. Eén comment, ná de laatste deploy, is de enige
# vorm die de URL's bij elkaar houdt.
#
# Wat de aanroeper meegeeft bepaalt wat erin staat; deploy.yml geeft de uitvraag en de demo mee en
# laat de externe stubs weg, omdat die geen ingang zijn voor wie de PR opent.
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

# De body wordt in een command substitution opgebouwd, en dáár erft een subshell `errexit` niet
# zonder deze optie: een falende jq zou dan een sectie zonder URL's opleveren terwijl het script
# "geplaatst" meldt en met 0 eindigt.
shopt -s inherit_errexit

# De constante is de bron van waarheid waar de unittests de workflows tegenaan houden; de
# env-override bestaat zodat de aanroeper de tekst expliciet meegeeft in plaats van op deze default
# te leunen.
readonly STANDAARD_HEADER='## 🚀 Preview Deployment'

# Valideren en renderen in één programma, zodat er geen kopje geschreven kan worden waarvan de
# regels eronder ontbreken. `error` in plaats van een lege uitvoer, want een lege map betekent dat
# de deploy-action geen URL's opleverde en dat hoort niet als lege sectie te eindigen: die leest als
# "dit deel hoort niet bij de preview".
readonly JQ_SECTIE='
  if type == "object" and length > 0
  then to_entries[] | "- **\(.key):** \(.value)"
  else error("geen gevulde map")
  end'

fout() {
  echo "::error::$*" >&2

  exit 1
}

sectie() {
  local kopje=$1 urls=$2
  local regels rc=0

  # Vóór jq, want lege invoer draagt geen JSON-waarde: het programma hieronder draait dan nooit, jq
  # eindigt met 0 en er zou een kopje zonder URL's overblijven. Dit is precies wat er binnenkomt als
  # de job-output van een andere deploy leeg is.
  [ -n "$urls" ] || fout "De URL's voor '$kopje' ontbreken; de deploy leverde geen enkele URL op."

  # jq doet het opmaken, en niet een shell-lus met `read`: dan hoeft het script de JSON niet zelf
  # te splitsen en kan een component-naam of URL de regel niet vervormen.
  regels=$(printf '%s' "$urls" | jq -r "$JQ_SECTIE") || rc=$?

  # jq 1.7 geeft 5 voor élk probleem met de invoer — de `error` hierboven én een parse-fout. Een
  # hogere code komt van jq zelf (127 als hij niet op het pad staat, een crash daarboven). Die twee
  # door elkaar halen stuurt de lezer naar de deploy-uitvoer terwijl er met de URL's niets mis is.
  # jq's eigen melding staat in de log: zijn stderr wordt niet gedempt.
  case $rc in
    0) ;;
    5) fout "De URL's voor '$kopje' zijn geen gevulde JSON-map: $urls" ;;
    *) fout "jq kon de URL's voor '$kopje' niet verwerken (exit $rc)." ;;
  esac

  printf '### %s\n\n%s\n\n' "$kopje" "$regels"
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

# De id's van de comments die er al staan, in de volgorde waarin de API ze levert. `--paginate`,
# want op een PR met veel comments valt de preview-comment buiten de eerste pagina; zonder
# paginering zou deze stap er dan bij elke push een tweede plaatsen.
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

  # Een leeg of niet-numeriek nummer zou pas halverwege als 404 uit `gh` komen — `/issues//comments`
  # bestaat niet. Hier faalt het met een leesbare reden vóór er één aanroep uitgaat.
  [[ "$pr" =~ ^[0-9]+$ ]] || fout "PR-nummer moet een getal zijn, was '$pr'."

  [ "$#" -gt 0 ] || fout "Geen enkele sectie opgegeven; een comment zonder URL's zegt niets."

  command -v jq >/dev/null || fout "jq ontbreekt; zonder jq is de body niet op te bouwen."

  local repo=${GITHUB_REPOSITORY:-}
  [ -n "$repo" ] || fout "GITHUB_REPOSITORY ontbreekt; zonder repo valt er niets te plaatsen."

  # `-` en niet `:-`: een expliciet leeg gezette header valt zo niet stil terug op de default. Deed
  # hij dat wel, dan zou het opruimen — dat op de header van cleanup-preview.yml zoekt — de comment
  # niet meer vinden.
  local header=${COMMENT_HEADER-$STANDAARD_HEADER}
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

  # De eerste die de API teruggeeft. Dat is de oudste — *List issue comments* levert oplopend op
  # aanmaaktijd en kent geen parameter om dat vast te leggen — en dus de comment die opeenvolgende
  # pushes steeds bijwerken. Meer dan één treffer hoort niet voor te komen (deze stap plaatst er
  # hooguit één), maar een achtergebleven comment uit een eerdere vorm zou anders stil naast de
  # bijgewerkte blijven staan.
  #
  # Zonder pipe naar `head`: die sluit zijn invoer na één regel, en bij duizenden treffers eindigt
  # het script dan op een SIGPIPE zonder één regel uitleg.
  local id=${ids%%$'\n'*}

  if [ "$id" != "$ids" ]; then
    echo "::warning::Meer dan één comment met deze header op PR $pr; alleen $id is bijgewerkt."
  fi

  gh api "repos/$repo/issues/comments/$id" -X PATCH -f body="$body" --silent \
    || fout "De preview-comment $id op PR $pr is niet bijgewerkt."

  echo "Preview-comment $id bijgewerkt op PR $pr."
}

# Alleen uitvoeren bij directe aanroep, zodat de unittests het script kunnen sourcen zonder dat er
# een comment geplaatst wordt.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
