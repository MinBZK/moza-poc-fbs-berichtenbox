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
#   kies <bestand>              de nummers die deze run opruimt, roterend (MAX_PER_RUN, LAATSTE)
#   vorige-stand <doel>         de stand van de vorige run op main, uit het artefact; exit 1 als
#                               er geen is (REPO)
#   nieuwe-stand                de stand voor de volgende run (NEEDS)
#   opruimen <n>...             start cleanup-preview.yml per nummer, na elkaar, en wacht op de
#                               uitkomst: `<n>\t<conclusie>\t<run-url of reden>` (REPO, KENMERK);
#                               conclusie `uitgesteld` als er een uitrol of opruiming liep
#   ongetagd <package>          ruimt ongetagde ghcr-versies op die nergens meer bij horen:
#                               `<package>\t<uitkomst>\t<aantal>\t<toelichting>` (ORG, GH_TOKEN,
#                               VERWIJDER=true om echt te verwijderen, VERWIJDER_MAX als plafond)
#   bevindingen                 wat er na een run te melden is (NEEDS, CANCELLED, OPRUIMEN)
#   issue <bevindingen>         houdt het meld-issue bij (REPO, RUN_URL)
#   noodmelding                 minimaal issue als de melding zelf mislukte (REPO, RUN_URL)
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

# Welke nummers deze run opruimt: hoogstens MAX_PER_RUN, uit de regels met klasse `opruimen`.
#
# Roterend en niet "de laagste tien": blijven de laagste nummers mislukken, dan zouden ze elke run
# opnieuw de hele ruimte opeisen en kwamen de nummers erna nooit aan de beurt. De keuze begint bij
# het eerste nummer ná LAATSTE, het laatst geprobeerde nummer uit de stand van de vorige run, en
# loopt rond. Een run die niets probeert (alleen inventariseren) laat LAATSTE staan, dus schuift de
# rij alleen op door werk dat echt gedaan is. Zonder stand begint de keuze bij het laagste nummer.
kies() {
  local bestand=${1:-} max=${MAX_PER_RUN:-} laatste=${LAATSTE-0}
  [ -f "$bestand" ] || onbepaald "Gebruik: MAX_PER_RUN=… [LAATSTE=…] $0 kies <bevindingen>"

  case "$max" in
    '' | *[!0-9]*) onbepaald "MAX_PER_RUN moet een getal zijn." ;;
  esac

  case "$laatste" in
    '' | *[!0-9]*) onbepaald "LAATSTE moet een getal zijn." ;;
  esac

  [ "$max" -gt 0 ] || onbepaald "MAX_PER_RUN moet groter dan 0 zijn."

  local nummers totaal begin=0 i
  mapfile -t nummers < <(awk -F'\t' '$2 == "opruimen" { print $1 }' "$bestand" | sort -un)
  totaal=${#nummers[@]}

  if [ "$totaal" -le "$max" ]; then
    [ "$totaal" -eq 0 ] || printf '%s\n' "${nummers[@]}"

    return 0
  fi

  # Het eerste nummer groter dan LAATSTE; is er geen, dan rond naar het begin.
  for ((i = 0; i < totaal; i++)); do
    if [ "${nummers[i]}" -gt "$laatste" ]; then
      begin=$i
      break
    fi
  done

  for ((i = 0; i < max; i++)); do
    echo "${nummers[(begin + i) % totaal]}"
  done
}

# Loopt er een uitrol of een opruiming? Dan kan er een taak in Operations Manager staan, en die
# vergrendelt het project. Een delete die daar tussenkomt, neemt de wachtstap van de uitrol over en
# laat die als `superseded` eindigen (MinBZK/MijnOverheidZakelijk#1075). Niet vast te stellen geldt
# als bezet: een uitgestelde opruiming komt de volgende run terug, een gestrande uitrol niet.
bezet() {
  local workflow aantal

  for workflow in deploy.yml cleanup-preview.yml; do
    aantal=$("$GH" run list --repo "$REPO" --workflow "$workflow" --limit 20 --json status \
      | jq '[.[] | select(.status != "completed")] | length') || return 0

    [ "$aantal" -eq 0 ] 2>/dev/null || return 0
  done

  return 1
}

# Regels `<n>\tuitgesteld\t<reden>` voor de nummers die deze run niet meer start.
uitstellen() {
  local reden=$1 n
  shift

  for n in "$@"; do
    printf '%s\tuitgesteld\t%s\n' "$n" "$reden"
  done
}

# Na zoveel opvragingen op rij die mislukken, geeft het wachten op een run het op.
readonly MAX_MISSERS=30

# Start cleanup-preview.yml per nummer en wacht op de conclusie. Na elkaar en niet parallel: elke
# ZAD-delete laat Operations Manager naar de GitOps-repo committen en vergrendelt het project.
#
# Een `workflow_dispatch` geeft geen run-id terug. Het kenmerk gaat daarom als input mee en staat in
# de runnaam van cleanup-preview.yml; daaraan herkennen we de run die wij startten, en niet een
# handmatige run voor hetzelfde nummer die toevallig tegelijk loopt.
opruimen() {
  [ -n "${REPO:-}" ] && [ -n "${KENMERK:-}" ] || onbepaald "REPO en KENMERK zijn verplicht."

  local nummers=("$@") i n kenmerk run status conclusie missers

  for ((i = 0; i < ${#nummers[@]}; i++)); do
    n=${nummers[i]}
    kenmerk="$KENMERK-$n"

    if bezet; then
      uitstellen "er liep een uitrol of opruiming" "${nummers[@]:i}"

      return 0
    fi

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

    # Een gestarte run die we niet terugvinden, loopt misschien nog. Het volgende nummer starten zou
    # twee ZAD-deletes naast elkaar zetten; de rest wacht dus op de volgende run.
    if [ -z "$run" ]; then
      printf '%s\tniet-gevonden\t\n' "$n"
      uitstellen "de vorige opruiming in deze run was niet terug te vinden" "${nummers[@]:i+1}"

      return 0
    fi

    # Wachten tot de run klaar is. De ZAD-delete duurt minuten per project; de job-timeout van de
    # aanroeper is de bovengrens. Een run die herhaald niet op te vragen is, geeft het wachten op:
    # anders blokkeert één hik alle nummers erna.
    status=""
    missers=0

    while [ "$missers" -lt "$MAX_MISSERS" ]; do
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

readonly MANIFEST_TYPEN='application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json'

# Het mediatype van een manifest, via de registry-API.
mediatype() {
  local pkg=$1 digest=$2 token=$3

  "$CURL" -sSf --max-time 30 --retry 3 -o /dev/null -w '%{content_type}' -I \
    -H "Authorization: Bearer $token" -H "Accept: $MANIFEST_TYPEN" \
    "$REGISTRY/v2/$ORG/$pkg/manifests/$digest"
}

# Het manifest zelf, via de registry-API.
manifest() {
  local pkg=$1 digest=$2 token=$3

  "$CURL" -sSf --max-time 30 --retry 3 \
    -H "Authorization: Bearer $token" -H "Accept: $MANIFEST_TYPEN" \
    "$REGISTRY/v2/$ORG/$pkg/manifests/$digest"
}

# Ongetagde versies ontstaan doordat een tag naar een nieuwere versie verhuist; zo'n versie hoort
# nergens meer bij en is veilig weg te halen. Twee soorten ongetagde versies zijn wél in gebruik:
# - de kinderen van een getagde index (multi-arch, of een image met attestation). Daarom eerst
#   vaststellen dat geen enkele getagde versie in dit package een index is; lukt dat niet, dan
#   blijft alles staan en volgt een melding.
# - een referrer: een handtekening of attestation (cosign, `docker buildx --attest`) die via
#   `subject` naar een ander manifest wijst en zelf geen tag heeft. Elk manifest wordt daarom eerst
#   opgehaald; een `subject`, of een manifest dat niet op te halen is, blijft staan.
# Liever een melding te veel dan een kapot image.
#
# VERWIJDER_MAX begrenst het aantal verwijderingen per aanroep, oudste eerst, en MANIFEST_MAX het
# aantal opgehaalde manifests; wat niet onderzocht is, komt als `uitgesteld` terug en wacht op de
# volgende run. Zo blijft een run binnen de API-limiet van GITHUB_TOKEN, ook bij een achterstand
# van honderden versies.
ongetagd() {
  local pkg=${1:-} max=${VERWIJDER_MAX:-100}
  [ -n "$pkg" ] && [ -n "${ORG:-}" ] && [ -n "${GH_TOKEN:-}" ] || onbepaald "Gebruik: ORG=… GH_TOKEN=… $0 ongetagd <package>"

  case "$max" in
    '' | *[!0-9]*) onbepaald "VERWIJDER_MAX moet een getal zijn." ;;
  esac

  local versies kandidaten getagd token digest type ids id fouten=0 rest

  versies=$(api "orgs/$ORG/packages/container/$pkg/versions?per_page=100" --paginate | jq -sc 'add') \
    || onbepaald "De versies van $pkg zijn niet op te vragen."

  # `id<TAB>digest`, oudste eerst.
  kandidaten=$(jq -r --argjson nu "$NU" --argjson min "$ONGETAGD_MINIMUM_SECONDEN" '[.[]
    | select((.metadata.container.tags // []) | length == 0)
    | select($nu - (.created_at | fromdateiso8601) >= $min)]
    | sort_by(.created_at)[] | "\(.id)\t\(.name)"' <<<"$versies") \
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

  # Doorlopen tot er `max` verwijderbare versies zijn: bewaarde referrers en onleesbare manifests
  # tellen niet mee voor dat plafond, anders zouden blijvende referrers onder de oudste versies de
  # plekken voorgoed bezetten. Het aantal manifest-opvragingen heeft een eigen plafond.
  local fetch_max=${MANIFEST_MAX:-300} gehaald=0 onderzocht=0 uitgesteld
  local referrers=0 onleesbaar=0 verwijderbaar=0 inhoud verwijderen=""

  case "$fetch_max" in
    '' | *[!0-9]*) onbepaald "MANIFEST_MAX moet een getal zijn." ;;
  esac

  while IFS=$'\t' read -r id digest; do
    [ -n "$id" ] || continue
    [ "$verwijderbaar" -lt "$max" ] && [ "$gehaald" -lt "$fetch_max" ] || break

    gehaald=$((gehaald + 1))
    onderzocht=$((onderzocht + 1))

    if ! inhoud=$(manifest "$pkg" "$digest" "$token") || ! jq -e 'type == "object"' <<<"$inhoud" >/dev/null 2>&1; then
      onleesbaar=$((onleesbaar + 1))
    elif jq -e 'has("subject")' <<<"$inhoud" >/dev/null; then
      referrers=$((referrers + 1))
    else
      verwijderen="$verwijderen$id"$'\n'
      verwijderbaar=$((verwijderbaar + 1))
    fi
  done <<<"$kandidaten"

  uitgesteld=$((aantal - onderzocht))

  # Een referrer hoort bij het manifest waar hij naar wijst en blijft dus bewust staan. Geen
  # melding: die zou blijven staan zolang het image bestaat, en een melding die nooit verdwijnt,
  # leert mensen hem te negeren.
  if [ "$referrers" -gt 0 ]; then
    printf '%s\treferrer\t%s\tverwijzen via subject naar een ander manifest\n' "$pkg" "$referrers"
  fi

  if [ "$onleesbaar" -gt 0 ]; then
    printf '%s\tbewaard\t%s\tmanifest niet op te halen\n' "$pkg" "$onleesbaar"
  fi

  if [ "$uitgesteld" -gt 0 ]; then
    printf '%s\tuitgesteld\t%s\tniet onderzocht: plafond van %s verwijderingen of %s manifests per run\n' \
      "$pkg" "$uitgesteld" "$max" "$fetch_max"
  fi

  [ -n "$verwijderen" ] || return 0

  for id in $verwijderen; do
    api "orgs/$ORG/packages/container/$pkg/versions/$id" -X DELETE >/dev/null 2>&1 || fouten=$((fouten + 1))
  done

  # Nameten op de lijst, niet op de exitcodes: een weigering van GitHub (de downloadteller) komt
  # ook als 4xx terug, en alleen wat aantoonbaar uit de lijst verdwenen is, telt als verwijderd.
  ids=$(api "orgs/$ORG/packages/container/$pkg/versions?per_page=100" --paginate | jq -r '.[].id') \
    || onbepaald "De versies van $pkg zijn na het verwijderen niet op te vragen."
  rest=$(grep -cxFf <(printf '%s' "$verwijderen") <<<"$ids" || true)

  printf '%s\tverwijderd\t%s\t\n' "$pkg" "$(($(grep -c . <<<"$verwijderen") - rest))"

  if [ "$rest" -gt 0 ]; then
    printf '%s\tmislukt\t%s\tstaan na het verwijderen nog in de lijst (%s weigeringen)\n' "$pkg" "$rest" "$fouten"
  fi
}

# De stand tussen runs, als regels `<soort>\t<n>`, in het artefact `preview-resten-stand`:
#   gelukt   cleanup-preview.yml meldde dit nummer deze run als opgeruimd
#   terug    een eerder als opgeruimd gemeld nummer dat toch weer als rest terugkwam; blijft in de
#            stand zolang het nummer terugkomt, zodat de melding niet om de andere run verdwijnt
#   laatste  het laatst geprobeerde nummer, waar `kies` de volgende run na begint
readonly STAND_ARTEFACT=preview-resten-stand

# De vorige stand uit NEEDS (`.inventaris.outputs.stand`).
vorige_stand_uit_needs() {
  jq -r '.inventaris.outputs.stand // ""' <<<"$NEEDS"
}

# De nummers die nu als rest terug zijn na een eerdere gemelde opruiming: (gelukt ∪ terug) uit de
# vorige stand, voor zover ze nu weer met klasse `opruimen` in de inventaris staan.
terug() {
  awk -F'\t' '
    FNR == NR { if ($1 == "gelukt" || $1 == "terug") eerder[$2] = 1; next }
    $2 == "opruimen" && ($1 in eerder) { print $1 }
  ' <(vorige_stand_uit_needs) <(jq -r '.inventaris.outputs.bevindingen // ""' <<<"$NEEDS") | sort -un
}

nieuwe_stand() {
  [ -n "${NEEDS+gezet}" ] || onbepaald "NEEDS is verplicht."
  jq -e 'type == "object"' <<<"$NEEDS" >/dev/null 2>&1 || onbepaald "NEEDS is geen toJSON(needs)-uitvoer."

  local uitkomsten laatste
  uitkomsten=$(jq -r '.opruimen.outputs.uitkomsten // ""' <<<"$NEEDS")

  awk -F'\t' '$2 == "success" { print "gelukt\t" $1 }' <<<"$uitkomsten"
  terug | sed 's/^/terug\t/'

  # Het laatst geprobeerde nummer; een uitgesteld nummer is niet geprobeerd. Probeerde deze run
  # niets, dan blijft de vorige waarde staan.
  laatste=$(awk -F'\t' 'NF && $2 != "uitgesteld" { n = $1 } END { print n }' <<<"$uitkomsten")
  [ -n "$laatste" ] || laatste=$(vorige_stand_uit_needs | awk -F'\t' '$1 == "laatste" { n = $2 } END { print n }')
  [ -z "$laatste" ] || printf 'laatste\t%s\n' "$laatste"
}

# Het meest recente stand-artefact van een run op main. Exit 1 als er geen is (eerste run, of
# verlopen): dat is een geldige toestand en geen fout. Exit 2 als de lijst of het artefact niet te
# lezen is; dat moet zichtbaar zijn, want dan vallen de rotatie en de terug-controle stil terug.
vorige_stand() {
  local doel=${1:-}
  [ -n "$doel" ] && [ -n "${REPO:-}" ] || onbepaald "Gebruik: REPO=… $0 vorige-stand <doelbestand>"

  local lijst id
  lijst=$(api "repos/$REPO/actions/artifacts?name=$STAND_ARTEFACT&per_page=100") \
    || onbepaald "De artefacten van $REPO zijn niet op te vragen."

  id=$(jq -er '.artifacts | if type == "array" then . else error("geen lijst") end
    | map(select(.expired == false and .workflow_run.head_branch == "main"))
    | sort_by(.created_at) | last | .id // ""' <<<"$lijst" 2>/dev/null) \
    || onbepaald "De artefactlijst van $REPO is onleesbaar."

  [ -n "$id" ] || return 1

  api "repos/$REPO/actions/artifacts/$id/zip" >"$doel.zip" \
    || onbepaald "Artefact $id is niet te downloaden."
  python3 -c 'import sys, zipfile; sys.stdout.buffer.write(zipfile.ZipFile(sys.argv[1]).read("stand.tsv"))' \
    "$doel.zip" >"$doel" 2>/dev/null \
    || onbepaald "Artefact $id bevat geen leesbare stand."
  rm -f "$doel.zip"
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

  # Waarom een nummer met klasse `opruimen` geen uitkomst heeft. Zo'n nummer staat er nog: het hoort
  # in de melding, anders sluit het issue terwijl er resten op hun beurt wachten.
  local reden
  if [ "${OPRUIMEN:-false}" != true ]; then
    reden="alleen geïnventariseerd; deze run ruimde niet op"
  elif [ "$resultaat" = success ] || [ "$resultaat" = skipped ]; then
    reden="niet aan de beurt (maximaal ${MAX_PER_RUN:-?} per run)"
  else
    reden="de opruimjob eindigde als $resultaat"
  fi

  # De inventaris, met per nummer de uitkomst van de opruiming ernaast. `onbepaald`-regels gaan
  # ongewijzigd door. Een nummer dat eerder met succes is opgeruimd en nu tóch weer als rest
  # terugkomt, wijst op een opruiming die groen meldt zonder alles weg te halen.
  awk -F'\t' -v reden="$reden" -v vorige=" $(terug | tr '\n' ' ') " '
    FNR == NR { if (NF) { status[$1] = $2; extra[$1] = $3 } next }
    $1 == "onbepaald"     { print; next }
    $2 == "geen-pr"       { print "geen-pr\tpr-" $1 "\tresten zonder bijbehorende PR: " $3; next }
    $2 == "vreemd-pakket" { print "vreemd-pakket\tpr-" $1 "\ttags in een package buiten de opruimlijst: " $3; next }
    $2 == "opruimen" {
      if ($3 ~ /vreemd-pakket:/) print "vreemd-pakket\tpr-" $1 "\ttags in een package buiten de opruimlijst: " $3
      if (index(vorige, " " $1 " ")) print "terug-na-opruiming\tpr-" $1 "\teen eerdere run meldde de opruiming als gelukt, maar er staat nog: " $3
      s = status[$1]
      if (s == "success") next
      if (s == "uitgesteld") { print "wacht-op-opruiming\tpr-" $1 "\tuitgesteld: " extra[$1]; next }
      if (s != "") { print "opruiming-mislukt\tpr-" $1 "\tcleanup-preview.yml eindigde als " s (extra[$1] ? " (" extra[$1] ")" : ""); next }
      wacht[++w] = $1
    }
    END { for (i = 1; i <= w; i++) print "wacht-op-opruiming\tpr-" wacht[i] "\t" reden "; " w " nummer(s) wachten nog" }
  ' <(jq -r '.opruimen.outputs.uitkomsten // ""' <<<"$NEEDS") <(jq -r '.inventaris.outputs.bevindingen // ""' <<<"$NEEDS")

  jq -r '.ongetagd.outputs.uitkomsten // ""' <<<"$NEEDS" | awk -F'\t' '
    $2 == "bewaard"    { print "ongetagd-bewaard\t" $1 "\t" $3 " ongetagde versie(s) blijven staan: " $4 }
    $2 == "mislukt"    { print "ongetagd-mislukt\t" $1 "\t" $3 " ongetagde versie(s) " $4 }
    $2 == "uitgesteld" { print "ongetagd-uitgesteld\t" $1 "\t" $3 " ongetagde versie(s) " $4 }
    $2 == "onbepaald"  { print "onbepaald\tghcr:" $1 "\t" $4 }'
}

readonly ISSUE_TITEL='Achtergebleven preview-resten'

# De nummers van de open meld-issues, oudste eerst. Alleen issues van de workflow zelf: een mens of
# een PR met dezelfde titel telt niet.
open_issues() {
  api "repos/$REPO/issues?state=open&per_page=100" --paginate \
    | jq -r --arg t "$ISSUE_TITEL" '.[] | select(.pull_request == null and .title == $t
      and .user.login == "github-actions[bot]") | .number' | sort -n
}

# De stand waarop een wijziging gemeld wordt. Per PR-nummer telt alleen dát er iets staat, niet
# waarom: een nummer dat blijft mislukken wisselt door de rotatie per run tussen "mislukt" en "wacht
# op zijn beurt", en elke wissel zou anders een bijgewerkte beschrijving plus comment opleveren. Het
# waarom staat in de beschrijving.
stand_van() {
  awk -F'\t' '{
      soort = $1
      if (soort == "opruiming-mislukt" || soort == "wacht-op-opruiming" || soort == "terug-na-opruiming") soort = "staat-nog"
      print soort "\t" $2
    }' "$1" | sort -u | sha256sum | cut -c1-16
}

# Houdt precies één issue bij met wat er is blijven staan. Een rode job in de Actions-tab ziet niemand
# op een geplande run; een issue komt in de notificaties van wie de repo volgt.
#
#   bevindingen leeg, geen issue  → niets (een controle die niets aantreft is stil)
#   bevindingen leeg, issue open  → sluiten met een afsluitende comment
#   bevindingen, geen issue       → aanmaken
#   bevindingen, issue open       → alleen bij een gewijzigde stand de beschrijving bijwerken plus
#                                   een comment; dezelfde stand elke dag opnieuw melden is ruis
#
# De stand wordt vergeleken via stand_van, niet op de toelichting: daar staan run-URL's in die elke
# run anders zijn.
issue() {
  local bestand=${1:-}
  [ -f "$bestand" ] && [ -n "${REPO:-}" ] && [ -n "${RUN_URL:-}" ] \
    || onbepaald "Gebruik: REPO=… RUN_URL=… $0 issue <bevindingen>"

  local nummers nummer extra stand vorige body

  nummers=$(open_issues) || onbepaald "De open issues van $REPO zijn niet op te vragen."
  nummer=$(head -1 <<<"$nummers")

  # Meer dan één open issue (een eerdere race, of een noodmelding): het oudste blijft, de rest
  # sluit met een verwijzing. Anders zouden de extra issues nooit meer dichtgaan.
  for extra in $(tail -n +2 <<<"$nummers"); do
    api "repos/$REPO/issues/$extra/comments" -X POST -f body="Dubbel van #$nummer; dat issue houdt de stand bij." >/dev/null \
      || onbepaald "Kon niet reageren op issue #$extra."
    api "repos/$REPO/issues/$extra" -X PATCH -f state=closed -f state_reason=not_planned >/dev/null \
      || onbepaald "Kon issue #$extra niet sluiten."
    echo "Issue #$extra gesloten als dubbel van #$nummer."
  done

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

  stand=$(stand_van "$bestand")

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

# Vangnet als `bevindingen` of `issue` zelf omvalt: dan zou de run rood eindigen zonder dat iemand
# het ziet, en de melding is juist het enige signaal van deze workflow. Minimaal, zodat er zo min
# mogelijk mis kan gaan: alleen de run-URL. De beschrijving zonder stand-markering zorgt dat de
# volgende geslaagde run het issue volledig bijwerkt.
noodmelding() {
  [ -n "${REPO:-}" ] && [ -n "${RUN_URL:-}" ] || onbepaald "REPO en RUN_URL zijn verplicht."

  local nummer tekst="De controle op preview-resten kon zijn bevindingen niet melden; zie $RUN_URL."

  # Een onleesbare lijst is geen lege lijst: dan een nieuw issue aanmaken zou een dubbel opleveren
  # naast een issue dat er misschien al staat. Hard falen; de rode job is dan het enige signaal.
  nummer=$(open_issues) || onbepaald "De open issues zijn niet op te vragen; geen noodmelding geplaatst."
  nummer=$(head -1 <<<"$nummer")

  if [ -n "$nummer" ]; then
    api "repos/$REPO/issues/$nummer/comments" -X POST -f body="$tekst" >/dev/null \
      || onbepaald "Ook de noodmelding op issue #$nummer mislukte."
    echo "Noodmelding geplaatst op issue #$nummer."
  else
    api "repos/$REPO/issues" -X POST -f title="$ISSUE_TITEL" -f body="$tekst" >/dev/null \
      || onbepaald "Ook de noodmelding mislukte."
    echo "Noodmelding als nieuw issue aangemaakt."
  fi
}

# Alleen uitvoeren bij directe aanroep, zodat de unittests de functies kunnen sourcen.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  opdracht=${1:-}
  shift || true

  case "$opdracht" in
    zad | github | classificeer | kies | opruimen | ongetagd | bevindingen | issue | noodmelding) "$opdracht" "$@" ;;
    vorige-stand) vorige_stand "$@" ;;
    nieuwe-stand) nieuwe_stand "$@" ;;
    *) onbepaald "Gebruik: $0 zad|github|classificeer|kies|opruimen|ongetagd|bevindingen|issue|noodmelding|vorige-stand|nieuwe-stand …" ;;
  esac
fi
