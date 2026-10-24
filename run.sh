#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# run.sh  –  Normal run (no debugger)
#
# Usage:  ./run.sh
# Logs :  ~/.narraframe/logs/app-YYYY-MM-DD.log
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail
cd "$(dirname "$0")"

LOG_FILE="$HOME/.narraframe/logs/app-$(date +%F).log"

echo "Starting Narraframe…"
echo "Console log below; full log also in: $LOG_FILE"
echo "────────────────────────────────────────────────"

./gradlew :composeApp:run
