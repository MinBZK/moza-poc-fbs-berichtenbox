#!/usr/bin/env bash
# PreToolUse-guard: blokkeer een push die op main landt.
#
# "Nooit direct pushen naar main" staat in de werkwijze, maar wordt lokaal door niets afgedwongen:
# de branch-protection op de server weigert de push pas ná de netwerkronde, en een force-push die
# er wél doorheen komt is niet terug te draaien. Vandaar hier, vóór de aanroep.
#
# Geblokkeerd wordt elke push waarvan de BESTEMMING main is — ook via een refspec
# (`HEAD:main`, `feature:main`), ook `--delete main`, en ook `--all`/`--mirror`, die alle branches
# meesturen. Een push van main NAAR iets anders (`main:proef`) mag wel.
#
# Bewust uit te zetten voor één sessie: FBS_HOOK_MAIN_PUSH=0
#
# Contract: hook-input is JSON op stdin, de boodschap gaat naar stderr en exitcode 2 blokkeert de
# tool-call. Een andere exitcode blokkeert NIET.

set -uo pipefail

if [[ "${FBS_HOOK_MAIN_PUSH:-1}" == "0" ]]; then
    exit 0
fi

commando=$(jq -r '.tool_input.command // empty')

if [[ -z "$commando" ]]; then
    exit 0
fi

# Snelle uitweg: raakt het commando `push` niet, dan is er niets te bekijken.
if [[ "$commando" != *push* ]]; then
    exit 0
fi

huidige_branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")

# Een refspec mag `+`-geforceerd zijn en `bron:doel` dragen; alleen het doel telt.
doel_is_main() {
    local refspec="${1#+}"
    local doel="${refspec##*:}"
    doel="${doel#refs/heads/}"

    if [[ "$doel" == "HEAD" ]]; then
        doel="$huidige_branch"
    fi

    [[ "$doel" == "main" ]]
}

reden=""

# Samengestelde commando's opsplitsen: een `git push` achter && of ; telt evengoed.
while IFS= read -r segment; do
    read -ra woorden <<<"$segment"

    if [[ ${#woorden[@]} -lt 2 || "${woorden[0]}" != "git" ]]; then
        continue
    fi

    # `git -C <pad> push` en `git --no-pager push` schuiven het subcommando op.
    is_push=false

    for woord in "${woorden[@]:1:5}"; do
        if [[ "$woord" == "push" ]]; then
            is_push=true
            break
        fi
    done

    if [[ "$is_push" != true ]]; then
        continue
    fi

    refspecs=()
    na_push=false
    remote_gezien=false

    for woord in "${woorden[@]}"; do
        if [[ "$na_push" != true ]]; then
            if [[ "$woord" == "push" ]]; then
                na_push=true
            fi

            continue
        fi

        case "$woord" in
            --all | --mirror)
                reden="$woord stuurt alle branches mee, dus ook main"
                break
                ;;
            -*)
                continue
                ;;
            *)
                if [[ "$remote_gezien" != true ]]; then
                    remote_gezien=true
                else
                    refspecs+=("$woord")
                fi
                ;;
        esac
    done

    if [[ -n "$reden" ]]; then
        break
    fi

    if [[ ${#refspecs[@]} -eq 0 ]]; then
        # Zonder refspec pusht git de huidige branch naar zijn gelijknamige tegenhanger.
        if [[ "$huidige_branch" == "main" ]]; then
            reden="er staat geen refspec, dus git pusht de huidige branch main"
        fi

        continue
    fi

    for refspec in "${refspecs[@]}"; do
        if doel_is_main "$refspec"; then
            reden="de refspec '$refspec' landt op main"
            break 2
        fi
    done
done < <(printf '%s\n' "$commando" | sed 's/&&/\n/g; s/||/\n/g; s/;/\n/g; s/|/\n/g')

if [[ -z "$reden" ]]; then
    exit 0
fi

cat >&2 <<EOF
GEBLOKKEERD: dit commando pusht naar main — $reden.

Alle wijzigingen gaan via een feature branch en een Pull Request. Zet het werk op een branch met
prefix feature/, fix/ of chore/, push die, en open een draft-PR:

  git switch -c <prefix>/<beschrijving>
  git push -u origin <prefix>/<beschrijving>
  gh pr create --draft --base main

Moet dit echt rechtstreeks, dan is dat een bewuste beslissing van de opdrachtgever: draai de push
zelf, of zet de guard voor deze sessie uit met FBS_HOOK_MAIN_PUSH=0.
EOF
exit 2
