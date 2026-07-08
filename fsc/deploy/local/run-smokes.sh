#!/usr/bin/env bash
set -euo pipefail
d="$(dirname "$0")"
"$d/smoke-announce.sh"
"$d/publish-service.sh"
"$d/smoke-discover.sh"
echo "ALLE SMOKES GROEN."
