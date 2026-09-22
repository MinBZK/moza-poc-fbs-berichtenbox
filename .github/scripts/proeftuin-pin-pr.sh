#!/usr/bin/env bash
#
# Biedt een verouderde berichtenbox-pin (de proeftuin, MinBZK/moza-poc) als pull request aan, en
# ruimt die PR op zodra de pin buiten deze weg om weer bij is.
#
# Dit vervangt Dependabot voor dít image. Zijn docker_compose-updater kan de bump niet meer
# aanbieden: hij houdt PR #271 vast als "bestaande PR voor minbzk/moza-poc op versie latest", en
# omdat elke volgende bump van datzelfde image opnieuw `latest` heet, matcht die vastlegging altijd.
# In de joblog staat dan `Pull request #271 already exists for minbzk/moza-poc with latest version
# latest` en gebeurt er verder niets — ook niet na `@dependabot recreate` of een handmatige run
# vanaf de Dependency graph. De vastlegging is niet op te ruimen zolang die PR bestaat, en PR's zijn
# niet te verwijderen. Zonder dit script komt de bump dus nooit meer langs.
#
# De uitkomst van .github/scripts/proeftuin-pin.sh is de invoer; dat script bepaalt wat de stand is,
# dit script handelt er alleen naar. Verwacht in de omgeving:
#   STATUS   — status uit proeftuin-pin.sh
#   REGEL    — de complete compose-regel die gezet moet worden (alleen bij `verouderd`)
#   TAG      — de sha-tag van hun main-commit, voor de PR-tekst
#   HUIDIG   — de referentie die er nu staat, voor de PR-tekst
#   GH_TOKEN — PAT met Contents: write en Pull requests: write (FUZZ_PIN_TOKEN, gedeeld met
#              fuzz-base-image.yml); dekt alleen de gh-aanroepen, de git push leunt op de
#              credentials die de checkout in .git/config achterlaat
#   COMPOSE / BRANCH — te wijzigen bestand en de branch waarop de pin wordt aangeboden; alleen de
#              suite zet deze
set -euo pipefail

COMPOSE=${COMPOSE:-compose.yaml}
BRANCH=${BRANCH:-chore/proeftuin-pin}
SECRET=FUZZ_PIN_TOKEN

# Het PR-onderhoud zelf is gedeeld met fuzz-basis-pin.sh: token eisen, de eigen PR vinden, de branch
# publiceren en de PR opruimen. Wat hieronder staat is het deel dat van dít pad is.
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=.github/scripts/pin-pr-lib.sh
source "$HERE/pin-pr-lib.sh"

# De vorm toetsen vóór hij in compose.yaml belandt. `proeftuin-pin.sh` stelt de regel zelf samen, maar
# een lege of afgekapte waarde (een uitgebleven digest-lookup, een gewijzigd outputformaat) zou hier
# anders stilzwijgend een onbruikbare image-regel opleveren: de diff is dan niet leeg, de PR ziet er
# normaal uit, en pas een herstart van de demo loopt vast op een referentie die niemand kan trekken.
#
# `[[:blank:]]` en niet `[[:space:]]`: die laatste dekt ook een newline, en dan zou een meerregelige
# waarde — de vorm die uit GITHUB_OUTPUT kan komen — op zijn tweede regel alsnog slagen. `[[ =~ ]]`
# en geen `grep -E` om dezelfde reden: grep ankert per regel.
regel_is_welgevormd() {
  [[ ${1:-} =~ ^[[:blank:]]+image:[[:blank:]]ghcr\.io/[a-z0-9._/-]+:[A-Za-z0-9._-]+@sha256:[a-f0-9]{64}$ ]]
}

# De referentie uit de aangeboden regel, zonder de witruimte die er in die regel voor staat.
referentie_uit_regel() {
  local rest=${1##*image:}

  printf '%s' "${rest#"${rest%%[![:blank:]]*}"}"
}

# Het register-pad uit de aangeboden regel zelf halen, zodat dit script geen tweede vastlegging van
# het image-pad draagt die bij een verhuizing stil achterblijft.
pad_uit_regel() {
  local referentie
  referentie=$(referentie_uit_regel "$1")

  printf '%s' "${referentie%%[:@]*}"
}

vervang_pin() {
  local regel=$1 pad aantal status=0 tijdelijk aanhaling

  pad=$(pad_uit_regel "$regel")

  # De twee aanhalingstekens als tekenklasse-inhoud, opgebouwd in plaats van letterlijk genoteerd:
  # zo staat er geen `\"'` in een toewijzing, waar het een shell-quoting-fout lijkt terwijl het om
  # tekens in een reguliere expressie gaat.
  aanhaling=$(printf '"\x27')

  # Het pad is letterlijke tekst met punten en slashes erin; alleen de punten hebben in een ERE
  # betekenis. Via de omgeving naar awk en niet via `-v`: awk verwerkt escape-sequences in een
  # `-v`-toewijzing en maakt van `\.` een gewone punt, waarmee de vervanging losser zou matchen dan
  # de telling die erover besliste.
  PIN_PAD=$(printf '%s' "$pad" | sed 's/\./\\./g')
  # Tellen op een regel die met `image:` begint, zodat een commentaarregel die de referentie citeert
  # niet meetelt. Een aanhalingsteken ervoor mag: compose leest die vorm, en proeftuin-image.sh
  # ondersteunt hem expliciet.
  PIN_REGELPATROON="^[[:blank:]]*image:[[:blank:]]*[${aanhaling}]?${PIN_PAD}[:@]"
  # Alleen de referentie zelf vervangen, niet de hele regel: inspringing, aanhalingstekens en een
  # toelichting achter de pin blijven zo staan. Die platslaan zou stilzwijgend weggooien wat iemand
  # er bewust bij zette, elke run opnieuw.
  PIN_REFERENTIEPATROON="${PIN_PAD}[:@][^[:space:]${aanhaling}]+"
  PIN_NIEUWE_REFERENTIE=$(referentie_uit_regel "$regel")
  export PIN_PAD PIN_REGELPATROON PIN_REFERENTIEPATROON PIN_NIEUWE_REFERENTIE

  # `grep -c` kent drie uitkomsten: 0 = gevonden, 1 = niets gevonden, 2 = kon niet zoeken. Met
  # `|| true` erachter zou die derde als een lege telling doorgaan, en dan faalt de toets hieronder
  # open — hij slaat de guard over op het moment dat er niets gemeten is.
  aantal=$(grep -cE "$PIN_REGELPATROON" "$COMPOSE") || status=$?

  case $status in
    0|1) ;;
    *)
      echo "::error::kon $COMPOSE niet doorzoeken (grep gaf $status)."
      return 1
      ;;
  esac

  # Precies één: bij nul is de regel van vorm veranderd of verdwenen, bij meer dan één zou dit
  # script er willekeurig één kiezen en de andere laten staan — en dan draait de demo op twee
  # verschillende berichtenboxen terwijl de PR er compleet uitziet.
  if [ "$aantal" -ne 1 ]; then
    echo "::error::$COMPOSE heeft $aantal image-regels voor ${pad}; verwacht precies één."
    return 1
  fi

  tijdelijk=$(mktemp) || return 1

  # De uitkomst van awk expliciet toetsen in plaats van op `errexit` in de aanroepcontext te leunen:
  # zodra deze functie ooit in een `if` of achter een `&&` belandt, is die uit — en dan zou een
  # halverwege afgebroken awk een leeg bestand over compose.yaml zetten.
  if ! awk '
    $0 ~ ENVIRON["PIN_REGELPATROON"] && !gedaan {
      if (match($0, ENVIRON["PIN_REFERENTIEPATROON"])) {
        $0 = substr($0, 1, RSTART - 1) ENVIRON["PIN_NIEUWE_REFERENTIE"] substr($0, RSTART + RLENGTH)
        gedaan = 1
      }
    }
    { print }
  ' "$COMPOSE" > "$tijdelijk"; then
    rm -f "$tijdelijk"
    echo "::error::awk kon $COMPOSE niet herschrijven."
    return 1
  fi

  if [ ! -s "$tijdelijk" ]; then
    rm -f "$tijdelijk"
    echo "::error::de herschreven $COMPOSE is leeg; er is niets weggeschreven."
    return 1
  fi

  # `cat` en geen `mv`: de rechten en de eigenaar blijven zo die van compose.yaml in plaats van die
  # van een bestand uit de tijdelijke map.
  cat "$tijdelijk" > "$COMPOSE"
  rm -f "$tijdelijk"
}

pr_body() {
  cat <<EOM
De berichtenbox van de demo loopt achter op de main van [MinBZK/moza-poc](https://github.com/MinBZK/moza-poc).

| | |
|---|---|
| stond op | \`$HUIDIG\` |
| gaat naar | \`$TAG\` |

**Dit vraagt een oordeel, geen automatische merge.** Hun main draagt ook werk dat halfaf kan zijn.
Klik de demo door (\`--profile demo\`) voordat je deze PR merget; een oudere berichtenbox is een
oudere demo, geen kapotte build, dus laten staan mag ook.

Deze PR wordt bij elke run opnieuw op de laatste stand gezet, en sluit zichzelf zodra de pin buiten
deze PR om bij is. Sluiten is dus geen manier om de bump te weigeren: de volgende run opent hem
opnieuw. Wil je een versie overslaan, laat deze PR dan staan tot de volgende langskomt.

Dependabot biedt deze bump niet meer aan; de reden staat bij de \`ignore\`-regel in
\`.github/dependabot.yml\`.

Een andere versie draaien dan de gepinde — een release-tag voor een gebruikersonderzoek, of nog niet
gemergd werk van hun kant — gaat via \`compose.proeftuin-versie.yaml\`, niet via deze regel.
EOM
}

bied_pin_aan() {
  local open_pr=$1 body

  if ! regel_is_welgevormd "${REGEL:-}"; then
    echo "::error::proeftuin-pin.sh leverde geen bruikbare image-regel ('${REGEL:-}') — er valt niets aan te bieden."
    return 1
  fi

  # De aangeboden regel bepaalt wélke regel in compose.yaml geraakt wordt. Wijst hij naar een ander
  # image dan de pin die er nu staat, dan zou dit script een vreemde image-regel herschrijven in een
  # PR die er normaal uitziet — bijvoorbeeld als de bron zijn uitvoerformaat wijzigt of met een
  # overschreven MAIN_PAD draait. Dit script kent de berichtenbox verder nergens bij naam.
  if [ "$(pad_uit_regel "$REGEL")" != "${HUIDIG%%[:@]*}" ]; then
    echo "::error::de aangeboden regel wijst naar $(pad_uit_regel "$REGEL"), terwijl de pin op ${HUIDIG%%[:@]*} staat."
    return 1
  fi

  vervang_pin "$REGEL"

  # Vangnet onder `vervang_pin`: raakte de awk niets terwijl de telling wél één zei, dan zou een lege
  # PR volgen die niemand kan duiden.
  if git diff --quiet -- "$COMPOSE"; then
    echo "::error::de image-regel in $COMPOSE is niet gewijzigd terwijl dat wel had gemoeten."
    return 1
  fi

  pin_pr_publiceer_branch "$BRANCH" "$COMPOSE" \
    "chore(demo): zet de berichtenbox op de huidige main van de proeftuin"
  body=$(pr_body)

  # Ook de body verversen: hij noemt de stand van déze run.
  if [ -n "$open_pr" ]; then
    gh pr edit "$open_pr" --body "$body"
    echo "Openstaande pin-PR #$open_pr bijgewerkt naar $TAG."
    return 0
  fi

  gh pr create --base main --head "$BRANCH" \
    --title "chore(demo): zet de berichtenbox op de huidige main van de proeftuin" \
    --body "$body"
}

main() {
  # Kaal, zonder `|| return 1`: dat zou `errexit` in de hele functie uitzetten, en dan zou een fout
  # in een zwaardere controle die hier ooit bij komt stilzwijgend doorlopen.
  pin_pr_vereis_token "$SECRET"

  if [ ! -r "$COMPOSE" ]; then
    echo "::error::$COMPOSE is niet te lezen — de pin is niet aan te bieden."
    return 1
  fi

  local open_pr
  open_pr=$(pin_pr_open_pr "$BRANCH")

  case "${STATUS:-}" in
    verouderd)
      bied_pin_aan "$open_pr"
      ;;
    ok)
      # De pin kan ook buiten deze PR om goed komen: iemand werkt hem met de hand bij, of een
      # preview-pin wordt teruggezet. Blijft de PR dan openstaan, dan draagt hij een diff die niets
      # meer verandert en kan een reviewer niet zien of hij nog actueel is.
      echo "De pin staat op de huidige main van de proeftuin."

      # Ook zónder open PR opruimen: een vorige run die tussen het sluiten en het verwijderen
      # afbrak laat een branch achter die anders nooit meer wordt aangeraakt, en die de
      # eerstvolgende bump dan met de historie van die vorige cyclus zou dragen.
      pin_pr_ruim_op "$open_pr" "$BRANCH" \
        "De pin in \`$COMPOSE\` hoort inmiddels bij de huidige main van de proeftuin; deze PR heeft geen wijziging meer te brengen."

      if [ -n "$open_pr" ]; then
        echo "Openstaande pin-PR #$open_pr gesloten."
      fi
      ;;
    preview|ontbreekt)
      # Iemand beproeft bewust hun nog niet gemergde werk, of hun bouw loopt nog. In beide is er
      # niets vast te stellen om aan te bieden, en beide lossen zichzelf op. Een openstaande PR
      # blijft staan: die droeg een wél vastgestelde bevinding, en hem hier sluiten zou die
      # stilzwijgend intrekken.
      echo "::warning::Pin niet aangeboden (status=${STATUS}); een openstaande pin-PR blijft staan."
      ;;
    oncontroleerbaar)
      # "Er is deze run niets vastgesteld" — ghcr of de GitHub-API gaf 401, 429, 5xx of niets. Op een
      # PR is dat een tijdelijke hik die een ongerelateerde wijziging niet hoort te blokkeren, dus
      # daar blijft het een waarschuwing. Hier niet: dit is de enige plek waar het zichtbaar wordt.
      # Blijft die kant knijpen, dan waarschuwt deze run weken op groen terwijl de bump uitblijft, en
      # niemand opent de annotaties van een geslaagde geplande run.
      echo "::error::De pin is deze run NIET gecontroleerd (ghcr of GitHub niet te bevragen); er is dus niets vastgesteld om aan te bieden."
      return 1
      ;;
    *)
      # pin-onvindbaar, bron-weg, geen-pin en alles wat niet bestaat. Geen van deze laat zich met een
      # PR oplossen, en stil groen worden zou deze run waardeloos maken: bij een verdwenen image
      # loopt de eerstvolgende herstart van het component vast.
      echo "::error::Pin niet aan te bieden (status=${STATUS:-<leeg>}); de statuslijst staat in de kop van proeftuin-pin.sh."
      return 1
      ;;
  esac
}

# Sourcen voert main niet uit, zodat de suite het script kan laden zonder een PR te openen.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
