#!/usr/bin/env bash
# Start of breekt deze harness af in de modus die op deze machine werkt; zie
# demo/environment/lib/peer-stack.sh voor de commando's en de modi.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$HERE/../../../lib/peer-stack.sh" "$HERE" "$@"
