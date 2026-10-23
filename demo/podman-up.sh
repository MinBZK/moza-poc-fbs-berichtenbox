#!/usr/bin/env bash
# De vorige naam van demo/up.sh. Dat script start de demo-stack op elke engine, ook op Docker; deze
# naam blijft werken voor wie hem kent.
echo "demo/podman-up.sh heet nu demo/up.sh." >&2
exec "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/up.sh" "$@"
