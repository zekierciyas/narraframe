#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# run-debug.sh  –  Run with JDWP debug agent on port 5005
#
# Usage:
#   ./run-debug.sh            # JVM starts immediately, attach debugger later
#   ./run-debug.sh --suspend  # JVM waits until debugger connects
#
# Connect IntelliJ:
#   Run > "Desktop Attach Debugger"  (or any JDWP client to localhost:5005)
#
# Tail logs in a separate terminal:
#   tail -f ~/.narraframe/logs/app-$(date +%F).log
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail
cd "$(dirname "$0")"

SUSPEND_FLAG=""
if [[ "${1:-}" == "--suspend" ]]; then
    SUSPEND_FLAG="-PsuspendJvm"
    echo "JVM will WAIT for debugger on port 5005 before starting."
else
    echo "JVM starting with debug agent on port 5005 (suspend=n)."
fi

echo "Attach IntelliJ via Run > 'Desktop Attach Debugger' or any JDWP client."
echo "────────────────────────────────────────────────────────────────────────"

./gradlew :composeApp:run -PdebugJvm $SUSPEND_FLAG
