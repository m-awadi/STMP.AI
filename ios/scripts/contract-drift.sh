#!/bin/bash
# contract-drift.sh: repeats the _expect check against the Android tip (redesign/instrument). Warn only (D134).
HERE=$(cd "$(dirname "$0")" && pwd)
"$HERE/source-check.sh" --tip || true
exit 0
