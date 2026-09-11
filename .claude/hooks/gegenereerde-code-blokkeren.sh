#!/usr/bin/env bash
# PreToolUse-guard: gegenereerde OpenAPI-code mag niet handmatig worden aangepast.
#
# De interfaces onder target/generated-sources/openapi/ komen uit de spec. Een handmatige wijziging
# daar overleeft de eerstvolgende `mvn clean` niet en laat spec en code stil uiteenlopen — precies
# het probleem dat OpenAPI-first moet voorkomen.
#
# Contract: hook-input is JSON op stdin, de boodschap gaat naar stderr en exitcode 2 blokkeert de
# tool-call. Een andere exitcode blokkeert NIET.

set -uo pipefail

pad=$(jq -r '.tool_input.file_path // .tool_input.path // empty')

if [[ -z "$pad" ]]; then
    exit 0
fi

if [[ "$pad" == *target/generated-sources* ]]; then
    cat >&2 <<EOF
GEBLOKKEERD: $pad ligt onder target/generated-sources en wordt door de OpenAPI-generator
overschreven. Wijzig in plaats daarvan de spec van de service
(services/<service>/src/main/resources/openapi/<service>-api.yaml) en draai
\`./mvnw clean compile -pl services/<service> -am\`.
EOF
    exit 2
fi

exit 0
