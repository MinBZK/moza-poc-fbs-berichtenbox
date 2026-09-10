#!/usr/bin/env bash
# PreToolUse-guard: blokkeer een bestands- of mapnaam met witruimte erin.
#
# De conventie is kebab-case of snake_case voor documentatie en configuratie, PascalCase of
# camelCase voor Kotlin- en Java-sources. Reden is niet smaak: shellscripts, build-tools en
# CI-pipelines in deze repo lopen zonder quoting over paden — `find ... | xargs`,
# `for f in $(...)`, pad-lijsten in workflows. Eén spatie splitst zo'n pad in twee, en de fout
# komt naar boven in een CI-stap die iets heel anders lijkt te doen.
#
# Hernoemen kost later meer dan nu: het pad staat dan al in een workflow, een script of een spec.
#
# Contract: hook-input is JSON op stdin, de boodschap gaat naar stderr en exitcode 2 blokkeert de
# tool-call. Een andere exitcode blokkeert NIET.

set -uo pipefail

pad=$(jq -r '.tool_input.file_path // .tool_input.path // empty')

if [[ -z "$pad" ]]; then
    exit 0
fi

relatief="${pad#"${CLAUDE_PROJECT_DIR:-}"/}"

# Tabs en newlines vangen we mee: die zijn in een pad nog kwaadaardiger dan een spatie.
if [[ ! "$relatief" =~ [[:space:]] ]]; then
    exit 0
fi

# Alleen het nieuw toegevoegde deel is te repareren; een bestaand pad met een spatie is een
# ander gesprek dan deze edit.
if [[ -e "$pad" ]]; then
    echo "Let op: $relatief bestaat al en heeft witruimte in het pad. Hernoemen is een eigen"
    echo "wijziging — de edit zelf gaat door."
    exit 0
fi

voorstel=$(printf '%s' "$relatief" | tr -s '[:space:]' '-')

cat >&2 <<EOF
GEBLOKKEERD: $relatief heeft witruimte in het pad.

Shellscripts, build-tools en CI-pipelines in deze repo lopen zonder quoting over paden; één spatie
splitst zo'n pad in twee en de fout komt boven in een stap die iets heel anders lijkt te doen.

Gebruik kebab-case of snake_case (documentatie, markdown, configuratie), of PascalCase/camelCase
(Kotlin- en Java-sources). Bijvoorbeeld:

  $voorstel
EOF
exit 2
