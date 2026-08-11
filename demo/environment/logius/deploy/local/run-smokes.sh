#!/usr/bin/env bash
set -euo pipefail
d="$(dirname "$0")"
"$d/smoke-announce.sh"
"$d/smoke-services.sh"
echo "ALLE SMOKES GROEN."
