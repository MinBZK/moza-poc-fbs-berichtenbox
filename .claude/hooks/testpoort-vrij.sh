#!/usr/bin/env bash
# SessionStart: waarschuw als de poorten van de demo-stack bezet zijn.
#
# Draait de demo-stack, dan bezet WireMock poort 8081 en faalt élke @QuarkusTest op
# "Failed to start quarkus" / "Port already bound" — een melding die de oorzaak niet noemt en die
# je makkelijk voor een codefout aanziet. De stack hoeft niet plat: -Dquarkus.http.test-port=0
# laat Quarkus een vrije poort kiezen.
#
# De andere poorten staan erbij omdat ze dezelfde verwarring geven zodra een test of dev-mode ze
# wil hebben.
#
# Contract: hook-input is JSON op stdin. Stdout van een SessionStart-hook komt in de context; geen
# bezette poort betekent geen uitvoer.

set -uo pipefail

luistert() {
    local poort="$1"

    if command -v ss >/dev/null 2>&1; then
        ss -ltn "sport = :$poort" 2>/dev/null | grep -q LISTEN
        return
    fi

    if command -v netstat >/dev/null 2>&1; then
        netstat -ltn 2>/dev/null | grep -qE "[:.]${poort}[[:space:]]+.*LISTEN"
        return
    fi

    # Laatste redmiddel: proberen te verbinden. Lukt dat, dan luistert er iets.
    (exec 3<>"/dev/tcp/127.0.0.1/$poort") 2>/dev/null
}

bezet=()

# Poort, en waarvoor hij normaal in gebruik is.
for regel in \
    "8081:WireMock magazijn-a — botst met de standaard testpoort van Quarkus" \
    "8082:WireMock magazijn-b" \
    "8086:berichtenuitvraag (dev-mode)" \
    "8090:berichtenmagazijn (dev-mode)"; do

    poort="${regel%%:*}"

    if luistert "$poort"; then
        bezet+=("$regel")
    fi
done

if [[ ${#bezet[@]} -eq 0 ]]; then
    exit 0
fi

echo "Bezette poorten van de demo-stack:"

for regel in "${bezet[@]}"; do
    echo "  ${regel%%:*} — ${regel#*:}"
done

if [[ " ${bezet[*]} " == *" 8081:"* ]]; then
    echo
    echo "Poort 8081 is bezet: elke @QuarkusTest faalt zo op 'Failed to start quarkus'."
    echo "Voeg -Dquarkus.http.test-port=0 toe aan de Maven-aanroep; de stack hoeft niet plat."
fi

exit 0
