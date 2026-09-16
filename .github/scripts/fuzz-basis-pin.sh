#!/usr/bin/env bash
# Werkt de digest-pin in .clusterfuzzlite/Dockerfile bij naar het zojuist gebouwde basis-image en
# onderhoudt de pull request die die wijziging aanbiedt.
#
# Los script en niet inline in de workflow, omdat het gedrag zich niet uit de exitcode laat aflezen:
# "de pin stond al goed" en "de vergelijking matchte per ongeluk" eindigen allebei met 0, maar de
# tweede sluit ongemerkt een PR die de fix droeg. Alleen een suite die de gh-aanroepen vastlegt,
# houdt die twee uit elkaar — zie test-fuzz-basis-pin.sh.
#
# Verwacht in de omgeving:
#   DIGEST     — image@sha256:… zoals de registry het onder de zojuist gepushte tag serveert
#   POMS       — pom-hash van de dependency-set waar dat image bij hoort (gaat de PR-body in)
#   GH_TOKEN   — PAT met Contents: write en Pull requests: write; dekt alleen de gh-aanroepen, de
#                git push leunt op de credentials die de checkout in .git/config achterlaat
#   DOCKERFILE — te wijzigen bestand (default .clusterfuzzlite/Dockerfile; alleen de suite zet dit)
#   BRANCH     — branch waarop de pin wordt aangeboden (default chore/fuzz-basis-pin; idem)
set -euo pipefail

DOCKERFILE=${DOCKERFILE:-.clusterfuzzlite/Dockerfile}
BRANCH=${BRANCH:-chore/fuzz-basis-pin}

# Het PR-onderhoud zelf is gedeeld met proeftuin-pin-pr.sh: token eisen, de eigen PR vinden, de
# branch publiceren en de PR opruimen. Wat hieronder staat is het deel dat van dít pad is.
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=.github/scripts/pin-pr-lib.sh
source "$HERE/pin-pr-lib.sh"

# De digest komt uit `docker buildx imagetools inspect --format`. Dat commando eindigt ook met 0 als
# het template niets oplevert, en dan draagt DIGEST alleen nog het image-pad. `vervang_pin` zet die
# afgekapte waarde daarna gewoon in de FROM-regel: de sed raakt wél iets, de diff is niet leeg, en de
# PR biedt een `FROM …@` aan zonder digest — een image dat niemand kan trekken.
#
# `[[ =~ ]]` en niet `grep -E`: die laatste ankert per regel, dus een waarde met een newline erin zou
# op zijn tweede regel alsnog slagen. Deze functie bestaat juist als vangnet tegen onverwachte vorm.
digest_is_welgevormd() {
  [[ ${1:-} =~ ^[a-z0-9.]+/[a-z0-9._/-]+@sha256:[a-f0-9]{64}$ ]]
}

# `-x`: de hele regel moet gelijk zijn. Zonder anker telt ook een commentaarregel die een FROM-regel
# citeert (een voorbeeld, een oude pin) als "de pin staat al goed" — en dan sluit dit script de PR
# die de echte wijziging droeg.
pin_is_actueel() {
  grep -qxF "FROM $1" "$DOCKERFILE"
}

# Het image-pad komt uit DIGEST zelf, niet uit een tweede vastlegging die met de workflow-env in sync
# gehouden moet worden. De vervangkant is bewust níét ge-escaped; dat mag alleen omdat
# `digest_is_welgevormd` de tekenset al tot een pad plus hex beperkt.
vervang_pin() {
  local digest=$1 pad_regex aantal
  pad_regex=$(printf '%s' "${digest%@*}" | sed 's/[].[^$*\/]/\\&/g')
  aantal=$(grep -cE "^FROM +${pad_regex}@sha256:[a-f0-9]{64}$" "$DOCKERFILE" || true)

  # Precies één: bij nul wijst de FROM-regel ergens anders heen of is hij van vorm veranderd, bij
  # meer dan één (een multi-stage Dockerfile) zou de sed ze allemaal raken behalve die met een
  # `AS <naam>` erachter — en dat levert stil een half bijgewerkt bestand op.
  if [ "$aantal" -ne 1 ]; then
    echo "::error::$DOCKERFILE heeft $aantal FROM-regels die op ${digest%@*} pinnen; verwacht precies één."
    return 1
  fi

  sed -i -E "s|^FROM +${pad_regex}@sha256:[a-f0-9]{64}\$|FROM ${digest}|" "$DOCKERFILE"
}

pr_body() {
  cat <<EOM
Automatisch aangemaakt na een geslaagde bouw van het fuzz-basis-image.

pom-hash: \`$1\`

Zolang deze pin achterloopt, betaalt elke fuzz-run de volle voorbereiding (~100s extra).
Wijzigen de dependency-declaraties vóór de merge, dan ververst een volgende bouw deze PR.
EOM
}

main() {
  pin_pr_vereis_token FUZZ_PIN_TOKEN || return 1

  if ! digest_is_welgevormd "${DIGEST:-}"; then
    echo "::error::de bouw leverde geen bruikbare digest ('${DIGEST:-}') — de pin is niet te bepalen."
    return 1
  fi

  # Zonder deze controle antwoordt `pin_is_actueel` op een onleesbaar bestand met "niet actueel" —
  # de verkeerde richting voor "niet vast te stellen", en de run struikelt pas een stap later over
  # een kale tool-fout die de oorzaak niet noemt.
  if [ ! -r "$DOCKERFILE" ]; then
    echo "::error::$DOCKERFILE is niet te lezen — de pin is niet te vergelijken."
    return 1
  fi

  local open_pr body
  open_pr=$(pin_pr_open_pr "$BRANCH")

  # De pin kan ook buiten deze PR om goed komen: iemand werkt hem met de hand bij, of de bouw levert
  # bij uitzondering dezelfde digest. Blijft de PR dan openstaan, dan draagt hij een diff die niets
  # meer verandert en kan een reviewer niet zien of hij nog actueel is.
  if pin_is_actueel "$DIGEST"; then
    echo "De pin hoort al bij dit image."

    if [ -n "$open_pr" ]; then
      pin_pr_ruim_op "$open_pr" "$BRANCH" \
        "De pin in \`$DOCKERFILE\` hoort inmiddels bij het huidige basis-image; deze PR heeft geen wijziging meer te brengen."
      echo "Openstaande pin-PR #$open_pr gesloten."
    fi

    return 0
  fi

  vervang_pin "$DIGEST"

  # Vangnet onder `vervang_pin`: raakte de sed niets terwijl de telling wél één zei, dan zou een lege
  # PR volgen die niemand kan duiden.
  if git diff --quiet -- "$DOCKERFILE"; then
    echo "::error::de FROM-regel in $DOCKERFILE is niet gewijzigd terwijl dat wel had gemoeten."
    return 1
  fi

  pin_pr_publiceer_branch "$BRANCH" "$DOCKERFILE" \
    "chore(ci): pin het fuzz-basis-image op de huidige pom-set"
  body=$(pr_body "${POMS:-onbekend}")

  # Ook de body verversen: hij draagt de pom-hash van déze bouw.
  if [ -n "$open_pr" ]; then
    gh pr edit "$open_pr" --body "$body"
    echo "Openstaande pin-PR #$open_pr bijgewerkt naar de nieuwe digest."
    return 0
  fi

  gh pr create --base main --head "$BRANCH" \
    --title "chore(ci): pin het fuzz-basis-image op de huidige pom-set" \
    --body "$body"
}

# Sourcen voert main niet uit, zodat de suite het script kan laden zonder een PR te openen.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
