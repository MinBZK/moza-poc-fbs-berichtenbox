#!/usr/bin/env bash
# PreToolUse-guard: een toegepaste Flyway-migratie is immutable.
#
# Flyway slaat per migratie een checksum op in flyway_schema_history. Wijzig je een V*.sql die al
# gedraaid heeft, dan start de service pas de volgende keer niet meer op — ver weg van de edit die
# het veroorzaakte. Een nieuwe V(N+1) toevoegen mag wel, dus alleen een BESTAAND bestand blokkeren.
#
# Rollback-scripts onder db/rollback/ vallen hier bewust buiten: die zijn lokale hulpmiddelen die
# Flyway niet kent en dus niet controleert.
#
# Contract: hook-input is JSON op stdin, de boodschap gaat naar stderr en exitcode 2 blokkeert de
# tool-call. Een andere exitcode blokkeert NIET.

set -uo pipefail

pad=$(jq -r '.tool_input.file_path // .tool_input.path // empty')

if [[ -z "$pad" ]]; then
    exit 0
fi

if [[ "$pad" != *db/migration/V*.sql ]]; then
    exit 0
fi

if [[ ! -f "$pad" ]]; then
    exit 0
fi

bestandsnaam=$(basename "$pad")
map=$(dirname "$pad")
hoogste=$(find "$map" -maxdepth 1 -name 'V*.sql' -printf '%f\n' 2>/dev/null |
    sed -n 's/^V\([0-9]\{1,\}\)__.*/\1/p' | sort -n | tail -1)
volgende=$(( ${hoogste:-0} + 1 ))

cat >&2 <<EOF
GEBLOKKEERD: $bestandsnaam is een bestaande Flyway-migratie en die is immutable na toepassing.
Flyway bewaart een checksum per migratie; een wijziging hier laat de service bij de volgende boot
falen op een checksum-mismatch, ver weg van deze edit.

Voeg in plaats daarvan een nieuwe migratie toe: $map/V${volgende}__<beschrijving>.sql
Hoort er een rollback bij, zet die dan in de map db/rollback/ ernaast.
EOF
exit 2
