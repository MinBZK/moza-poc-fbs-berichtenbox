#!/usr/bin/env bash
# Plaatst — of werkt bij — de comment met de preview-URL's op een PR: één comment met een sectie
# per opgegeven groep URL's.
#
# Waarom niet de comment van zad-actions/deploy zelf: die schrijft per aanroep de HELE body met
# alleen de URL's van háár project. Twee deploys met dezelfde header overschrijven elkaar dus, en
# met een eigen header per project staan er twee comments op de PR die het opruimen allebei moet
# kennen. Eén comment, samengesteld ná de laatste deploy, houdt de URL's bij elkaar.
#
# Wat de aanroeper meegeeft bepaalt wat erin staat; deploy.yml geeft de demo en de uitvraag mee en
# laat het project met de externe stubs weg, omdat dat geen ingang is voor wie de PR opent.
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

# De body wordt in een command substitution opgebouwd, en een subshell erft `errexit` niet zonder
# deze optie. Elke faalroute in `comment_body` handelt zijn exitcode nu zelf af; dit is het vangnet
# voor een later commando dat dat niet doet.
shopt -s inherit_errexit

# De unittests houden deze tekst tegen die van beide workflows aan.
readonly STANDAARD_HEADER='## 🚀 Preview Deployment'

# Valideren en renderen in één programma, zodat er geen kopje geschreven kan worden waarvan de
# regels eronder ontbreken. Een waarde moet een niet-lege string zijn: de deploy-action geeft een
# component zonder publiek adres terug als `null` of leeg, en `- **console:** null` leest als een
# adres dat er niet is.
readonly JQ_SECTIE='
  if type == "object" and length > 0
     and (to_entries | all(.value | type == "string" and length > 0))
  then to_entries[] | "- **\(.key):** \(.value)"
  else error("geen map van componenten naar URLs")
  end'

fout() {
  echo "::error::$*" >&2

  exit 1
}

sectie() {
  local kopje=$1 urls=$2
  local regels rc=0

  # jq doet het opmaken, en niet een shell-lus met `read`: dan hoeft het script de JSON niet zelf
  # te splitsen en kan een component-naam of URL de regel niet vervormen.
  regels=$(printf '%s' "$urls" | jq -r "$JQ_SECTIE") || rc=$?

  # jq 1.7 geeft 5 voor élk probleem met de invoer — de `error` hierboven én een parse-fout. Elke
  # andere code komt van jq zelf (2 bij een systeemfout, 3 als het programma hierboven niet
  # compileert). Die twee door elkaar halen stuurt de lezer naar de deploy-uitvoer terwijl er met de
  # URL's niets mis is. jq's eigen melding staat in de log: zijn stderr wordt niet gedempt.
  case $rc in
    0) ;;
    5) fout "De URL's voor '$kopje' zijn geen gevulde JSON-map van componenten naar URL's: $urls" ;;
    *) fout "jq kon de URL's voor '$kopje' niet verwerken (exit $rc)." ;;
  esac

  # De uitkomst en niet de invoer, want jq eindigt met 0 zodra de invoer géén JSON-waarde draagt —
  # bij een lege string, maar net zo goed bij een spatie of een newline. Dat is precies wat er
  # binnenkomt als de job-output van een andere deploy wegviel, en het zou een kop zonder URL's
  # opleveren.
  [ -n "$regels" ] || fout "De URL's voor '$kopje' leverden geen enkele regel op: '$urls'"

  printf '### %s\n\n%s\n\n' "$kopje" "$regels"
}

comment_body() {
  local header=$1
  shift

  printf '%s\n\n' "$header"
  printf 'De preview-omgeving van deze PR:\n\n'

  local paar kopje urls gezien=""
  for paar in "$@"; do
    case "$paar" in
      *=*) ;;
      *) fout "Verwachtte '<kopje>=<json-map>', kreeg '$paar'." ;;
    esac

    kopje=${paar%%=*}
    urls=${paar#*=}

    [ -n "$kopje" ] || fout "Een lege sectienaam is geen sectienaam."

    # Twee secties met dezelfde naam komen van een verwisseld argument in de aanroep. De lezer ziet
    # dan twee kopjes en gelooft dat beide previews er staan, terwijl er één twee keer staat.
    case "$gezien" in
      *"|$kopje|"*) fout "De sectie '$kopje' staat er twee keer in." ;;
    esac

    gezien="$gezien|$kopje|"

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

  # Vooraf, want zonder deze twee komt het gemis eruit als een fout over de URL's of over de API —
  # en dan zoekt de lezer op de verkeerde plek.
  command -v jq >/dev/null || fout "jq ontbreekt; zonder jq is de body niet op te bouwen."
  command -v gh >/dev/null || fout "gh ontbreekt; zonder gh is er geen comment te plaatsen."

  local repo=${GITHUB_REPOSITORY:-}
  [ -n "$repo" ] || fout "GITHUB_REPOSITORY ontbreekt; zonder repo valt er niets te plaatsen."

  # `-` en niet `:-`: een leeg gezette header valt zo niet stil terug op de default, maar faalt —
  # de aanroeper bedoelde een andere tekst dan die default.
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

  # De eerste die de API teruggeeft — *List issue comments* levert oplopend op id — en dus de
  # comment die opeenvolgende pushes steeds bijwerken. Meer dan één treffer hoort niet voor te
  # komen (deze stap plaatst er hooguit één), maar een achtergebleven comment uit een eerdere vorm
  # zou anders stil naast de bijgewerkte blijven staan. Parameter-expansie en geen `head`: die
  # sluit zijn invoer na één regel, wat bij veel treffers op een SIGPIPE zonder uitleg eindigt.
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
