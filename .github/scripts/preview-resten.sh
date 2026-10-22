#!/usr/bin/env bash
# Spoort preview-resten op die niet meer bij een open PR horen, voor preview-resten.yml.
#
# cleanup-preview.yml ruimt een preview op bij het sluiten van de PR: één gebeurtenis, en wat daar
# misgaat of buiten zijn blik valt, blijft staan zonder dat iemand het merkt. Dit script kijkt
# andersom: het verzamelt wat er staat en zet dat af tegen de open PR's. Wat overblijft, gaat via
# precies die opruiming weg (zie `opruimen`), zodat er geen tweede verwijderroute naast staat met
# een eigen set aannames.
#
# SELECTIE. Alleen namen van de vorm `pr-<n>` (ZAD-deployment, GitHub-omgeving) en tags met de prefix
# `pr-<n>-` of precies `pr-<n>` tellen. `test`, de `fsc-*`-peers en `main-<sha>`-tags vallen daar per
# constructie buiten. Het nummer zelf bepaalt niets: de opruiming die erop volgt, bouwt de te
# verwijderen namen opnieuw op uit een gevalideerd getal en weigert een PR die niet gesloten is.
#
# ONLEESBAAR IS GEEN LEEG. Elke bron heeft een canary die er altijd in hoort te staan (`test` als
# deployment en omgeving); een lijst zonder canary is een kapotte meting en levert exitcode 2.
#
# Subcommando's — diagnostiek op stderr, data op stdout als tab-gescheiden regels:
#   zad <project>               de nummers van de `pr-<n>`-deployments in een ZAD-project
#                               (ZAD_API_KEY, optioneel ZAD_API_URL)
#   github                      `<n>\t<bron>` voor GitHub-omgevingen, -deployments en ghcr-tags
#                               (REPO, ORG, PAKKETTEN)
#   classificeer <bestand>      `<n>\t<klasse>\t<bronnen>` voor de regels `<n>\t<bron>` in <bestand>
#                               (REPO); klasse is `opruimen`, `geen-pr` of `vreemd-pakket`
#   opruimen <n>...             start cleanup-preview.yml per nummer, na elkaar, en wacht op de
#                               uitkomst: `<n>\t<conclusie>\t<run-url>` (REPO, KENMERK)
#   ongetagd <package>          ruimt ongetagde ghcr-versies op die nergens meer bij horen:
#                               `<package>\t<uitkomst>\t<aantal>\t<toelichting>` (ORG, GH_TOKEN,
#                               VERWIJDER=true om echt te verwijderen)
#
# Exitcodes: 0 = gelukt, 2 = niet vast te stellen.

set -euo pipefail

readonly STANDAARD_API_URL='https://operations-manager.rig.prd1.gn2.quattro.rijksapps.nl/api'
readonly REGISTRY='https://ghcr.io'

# Overschrijfbaar zodat de unittests het verkeer kunnen onderscheppen zonder netwerk.
GH=${PREVIEW_GH:-gh}
CURL=${PREVIEW_CURL:-curl}
NU=${PREVIEW_NU:-$(date -u +%s)}
WACHT=${PREVIEW_WACHT:-10}

# Een PR die net gesloten is, wordt nog door zijn eigen close-event opgeruimd. Daar een tweede
# opruiming naast starten zou in dezelfde concurrency-groep belanden, en GitHub annuleert dan de
# eerst-wachtende — mogelijk juist die van het close-event.
readonly RECENT_GESLOTEN_SECONDEN=$((24 * 3600))

# Ongetagde versies die jonger zijn, kunnen nog bij een push horen die op dit moment loopt.
readonly ONGETAGD_MINIMUM_SECONDEN=$((7 * 24 * 3600))

onbepaald() {
  echo "$*" >&2

  exit 2
}

# Een aanroep van de GitHub-API die faalt, is onbepaald. Geen `--jq`: met `--paginate` levert `gh`
# per pagina een eigen JSON-document, en jq hieronder leest die stroom als geheel.
api() {
  "$GH" api "$@"
}

nummers_uit_namen() {
  grep -oxE 'pr-[1-9][0-9]{0,6}' | sed 's/^pr-//' || true
}

# `pr-<n>-<sha>` (per commit) en de kale `pr-<n>` van vóór de per-commit-tags. Het koppelteken na het
# nummer is essentieel: zonder zou `pr-16` ook de tags van PR 168 opleveren.
nummers_uit_tags() {
  grep -oE '^pr-[1-9][0-9]{0,6}(-|$)' | sed -E 's/^pr-//; s/-$//' || true
}

zad() {
  local project=${1:-}
  [ -n "$project" ] || onbepaald "Gebruik: $0 zad <project>"
  [ -n "${ZAD_API_KEY:-}" ] || onbepaald "ZAD_API_KEY ontbreekt; de deploymentlijst van $project is niet op te vragen."

  local lijst namen
  lijst=$("$CURL" -sSf --max-time 30 --retry 3 --retry-connrefused \
    -H "X-API-Key: $ZAD_API_KEY" -H 'Accept: application/json' \
    "${ZAD_API_URL:-$STANDAARD_API_URL}/v2/projects/$project/deployments") \
    || onbepaald "De deploymentlijst van $project is niet op te vragen."

  namen=$(jq -r '.deployments[].name' <<<"$lijst" 2>/dev/null) \
    || onbepaald "Het antwoord van $project is geen deploymentlijst."

  grep -qxF test <<<"$namen" \
    || onbepaald "De deploymentlijst van $project mist 'test'; de meting is niet te vertrouwen."

  nummers_uit_namen <<<"$namen"
}

github() {
  [ -n "${REPO:-}" ] && [ -n "${ORG:-}" ] && [ -n "${PAKKETTEN:-}" ] \
    || onbepaald "REPO, ORG en PAKKETTEN zijn verplicht."

  local namen pkg tags pakketten

  namen=$(api "repos/$REPO/environments?per_page=100" --paginate | jq -r '.environments[].name') \
    || onbepaald "De omgevingslijst van $REPO is niet op te vragen."
  grep -qxF test <<<"$namen" || onbepaald "De omgevingslijst van $REPO mist 'test'; de meting is niet te vertrouwen."
  nummers_uit_namen <<<"$namen" | sed 's/$/\tomgeving/'

  namen=$(api "repos/$REPO/deployments?per_page=100" --paginate | jq -r '.[].environment') \
    || onbepaald "De deploymentlijst van $REPO is niet op te vragen."
  grep -qxF test <<<"$namen" || onbepaald "De deploymentlijst van $REPO mist 'test'; de meting is niet te vertrouwen."
  nummers_uit_namen <<<"$namen" | sort -u | sed 's/$/\tgithub-deployment/'

  for pkg in $PAKKETTEN; do
    tags=$(api "orgs/$ORG/packages/container/$pkg/versions?per_page=100" --paginate \
      | jq -r '.[].metadata.container.tags[]?') \
      || onbepaald "De versies van $pkg zijn niet op te vragen."
    nummers_uit_tags <<<"$tags" | sort -u | sed "s/\$/\tghcr:$pkg/"
  done

  # Packages die aan deze repo hangen maar niet in de opruimlijst staan. Een vergeten package laat
  # na elke gesloten PR weesversies achter die de opruiming niet ziet; hier vallen ze op. Alleen
  # packages van déze repo: de prefix `pr-<n>-` is binnen de organisatie niet uniek.
  pakketten=$(api "orgs/$ORG/packages?package_type=container&per_page=100" --paginate \
    | jq -r --arg r "$REPO" '.[] | select(.repository.full_name == $r) | .name') \
    || onbepaald "De packagelijst van $ORG is niet op te vragen."

  for pkg in $pakketten; do
    case " $PAKKETTEN " in *" $pkg "*) continue ;; esac

    tags=$(api "orgs/$ORG/packages/container/$pkg/versions?per_page=100" --paginate \
      | jq -r '.[].metadata.container.tags[]?') \
      || onbepaald "De versies van $pkg zijn niet op te vragen."
    nummers_uit_tags <<<"$tags" | sort -u | sed "s/\$/\tvreemd-pakket:$pkg/"
  done
}

# Per nummer één klasse. Open PR's vallen eruit, net gesloten PR's ook (zie RECENT_GESLOTEN_SECONDEN).
#   opruimen       gesloten PR met resten die cleanup-preview.yml kent
#   vreemd-pakket  gesloten PR met alleen tags in een package buiten de opruimlijst
#   geen-pr        een nummer waar geen PR bij hoort; de opruiming zou het weigeren
classificeer() {
  local bestand=${1:-}
  [ -f "$bestand" ] || onbepaald "Gebruik: $0 classificeer <bestand met regels n<TAB>bron>"
  [ -n "${REPO:-}" ] || onbepaald "REPO is verplicht."

  local open n bronnen antwoord staat gesloten klasse

  open=$(api "repos/$REPO/pulls?state=open&per_page=100" --paginate | jq -r '.[].number') \
    || onbepaald "De open PR's van $REPO zijn niet op te vragen."

  for n in $(cut -f1 "$bestand" | sort -un); do
    grep -qxF "$n" <<<"$open" && continue

    bronnen=$(awk -F'\t' -v n="$n" '$1 == n { print $2 }' "$bestand" | sort -u | paste -sd, -)

    # Eerst de foutmelding vangen: alleen een 404 betekent "geen PR", al het andere is onbepaald.
    if ! antwoord=$(api "repos/$REPO/pulls/$n" 2>&1); then
      grep -qF 'HTTP 404' <<<"$antwoord" || onbepaald "PR $n van $REPO is niet op te vragen: $antwoord"
      printf '%s\tgeen-pr\t%s\n' "$n" "$bronnen"
      continue
    fi

    staat=$(jq -r '.state' <<<"$antwoord") && gesloten=$(jq -r '.closed_at // ""' <<<"$antwoord") \
      || onbepaald "Het antwoord over PR $n is geen PR."

    # Tussen de open-lijst en dit moment heropend: niet aankomen.
    [ "$staat" = closed ] || continue

    [ -n "$gesloten" ] || onbepaald "PR $n is gesloten maar heeft geen sluitmoment."
    [ $((NU - $(date -u -d "$gesloten" +%s))) -ge "$RECENT_GESLOTEN_SECONDEN" ] || continue

    if grep -qvE '^vreemd-pakket:' <<<"$(tr , '\n' <<<"$bronnen")"; then
      klasse=opruimen
    else
      klasse=vreemd-pakket
    fi

    printf '%s\t%s\t%s\n' "$n" "$klasse" "$bronnen"
  done
}

# Start cleanup-preview.yml per nummer en wacht op de conclusie. Na elkaar en niet parallel: elke
# ZAD-delete laat Operations Manager naar de GitOps-repo committen en vergrendelt het project.
#
# Een `workflow_dispatch` geeft geen run-id terug. Het kenmerk gaat daarom als input mee en staat in
# de runnaam van cleanup-preview.yml; daaraan herkennen we de run die wij startten, en niet een
# handmatige run voor hetzelfde nummer die toevallig tegelijk loopt.
opruimen() {
  [ -n "${REPO:-}" ] && [ -n "${KENMERK:-}" ] || onbepaald "REPO en KENMERK zijn verplicht."

  local n kenmerk run status conclusie missers

  for n in "$@"; do
    kenmerk="$KENMERK-$n"

    if ! "$GH" workflow run cleanup-preview.yml --repo "$REPO" -f pr="$n" -f aanvraag="$kenmerk" >&2; then
      printf '%s\tniet-gestart\t\n' "$n"
      continue
    fi

    run=""

    for _ in $(seq 30); do
      run=$("$GH" run list --repo "$REPO" --workflow cleanup-preview.yml --event workflow_dispatch \
        --limit 50 --json databaseId,displayTitle \
        | jq -r --arg k "$kenmerk" '.[] | select(.displayTitle | split(" ") | index($k)) | .databaseId' \
        | head -1) || run=""
      [ -n "$run" ] && break
      sleep "$WACHT"
    done

    if [ -z "$run" ]; then
      printf '%s\tniet-gevonden\t\n' "$n"
      continue
    fi

    # Wachten tot de run klaar is. De ZAD-delete duurt minuten per project; de job-timeout van de
    # aanroeper is de bovengrens. Een run die herhaald niet op te vragen is, geeft het wachten op:
    # anders blokkeert één hik alle nummers erna.
    status=""
    missers=0

    while [ "$missers" -lt 30 ]; do
      if status=$("$GH" run view "$run" --repo "$REPO" --json status,conclusion \
        | jq -r '"\(.status)\t\(.conclusion)"'); then
        missers=0
      else
        status=""
        missers=$((missers + 1))
      fi

      case "$status" in
        completed*) break ;;
      esac

      sleep "$WACHT"
    done

    conclusie=${status#*$'\t'}
    printf '%s\t%s\t%s\n' "$n" "${conclusie:-onbekend}" "https://github.com/$REPO/actions/runs/$run"
  done
}

# Het mediatype van een manifest, via de registry-API.
mediatype() {
  local pkg=$1 digest=$2 token=$3

  "$CURL" -sSf --max-time 30 --retry 3 -o /dev/null -w '%{content_type}' -I \
    -H "Authorization: Bearer $token" \
    -H 'Accept: application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json' \
    "$REGISTRY/v2/$ORG/$pkg/manifests/$digest"
}

# Ongetagde versies ontstaan doordat een tag naar een nieuwere versie verhuist; zo'n versie hoort
# nergens meer bij en is veilig weg te halen. Maar een multi-arch-image of een image met attestation
# bestaat uit een getagde index met ongetagde kinderen, en die kinderen zijn in gebruik. Daarom
# eerst vaststellen dat geen enkele getagde versie in dit package een index is; lukt dat niet, dan
# blijft alles staan en volgt een melding. Liever een melding te veel dan een kapot image.
ongetagd() {
  local pkg=${1:-}
  [ -n "$pkg" ] && [ -n "${ORG:-}" ] && [ -n "${GH_TOKEN:-}" ] || onbepaald "Gebruik: ORG=… GH_TOKEN=… $0 ongetagd <package>"

  local versies kandidaten getagd token digest type ids id fouten=0 rest

  versies=$(api "orgs/$ORG/packages/container/$pkg/versions?per_page=100" --paginate | jq -sc 'add') \
    || onbepaald "De versies van $pkg zijn niet op te vragen."

  kandidaten=$(jq -r --argjson nu "$NU" --argjson min "$ONGETAGD_MINIMUM_SECONDEN" '.[]
    | select((.metadata.container.tags // []) | length == 0)
    | select($nu - (.created_at | fromdateiso8601) >= $min) | .id' <<<"$versies") \
    || onbepaald "De versies van $pkg zijn niet te beoordelen."

  [ -n "$kandidaten" ] || return 0

  local aantal
  aantal=$(grep -c . <<<"$kandidaten")

  token=$("$CURL" -sSf --max-time 30 --retry 3 -u "x:$GH_TOKEN" \
    "$REGISTRY/token?scope=repository:$ORG/$pkg:pull" | jq -er .token) || {
    printf '%s\tbewaard\t%s\tgeen registry-token; niet vast te stellen of het kinderen van een index zijn\n' "$pkg" "$aantal"
    return 0
  }

  getagd=$(jq -r '.[] | select((.metadata.container.tags // []) | length > 0) | .name' <<<"$versies")

  for digest in $getagd; do
    if ! type=$(mediatype "$pkg" "$digest" "$token"); then
      printf '%s\tbewaard\t%s\tmediatype van %s niet op te vragen\n' "$pkg" "$aantal" "$digest"
      return 0
    fi

    case "$type" in
      *manifest.v2+json* | *image.manifest.v1+json*) ;;
      *)
        printf '%s\tbewaard\t%s\tgetagde versie %s is %s; ongetagde versies kunnen daar kinderen van zijn\n' \
          "$pkg" "$aantal" "$digest" "${type:-zonder mediatype}"
        return 0
        ;;
    esac
  done

  if [ "${VERWIJDER:-false}" != true ]; then
    printf '%s\tte-verwijderen\t%s\talleen geïnventariseerd\n' "$pkg" "$aantal"
    return 0
  fi

  for id in $kandidaten; do
    api "orgs/$ORG/packages/container/$pkg/versions/$id" -X DELETE >/dev/null 2>&1 || fouten=$((fouten + 1))
  done

  # Nameten op de lijst, niet op de exitcodes: een weigering van GitHub (de downloadteller) komt
  # ook als 4xx terug, en alleen wat aantoonbaar uit de lijst verdwenen is, telt als verwijderd.
  ids=$(api "orgs/$ORG/packages/container/$pkg/versions?per_page=100" --paginate | jq -r '.[].id') \
    || onbepaald "De versies van $pkg zijn na het verwijderen niet op te vragen."
  rest=$(grep -cxFf <(printf '%s\n' "$kandidaten") <<<"$ids" || true)

  printf '%s\tverwijderd\t%s\t\n' "$pkg" "$((aantal - rest))"

  if [ "$rest" -gt 0 ]; then
    printf '%s\tmislukt\t%s\tstaan na het verwijderen nog in de lijst (%s weigeringen)\n' "$pkg" "$rest" "$fouten"
  fi
}

# Wat er na een run is blijven staan of niet vast te stellen was, als regels
# `<soort>\t<sleutel>\t<toelichting>`. Leeg betekent: niets te melden. Wat wél is opgeruimd, staat
# hier niet in; dat hoort in de job-samenvatting en niet in een melding.
#
# Invoer via de omgeving:
#   CANCELLED  github.cancelled()
#   OPRUIMEN   true als deze run hoorde op te ruimen
#   NEEDS      toJSON(needs) van de meldjob, met de outputs van zad, inventaris, opruimen en ongetagd
bevindingen() {
  [ -n "${NEEDS+gezet}" ] && [ -n "${CANCELLED+gezet}" ] || onbepaald "NEEDS en CANCELLED zijn verplicht."
  jq -e 'type == "object"' <<<"$NEEDS" >/dev/null 2>&1 || onbepaald "NEEDS is geen toJSON(needs)-uitvoer."

  local job resultaat

  if [ "$CANCELLED" != false ]; then
    printf 'onbepaald\trun\tde run is afgebroken; wat er staat, is niet volledig vastgesteld\n'
  fi

  # `opruimen` mag overslaan als er niets op te ruimen was of de run alleen inventariseert; elke
  # andere job hoort te slagen.
  for job in zad inventaris ongetagd; do
    resultaat=$(jq -r --arg j "$job" '.[$j].result // "ontbreekt"' <<<"$NEEDS")
    [ "$resultaat" = success ] || printf 'onbepaald\t%s\tde job eindigde als %s\n' "$job" "$resultaat"
  done

  local te_doen
  te_doen=$(jq -r '.inventaris.outputs.opruimen // ""' <<<"$NEEDS")
  resultaat=$(jq -r '.opruimen.result // "ontbreekt"' <<<"$NEEDS")

  if [ -n "$te_doen" ] && [ "${OPRUIMEN:-false}" = true ] && [ "$resultaat" != success ]; then
    printf 'onbepaald\topruimen\tde job eindigde als %s; voor %s is niet vastgesteld of de opruiming lukte\n' \
      "$resultaat" "$te_doen"
  fi

  # Per ZAD-project de leg-uitkomst. `onleesbaar` is een meting die niets bewijst.
  jq -r '.zad.outputs // {} | to_entries[] | select(.key | startswith("nummers-"))
    | select(.value == "onleesbaar")
    | "onbepaald\tzad:\(.key | ltrimstr("nummers-"))\tde deploymentlijst is niet te lezen"' <<<"$NEEDS"

  # De inventaris: `onbepaald`-regels gaan ongewijzigd door, en per nummer alleen de klassen die
  # de opruiming niet zelf kan afhandelen.
  jq -r '.inventaris.outputs.bevindingen // ""' <<<"$NEEDS" | awk -F'\t' '
    $1 == "onbepaald"                     { print }
    $2 == "geen-pr"                       { print "geen-pr\tpr-" $1 "\tresten zonder bijbehorende PR: " $3 }
    $2 == "vreemd-pakket"                 { print "vreemd-pakket\tpr-" $1 "\ttags in een package buiten de opruimlijst: " $3 }
    $2 == "opruimen" && $3 ~ /vreemd-pakket:/ { print "vreemd-pakket\tpr-" $1 "\ttags in een package buiten de opruimlijst: " $3 }'

  jq -r '.opruimen.outputs.uitkomsten // ""' <<<"$NEEDS" | awk -F'\t' '
    NF && $2 != "success" { print "opruiming-mislukt\tpr-" $1 "\tcleanup-preview.yml eindigde als " $2 ($3 ? " (" $3 ")" : "") }'

  jq -r '.ongetagd.outputs.uitkomsten // ""' <<<"$NEEDS" | awk -F'\t' '
    $2 == "bewaard"   { print "ongetagd-bewaard\t" $1 "\t" $3 " ongetagde versie(s) blijven staan: " $4 }
    $2 == "mislukt"   { print "ongetagd-mislukt\t" $1 "\t" $3 " ongetagde versie(s) " $4 }
    $2 == "onbepaald" { print "onbepaald\tghcr:" $1 "\t" $4 }'
}

readonly ISSUE_TITEL='Achtergebleven preview-resten'

# Houdt precies één issue bij met wat er is blijven staan. Een rode job in de Actions-tab ziet niemand
# op een geplande run; een issue komt in de notificaties van wie de repo volgt.
#
#   bevindingen leeg, geen issue  → niets (een controle die niets aantreft is stil)
#   bevindingen leeg, issue open  → sluiten met een afsluitende comment
#   bevindingen, geen issue       → aanmaken
#   bevindingen, issue open       → alleen bij een gewijzigde stand de beschrijving bijwerken plus
#                                   een comment; dezelfde stand elke dag opnieuw melden is ruis
#
# De stand wordt vergeleken op soort en sleutel, niet op de toelichting: daar staan run-URL's in die
# elke run anders zijn.
issue() {
  local bestand=${1:-}
  [ -f "$bestand" ] && [ -n "${REPO:-}" ] && [ -n "${RUN_URL:-}" ] \
    || onbepaald "Gebruik: REPO=… RUN_URL=… $0 issue <bevindingen>"

  local nummer stand vorige body

  nummer=$(api "repos/$REPO/issues?state=open&per_page=100" --paginate \
    | jq -r --arg t "$ISSUE_TITEL" '.[] | select(.pull_request == null and .title == $t
      and .user.login == "github-actions[bot]") | .number' | head -1) \
    || onbepaald "De open issues van $REPO zijn niet op te vragen."

  if [ ! -s "$bestand" ]; then
    if [ -n "$nummer" ]; then
      api "repos/$REPO/issues/$nummer/comments" -X POST \
        -f body="Er staan geen preview-resten meer die niet bij een open PR horen ($RUN_URL)." >/dev/null \
        || onbepaald "Kon niet reageren op issue #$nummer."
      api "repos/$REPO/issues/$nummer" -X PATCH -f state=closed -f state_reason=completed >/dev/null \
        || onbepaald "Kon issue #$nummer niet sluiten."
      echo "Issue #$nummer gesloten: niets meer te melden."
    else
      echo "Niets te melden."
    fi

    return 0
  fi

  stand=$(cut -f1,2 "$bestand" | sort -u | sha256sum | cut -c1-16)

  body=$(
    echo "<!-- stand: $stand -->"
    echo "De dagelijkse controle op preview-resten ([laatste run]($RUN_URL)) vond resten die niet"
    echo "vanzelf zijn opgeruimd, of kon niet alles vaststellen. Wat de controle zelf kon opruimen,"
    echo "is opgeruimd en staat hier niet. Dit issue sluit vanzelf zodra er niets meer te melden is."
    echo
    echo "| soort | wat | toelichting |"
    echo "| --- | --- | --- |"
    awk -F'\t' '{ gsub(/\|/, "\\|"); print "| " $1 " | `" $2 "` | " $3 " |" }' "$bestand"
    echo
    echo "Wat te doen per soort:"
    echo
    echo "- **onbepaald** — een bron was niet te lezen. Kijk in de run; vaak een verlopen sleutel of een API-hik."
    echo "- **opruiming-mislukt** — herdraai \`gh workflow run cleanup-preview.yml -f pr=<n>\` en kijk naar \`opruim-oordeel\`."
    echo "- **geen-pr** — een naam \`pr-<n>\` zonder PR in deze repo. Met de hand onderzoeken; de automatische opruiming weigert dit nummer terecht."
    echo "- **vreemd-pakket** — een package van deze repo dat niet in \`EIGEN_IMAGES\` (deploy.yml) staat. Opnemen in de lijst, of het package met de hand opruimen."
    echo "- **ongetagd-bewaard / ongetagd-mislukt** — ongetagde ghcr-versies die niet aantoonbaar los staan, of die GitHub weigerde te verwijderen."
  )

  if [ -z "$nummer" ]; then
    api "repos/$REPO/issues" -X POST -f title="$ISSUE_TITEL" -f body="$body" | jq -r '"Issue #\(.number) aangemaakt."' \
      || onbepaald "Kon het issue niet aanmaken."

    return 0
  fi

  vorige=$(api "repos/$REPO/issues/$nummer" | jq -r '.body // ""' | sed -n 's/^<!-- stand: \([0-9a-f]*\) -->$/\1/p') \
    || onbepaald "Issue #$nummer is niet op te vragen."

  if [ "$vorige" = "$stand" ]; then
    echo "Issue #$nummer: stand ongewijzigd, niets bijgewerkt."

    return 0
  fi

  api "repos/$REPO/issues/$nummer" -X PATCH -f body="$body" >/dev/null \
    || onbepaald "Kon issue #$nummer niet bijwerken."
  api "repos/$REPO/issues/$nummer/comments" -X POST \
    -f body="De stand is veranderd; zie de beschrijving ($RUN_URL)." >/dev/null \
    || onbepaald "Kon niet reageren op issue #$nummer."
  echo "Issue #$nummer bijgewerkt."
}

# Alleen uitvoeren bij directe aanroep, zodat de unittests de functies kunnen sourcen.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  opdracht=${1:-}
  shift || true

  case "$opdracht" in
    zad | github | classificeer | opruimen | ongetagd | bevindingen | issue) "$opdracht" "$@" ;;
    *) onbepaald "Gebruik: $0 zad|github|classificeer|opruimen|ongetagd|bevindingen|issue …" ;;
  esac
fi
