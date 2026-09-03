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
#   GH_TOKEN   — PAT met Contents: write en Pull requests: write
#   DOCKERFILE — te wijzigen bestand (default .clusterfuzzlite/Dockerfile)
#   BRANCH     — branch waarop de pin wordt aangeboden (default chore/fuzz-basis-pin)
set -euo pipefail

DOCKERFILE=${DOCKERFILE:-.clusterfuzzlite/Dockerfile}
BRANCH=${BRANCH:-chore/fuzz-basis-pin}

# De digest komt uit `docker buildx imagetools inspect --format`. Dat commando eindigt ook met 0 als
# het template niets oplevert, en dan draagt DIGEST alleen nog het image-pad. Zonder deze controle
# is `FROM ghcr.io/…@` een prefix van élke gepinde FROM-regel: de vergelijking hieronder matcht, het
# script concludeert "pin staat al goed" en sluit de PR die de echte wijziging droeg.
digest_is_welgevormd() {
  printf '%s' "${1:-}" | grep -qE '^[a-z0-9.]+/[a-z0-9._/-]+@sha256:[a-f0-9]{64}$'
}

# `-x`: de hele regel moet gelijk zijn. Een niet-geankerde vergelijking leest ook het commentaarblok
# bovenin het Dockerfile mee, en juist daar staat de FROM-regel die de bouw als voorbeeld print.
pin_is_actueel() {
  grep -qxF "FROM $1" "$DOCKERFILE"
}

# Het image-pad komt uit DIGEST zelf, niet uit een tweede vastlegging die met de workflow-env in
# sync gehouden moet worden. Wijst de FROM-regel naar een ánder image, dan matcht de sed niet en is
# dat een harde fout — geen stille no-op.
vervang_pin() {
  local digest=$1 pad_regex
  pad_regex=$(printf '%s' "${digest%@*}" | sed 's/[].[^$*\/]/\\&/g')

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

# Sluiten en de branch opruimen in twee stappen: `gh pr close --delete-branch` wil ook de lokale branch
# weg en die bestaat in dit pad niet. De ls-remote ervoor houdt het idempotent — heeft de repo
# "automatically delete head branches" aan, dan is de branch al weg en is dat geen fout.
ruim_pin_pr_op() {
  local nummer=$1

  gh pr close "$nummer" \
    --comment "De pin in \`$DOCKERFILE\` hoort inmiddels bij het huidige basis-image; deze PR heeft geen wijziging meer te brengen."

  if git ls-remote --exit-code --heads origin "$BRANCH" >/dev/null 2>&1; then
    git push origin --delete "$BRANCH"
  fi
}

publiceer_tak() {
  git config user.name "github-actions[bot]"
  git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
  git switch -C "$BRANCH"
  git commit -am "chore(ci): pin het fuzz-basis-image op de huidige pom-set"
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

  local open_pr body
  open_pr=$(open_pin_pr)

  # De pin kan ook buiten deze PR om goed komen: iemand werkt hem met de hand bij, of een herbouw
  # levert dezelfde digest. Blijft de PR dan openstaan, dan draagt hij een diff die niets meer
  # verandert en kan een reviewer niet zien of hij nog actueel is.
  if pin_is_actueel "$DIGEST"; then
    echo "De pin hoort al bij dit image."

    if [ -n "$open_pr" ]; then
      ruim_pin_pr_op "$open_pr"
      echo "Openstaande pin-PR #$open_pr gesloten."
    fi

    return 0
  fi

  vervang_pin "$DIGEST"

  # Raakt de sed niets, dan wijst de FROM-regel naar een ander image of is hij van vorm veranderd.
  # Doorgaan zou een lege PR opleveren die niemand kan duiden.
  if git diff --quiet -- "$DOCKERFILE"; then
    echo "::error::de FROM-regel in $DOCKERFILE is niet herkend — wijst hij naar een ander image, of is de vorm gewijzigd?"
    return 1
  fi

  publiceer_tak
  body=$(pr_body "${POMS:-onbekend}")

  # Ook de body verversen: hij draagt de pom-hash, en dat is waaraan een reviewer ziet bij wélke
  # dependency-set de aangeboden digest hoort.
  if [ -n "$open_pr" ]; then
    gh pr edit "$open_pr" --body "$body"
    echo "Openstaande pin-PR #$open_pr bijgewerkt naar de nieuwe digest."
    return 0
  fi

  gh pr create --base main --head "$BRANCH" \
    --title "chore(ci): pin het fuzz-basis-image op de huidige pom-set" \
    --body "$body"
}

# Sourcen voert main niet uit, zodat de suite de functies los kan toetsen.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
