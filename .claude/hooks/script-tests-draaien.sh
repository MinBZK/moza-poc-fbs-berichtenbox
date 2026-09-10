#!/usr/bin/env bash
# PostToolUse: draai de bash-unittests van een gewijzigd script onder .github/scripts/.
#
# Die scripts beslissen wát de rest van de CI doet — welke jobs draaien, of een run een merge mag
# dragen, of het stelsel van demonstratiecode afhangt. Een fout daarin is stil: een overgeslagen
# job rapporteert 'skipped' en dat telt als succes voor branch protection. De suites zijn puur
# bash en klaar in seconden, dus in tegenstelling tot Maven kan de feedback hier meteen.
#
# Uitzetten voor één sessie: FBS_HOOK_TESTS=0
#
# Contract: hook-input is JSON op stdin. Exitcode 2 geeft stderr aan Claude door; dat gebruiken we
# alleen als de suite écht faalt.

set -uo pipefail

pad=$(jq -r '.tool_input.file_path // .tool_input.path // empty')

if [[ -z "$pad" ]]; then
    exit 0
fi

# Pad kan absoluut zijn; we vergelijken op het repo-relatieve deel.
relatief="${pad#"${CLAUDE_PROJECT_DIR:-}"/}"

if [[ "$relatief" != .github/scripts/* ]]; then
    exit 0
fi

bestandsnaam=$(basename "$relatief")

# Een bewerkte suite draait zichzelf; een bewerkt script draait de suite ernaast. De .py-scripts
# hebben een bash-suite met dezelfde stam, vandaar het strippen van beide extensies.
if [[ "$bestandsnaam" == test-* ]]; then
    suite=".github/scripts/$bestandsnaam"
else
    stam="${bestandsnaam%.sh}"
    stam="${stam%.py}"
    suite=".github/scripts/test-$stam.sh"
fi

if [[ ! -f "$suite" ]]; then
    echo "Let op: $relatief heeft geen $suite ernaast."
    echo "ci-scripts.yml draait elke test-*.sh onder .github/scripts/ en bewaakt per suite een"
    echo "minimum aantal asserties; een script zonder suite is dus onbewaakt."
    exit 0
fi

if [[ "${FBS_HOOK_TESTS:-1}" == "0" ]]; then
    echo "Geraakt script: $relatief — draai zelf: bash $suite"
    exit 0
fi

uitvoer=$(bash "$suite" 2>&1)
resultaat=$?

if [[ $resultaat -ne 0 ]]; then
    echo "$suite FAALT na deze wijziging:" >&2
    echo "$uitvoer" | tail -40 >&2
    exit 2
fi

gemeten=$(sed -n 's/^ASSERTIES=//p' <<<"$uitvoer" | tail -1)

# ci-scripts.yml faalt op een suite die minder asserties levert dan de tabel daar verwacht, én op
# een suite die die regel helemaal niet print. Beide zijn hier al zichtbaar, in plaats van pas in
# CI. Voeg je asserties toe, verhoog dan de verwachting in ci-scripts.yml mee.
if [[ -z "$gemeten" ]]; then
    echo "$suite slaagt, maar print geen ASSERTIES=-regel; ci-scripts.yml faalt daarop." >&2
    exit 2
fi

echo "$suite groen ($gemeten asserties)."
echo "Wijzigde het aantal? Werk VERWACHTE_ASSERTIES in .github/workflows/ci-scripts.yml bij."
exit 0
