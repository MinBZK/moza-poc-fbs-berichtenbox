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

# De vorm toetsen vóór hij in compose.yaml belandt. `proeftuin-pin.sh` stelt de regel zelf samen, maar
# een lege of afgekapte waarde (een uitgebleven digest-lookup, een gewijzigd outputformaat) zou hier
# anders stilzwijgend een onbruikbare image-regel opleveren: de diff is dan niet leeg, de PR ziet er
# normaal uit, en pas een herstart van de demo loopt vast op een referentie die niemand kan trekken.
regel_is_welgevormd() {
  [[ ${1:-} =~ ^[[:space:]]+image:[[:space:]]ghcr\.io/[a-z0-9._/-]+:[A-Za-z0-9._-]+@sha256:[a-f0-9]{64}$ ]]
}

# Het register-pad uit de aangeboden regel zelf halen, zodat dit script geen tweede vastlegging van
# het image-pad draagt die bij een verhuizing stil achterblijft.
pad_uit_regel() {
  local zonder_prefix=${1#*image: }

  printf '%s' "${zonder_prefix%%[:@]*}"
}

# `isCrossRepository` eruit: `--head` matcht op branchnaam, dus een fork-PR met dezelfde naam zou
# hier als onze pin-PR gelden — en dan sluiten of overschrijven we andermans PR.
open_pin_pr() {
  gh pr list --head "$BRANCH" --state open --json number,isCrossRepository \
    --jq '[.[] | select(.isCrossRepository | not)] | .[0].number // empty'
}

vervang_pin() {
  local regel=$1 pad patroon aantal
  pad=$(pad_uit_regel "$regel")
  # Het pad in het patroon is een letterlijke tekst met punten en slashes erin; alleen de punten
  # hebben in een ERE betekenis.
  patroon="^[[:space:]]*image:[[:space:]]*$(printf '%s' "$pad" | sed 's/\./\\./g')[:@]"
  aantal=$(grep -cE "$patroon" "$COMPOSE" || true)

  # Precies één: bij nul is de regel van vorm veranderd of verdwenen, bij meer dan één zou dit
  # script er willekeurig één kiezen en de andere laten staan — en dan draait de demo op twee
  # verschillende berichtenboxen terwijl de PR er compleet uitziet.
  if [ "$aantal" -ne 1 ]; then
    echo "::error::$COMPOSE heeft $aantal image-regels voor ${pad}; verwacht precies één."
    return 1
  fi

  # awk en geen `sed -i`: de vervangende tekst bevat slashes en punten, en sed zou die als
  # scheidingsteken en metateken lezen. awk zet hem letterlijk neer.
  awk -v nieuw="$regel" -v patroon="$patroon" '
    $0 ~ patroon && !gedaan { print nieuw; gedaan = 1; next }
    { print }
  ' "$COMPOSE" > "$COMPOSE.nieuw"

  mv "$COMPOSE.nieuw" "$COMPOSE"
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
deze PR om bij is. Dependabot biedt deze bump niet meer aan: hij houdt de gesloten PR #271 vast als
bestaande PR voor dit image op versie \`latest\`, en elke volgende bump heet weer \`latest\`.

Een andere versie draaien dan de gepinde — een release-tag voor een gebruikersonderzoek, of nog niet
gemergd werk van hun kant — gaat via \`compose.proeftuin-versie.yaml\`, niet via deze regel.
EOM
}

# Sluiten en de branch opruimen in twee stappen: `gh pr close --delete-branch` wil ook de lokale
# branch weg en die bestaat in dit pad niet.
ruim_pin_pr_op() {
  local nummer=$1 status=0

  gh pr close "$nummer" \
    --comment "De pin in \`$COMPOSE\` hoort inmiddels bij de huidige main van de proeftuin; deze PR heeft geen wijziging meer te brengen."

  git ls-remote --exit-code --heads origin "$BRANCH" >/dev/null || status=$?

  # Alleen 2 betekent "die branch is er niet" — een vorige run die na de close afbrak, of iemand die
  # hem met de hand verwijderde. Elke andere code (128 bij een auth- of netwerkfout) zegt dat we het
  # niet weten, en dan is stil overslaan het slechtste antwoord: een ingetrokken `Contents: write`
  # zou zo elke run de opruiming overslaan terwijl de log meldt dat er opgeruimd is.
  case $status in
    0) git push origin --delete "$BRANCH" ;;
    2) echo "Branch $BRANCH bestond al niet meer." ;;
    *)
      echo "::error::kon niet vaststellen of $BRANCH nog bestaat (git ls-remote gaf $status)."
      return 1
      ;;
  esac
}

# Eén commit bovenop main, geen doorgroeiende branch: `switch -C` plus force-push zetten de
# pin-branch elke run opnieuw neer. Wat iemand er zelf op zette gaat daarmee weg — bedoeld, want deze
# PR hoort precies één image-regel te dragen. De commit is op compose.yaml begrensd.
publiceer_branch() {
  git config user.name "github-actions[bot]"
  git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
  git switch -C "$BRANCH"
  git commit -m "chore(demo): zet de berichtenbox op de huidige main van de proeftuin" -- "$COMPOSE"
  git push -f origin "$BRANCH"
}

bied_pin_aan() {
  local open_pr=$1 body

  if ! regel_is_welgevormd "${REGEL:-}"; then
    echo "::error::proeftuin-pin.sh leverde geen bruikbare image-regel ('${REGEL:-}') — er valt niets aan te bieden."
    return 1
  fi

  vervang_pin "$REGEL"

  # Vangnet onder `vervang_pin`: raakte de awk niets terwijl de telling wél één zei, dan zou een lege
  # PR volgen die niemand kan duiden.
  if git diff --quiet -- "$COMPOSE"; then
    echo "::error::de image-regel in $COMPOSE is niet gewijzigd terwijl dat wel had gemoeten."
    return 1
  fi

  publiceer_branch
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
  if [ -z "${GH_TOKEN:-}" ]; then
    echo "::error::$SECRET ontbreekt — de pin-PR kan niet aangemaakt worden. Zet de repo-secret (fine-grained PAT met Contents: write en Pull requests: write)."
    return 1
  fi

  if [ ! -r "$COMPOSE" ]; then
    echo "::error::$COMPOSE is niet te lezen — de pin is niet aan te bieden."
    return 1
  fi

  local open_pr
  open_pr=$(open_pin_pr)

  case "${STATUS:-}" in
    verouderd)
      bied_pin_aan "$open_pr"
      ;;
    ok)
      # De pin kan ook buiten deze PR om goed komen: iemand werkt hem met de hand bij, of een
      # preview-pin wordt teruggezet. Blijft de PR dan openstaan, dan draagt hij een diff die niets
      # meer verandert en kan een reviewer niet zien of hij nog actueel is.
      echo "De pin staat op de huidige main van de proeftuin."

      if [ -n "$open_pr" ]; then
        ruim_pin_pr_op "$open_pr"
        echo "Openstaande pin-PR #$open_pr gesloten."
      fi
      ;;
    preview|ontbreekt|oncontroleerbaar)
      # Achtereenvolgens: iemand beproeft bewust hun nog niet gemergde werk, hun bouw loopt nog of
      # viel, en de controle kon deze run niets vaststellen. In alle drie is er niets vast te stellen
      # om aan te bieden. Een openstaande PR blijft staan: die droeg een wél vastgestelde bevinding,
      # en hem hier sluiten zou die stilzwijgend intrekken.
      echo "::warning::Pin niet aangeboden (status=${STATUS}); een openstaande pin-PR blijft staan."
      ;;
    *)
      # pin-onvindbaar, bron-weg, geen-pin en alles wat niet bestaat. Geen van deze laat zich met een
      # PR oplossen, en stil groen worden zou de dagelijkse run waardeloos maken: bij een verdwenen
      # image loopt de eerstvolgende herstart van het component vast.
      echo "::error::Pin niet aan te bieden (status=${STATUS:-<leeg>}); zie de job proeftuin-pin in pin-consistency.yml voor wat deze status betekent."
      return 1
      ;;
  esac
}

# Sourcen voert main niet uit, zodat de suite het script kan laden zonder een PR te openen.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
