#!/usr/bin/env bash
# Whitelist-grep CI enforcement over the async-signal-safe child path
# (plan §4.4 "Enforcement (b)"): the region between the RD_CHILD_PATH
# markers in termux.c must not contain any of the FORBIDDEN identifiers
# (word-boundary match, so _exit and snprintf are not "exit"/"printf").
#
# This greps comments too — deliberately: a comment in the safe zone is
# part of what a reviewer reads, and keeping the zone's vocabulary
# honest costs nothing.
set -euo pipefail

SRC="${1:-../src/main/jni/termux.c}"

FORBIDDEN='opendir|readdir|closedir|malloc|calloc|realloc|free|strdup|putenv|clearenv|asprintf|perror|printf|fflush|execvp|execlp|exit|syscall|close_range'

if [ ! -f "$SRC" ]; then
    echo "check_child_path.sh: source not found: $SRC" >&2
    exit 2
fi

REGION=$(awk '/RD_CHILD_PATH_BEGIN/{f=1;next} /RD_CHILD_PATH_END/{f=0} f' "$SRC")

if [ -z "$REGION" ]; then
    echo "check_child_path.sh: RD_CHILD_PATH region not found in $SRC" >&2
    exit 2
fi

LINES=$(printf '%s\n' "$REGION" | wc -l)
if [ "$LINES" -gt 120 ]; then
    echo "check_child_path.sh: child path is $LINES lines — the safe zone must stay short and reviewable" >&2
    exit 1
fi

VIOLATIONS=$(printf '%s\n' "$REGION" | grep -nE "(^|[^[:alnum:]_])(${FORBIDDEN})([^[:alnum:]_]|$)" || true)

if [ -n "$VIOLATIONS" ]; then
    echo "check_child_path.sh: FORBIDDEN identifier(s) in the async-signal-safe child path:" >&2
    echo "$VIOLATIONS" >&2
    exit 1
fi

echo "OK: child path clean ($LINES lines, whitelist grep passed)"
