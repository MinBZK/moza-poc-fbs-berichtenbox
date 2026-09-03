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

# `isCrossRepository` eruit: `--head` matcht op branchnaam, dus een fork-PR met dezelfde naam zou hier
# als "onze" pin-PR gelden — en dan sluiten we andermans PR.
open_pin_pr() {
  gh pr list --head "$BRANCH" --state open --json number,isCrossRepository \
    --jq '[.[] | select(.isCrossRepository | not)] | .[0].number // empty'
}

pr_body() {
  cat <<EOM
Automatisch aangemaakt na een geslaagde bouw van het fuzz-basis-image.

pom-hash: \`$1\`

Zolang deze pin achterloopt, betaalt elke fuzz-run de volle voorbereiding (~100s extra).
Wijzigen de dependency-declaraties vóór de merge, dan ververst een volgende bouw deze PR.
EOM
}

# Sluiten en de branch opruimen in twee stappen: `gh pr close --delete-branch` wil ook de lokale
# branch weg en die bestaat in dit pad niet.
ruim_pin_pr_op() {
  local nummer=$1 status=0

  gh pr close "$nummer" \
    --comment "De pin in \`$DOCKERFILE\` hoort inmiddels bij het huidige basis-image; deze PR heeft geen wijziging meer te brengen."

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

# Eén commit bovenop main, geen doorgroeiende branch: `switch -C` plus force-push zetten de pin-branch
# elke bouw opnieuw neer. Wat iemand er zelf op zette gaat daarmee weg — bedoeld, want deze PR hoort
# precies één FROM-regel te dragen. De commit is op het Dockerfile begrensd, net als de guard die
# ervoor bepaalt of er iets te committen valt.
publiceer_branch() {
  git config user.name "github-actions[bot]"
  git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
  git switch -C "$BRANCH"
  git commit -m "chore(ci): pin het fuzz-basis-image op de huidige pom-set" -- "$DOCKERFILE"
  git push -f origin "$BRANCH"
}

main() {
  if [ -z "${GH_TOKEN:-}" ]; then
    echo "::error::FUZZ_PIN_TOKEN ontbreekt — de pin-PR kan niet aangemaakt worden. Zet de repo-secret (fine-grained PAT met Contents: write en Pull requests: write)."
    return 1
  fi

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
  open_pr=$(open_pin_pr)

  # De pin kan ook buiten deze PR om goed komen: iemand werkt hem met de hand bij, of de bouw levert
  # bij uitzondering dezelfde digest. Blijft de PR dan openstaan, dan draagt hij een diff die niets
  # meer verandert en kan een reviewer niet zien of hij nog actueel is.
  if pin_is_actueel "$DIGEST"; then
    echo "De pin hoort al bij dit image."

    if [ -n "$open_pr" ]; then
      ruim_pin_pr_op "$open_pr"
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

  publiceer_branch
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
