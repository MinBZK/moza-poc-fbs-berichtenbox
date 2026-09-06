#!/usr/bin/env bash
# PostToolUse: vertel na een Kotlin-edit wat er moet draaien, en draai het waar dat snel kan.
#
# De vorige versie van deze hook noemde één module met een hardgecodeerd pad. Die module is
# hernoemd en de hook is daarna stilletjes nooit meer gevuurd. Daarom leidt deze versie de module
# af uit het bewerkte pad: hernoemen we een module, dan valt dat hier op in plaats van dat de hook
# verstomt.
#
# Snelle, pure-JVM-modules draaien we meteen. De rest boot Quarkus of start Testcontainers en kost
# minuten; daarvoor printen we het commando in plaats van de sessie te laten wachten.
#
# `clean` hoort erbij: we wisselen vaak van branch op een gedeelde bind mount, en een achtergebleven
# target/ laat Surefire stale .class-bestanden draaien — dat geeft NoSuchMethodError-achtige fouten
# in ongewijzigde code.
#
# De coverage-gate hangt aan de fase test maar hoort bij een PR, niet bij een edit halverwege het
# werk; daarom staat JaCoCo hier uit.
#
# Uitzetten voor één sessie: FBS_HOOK_TESTS=0
#
# Contract: hook-input is JSON op stdin. Exitcode 2 geeft stderr aan Claude door; dat gebruiken we
# alleen als de tests écht falen.

set -uo pipefail

pad=$(jq -r '.tool_input.file_path // .tool_input.path // empty')

if [[ -z "$pad" || "$pad" != *.kt ]]; then
    exit 0
fi

# Alleen echte broncode; gegenereerde code heeft een eigen guard.
if [[ "$pad" != *"/src/"* || "$pad" == *target/* ]]; then
    exit 0
fi

module=""

for kandidaat in \
    libraries/fbs-common \
    libraries/fbs-magazijnregister \
    libraries/fbs-berichtensessiecache \
    services/berichtenuitvraag \
    services/berichtenmagazijn \
    demo/demo-console \
    demo/demo-personas \
    demo/magazijn-simulator; do

    if [[ "$pad" == *"$kandidaat/"* ]]; then
        module="$kandidaat"
        break
    fi
done

if [[ -z "$module" ]]; then
    echo "Let op: $pad hoort bij geen bekende module. Staat er een nieuwe module in de reactor?" >&2
    echo "Vul dan de lijst in .claude/hooks/geraakte-module-test.sh aan." >&2
    exit 0
fi

# Deze twee booten geen Quarkus en hebben geen container nodig: enkele seconden, dus we draaien ze.
snel=false

if [[ "$module" == "libraries/fbs-common" || "$module" == "libraries/fbs-magazijnregister" ]]; then
    snel=true
fi

if [[ "$snel" != true || "${FBS_HOOK_TESTS:-1}" == "0" ]]; then
    echo "Geraakte module: $module — draai zelf:"
    echo "  ./mvnw clean test -pl $module -am"

    case "$module" in
        libraries/fbs-berichtensessiecache | services/* | demo/magazijn-simulator)
            echo "  (start Testcontainers; Docker of Podman moet draaien)"
            ;;
    esac

    echo "  Draait de demo-stack? Voeg -Dquarkus.http.test-port=0 toe, anders botst elke"
    echo "  @QuarkusTest op de bezette poort 8081."
    exit 0
fi

uitvoer=$(./mvnw -q clean test -pl "$module" -am -Djacoco.skip=true 2>&1)
resultaat=$?

if [[ $resultaat -ne 0 ]]; then
    echo "Tests van $module FALEN na deze wijziging:" >&2
    echo "$uitvoer" | tail -40 >&2
    exit 2
fi

exit 0
