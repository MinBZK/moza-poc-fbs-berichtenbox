#!/usr/bin/env bash
# shellcheck shell=bash
#
# Gedeeld PR-onderhoud voor de twee pin-scripts: fuzz-basis-pin.sh (het fuzz-basis-image) en
# proeftuin-pin-pr.sh (de berichtenbox van de demo).
#
# Beide bieden één regel in één bestand aan op een vaste branch, en beide moeten daarvoor dezelfde
# vier dingen goed doen: het token eisen, de eigen PR vinden zonder een fork-PR te raken, de branch
# als één commit bovenop main neerzetten, en de PR opruimen zodra hij niets meer verandert. Dat deel
# staat hier. Wat "verouderd" betekent en welke regel vervangen wordt, verschilt per pad en blijft in
# het aanroepende script — daar zit het oordeel, en dat hoort niet gedeeld te worden.
#
# Dit bestand wordt gesourcet en definieert alleen functies; het doet uit zichzelf niets.

# De secretnaam als argument, want de melding moet de naam noemen die in de workflow staat: een
# generieke "het token ontbreekt" laat degene die hem leest zoeken naar wélke secret.
pin_pr_vereis_token() {
  local secret=$1

  if [ -z "${GH_TOKEN:-}" ]; then
    echo "::error::$secret ontbreekt — de pin-PR kan niet aangemaakt worden. Zet de repo-secret (fine-grained PAT met Contents: write en Pull requests: write)."
    return 1
  fi
}

# `isCrossRepository` eruit: `--head` matcht op branchnaam, dus een fork-PR met dezelfde naam zou
# hier als onze pin-PR gelden — en dan sluiten of overschrijven we andermans PR.
pin_pr_open_pr() {
  local branch=$1

  gh pr list --head "$branch" --state open --json number,isCrossRepository \
    --jq '[.[] | select(.isCrossRepository | not)] | .[0].number // empty'
}

# Eén commit bovenop main, geen doorgroeiende branch: `switch -C` plus force-push zetten de pin-branch
# elke run opnieuw neer. Wat iemand er zelf op zette gaat daarmee weg — bedoeld, want deze PR hoort
# precies één gewijzigde regel te dragen. De commit is op dat ene bestand begrensd, net als de guard
# die ervoor bepaalt of er iets te committen valt.
pin_pr_publiceer_branch() {
  local branch=$1 bestand=$2 bericht=$3

  git config user.name "github-actions[bot]"
  git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
  git switch -C "$branch"
  git commit -m "$bericht" -- "$bestand"
  git push -f origin "$branch"
}

# Sluiten en de branch opruimen in twee stappen: `gh pr close --delete-branch` wil ook de lokale
# branch weg en die bestaat in dit pad niet.
pin_pr_ruim_op() {
  local nummer=$1 branch=$2 reden=$3 status=0

  gh pr close "$nummer" --comment "$reden"

  git ls-remote --exit-code --heads origin "$branch" >/dev/null || status=$?

  # Alleen 2 betekent "die branch is er niet" — een vorige run die na de close afbrak, of iemand die
  # hem met de hand verwijderde. Elke andere code (128 bij een auth- of netwerkfout) zegt dat we het
  # niet weten, en dan is stil overslaan het slechtste antwoord: een ingetrokken `Contents: write`
  # zou zo elke run de opruiming overslaan terwijl de log meldt dat er opgeruimd is.
  case $status in
    0) git push origin --delete "$branch" ;;
    2) echo "Branch $branch bestond al niet meer." ;;
    *)
      echo "::error::kon niet vaststellen of $branch nog bestaat (git ls-remote gaf $status)."
      return 1
      ;;
  esac
}
