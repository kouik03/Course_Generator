#!/usr/bin/env bash
# Course Generator IQ: pull, build, restart and run for Garmin fenix 9 Pro 51mm.
# Launch from any directory: bash ~/Desktop/Course_Generator/connect-iq/CourseGeneratorIQ/run-cqiq.sh
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
PROJECT="$SCRIPT_DIR"
BRANCH="experiment/terrain-debiased-curve-v1"
DEVICE="fenix9pro51mm"
OUTPUT="$PROJECT/bin/CourseGeneratorIQ.prg"
KEY="$HOME/Library/Mobile Documents/com~apple~CloudDocs/garmin/developer_key"
SDK_HOME="$HOME/Library/Application Support/Garmin/ConnectIQ/Sdks"

fail() {
    printf '\nERREUR CGIq : %s\n' "$*" >&2
    exit 1
}

printf '\n=== Course Generator IQ | fenix 9 Pro 51 mm ===\n'

[[ -d "$REPO_ROOT/.git" ]] || fail "Le depot Git est introuvable : $REPO_ROOT"
CURRENT_BRANCH="$(git -C "$REPO_ROOT" branch --show-current)"
[[ "$CURRENT_BRANCH" == "$BRANCH" ]] ||
    fail "Branche actuelle : '$CURRENT_BRANCH'. La commande ne change pas automatiquement de branche. Attendu : '$BRANCH'."

printf '\n[1/4] Synchronisation GitHub (sans ecraser tes modifications locales)...\n'
git -C "$REPO_ROOT" pull --ff-only origin "$BRANCH"

if [[ -n "${CGIQ_SDK:-}" ]]; then
    SDK="${CGIQ_SDK%/}"
else
    SDK=""
    # Select the newest installed macOS SDK directory, so SDK upgrades work.
    for candidate in "$SDK_HOME"/connectiq-sdk-mac-*/; do
        if [[ -x "${candidate}bin/monkeyc" ]] &&
           [[ -x "${candidate}bin/monkeydo" ]] &&
           [[ -d "${candidate}bin/ConnectIQ.app" ]]; then
            SDK="${candidate%/}"
        fi
    done
fi

[[ -n "$SDK" ]] || fail "Aucun SDK Garmin macOS valide dans : $SDK_HOME"
[[ -x "$SDK/bin/monkeyc" ]] || fail "monkeyc introuvable dans : $SDK"
[[ -x "$SDK/bin/monkeydo" ]] || fail "monkeydo introuvable dans : $SDK"
[[ -d "$SDK/bin/ConnectIQ.app" ]] || fail "ConnectIQ.app introuvable dans : $SDK"
[[ -f "$KEY" ]] || fail "Cle Garmin introuvable : $KEY"
[[ -f "$PROJECT/monkey.jungle" ]] || fail "Fichier monkey.jungle introuvable"

printf '\n[2/4] Compilation (%s)...\n' "$DEVICE"
printf 'SDK : %s\n' "$SDK"
mkdir -p "$PROJECT/bin"
"$SDK/bin/monkeyc" \
    -f "$PROJECT/monkey.jungle" \
    -o "$OUTPUT" \
    -y "$KEY" \
    -d "$DEVICE" \
    -w
[[ -s "$OUTPUT" ]] || fail "La compilation n'a pas produit le fichier PRG."
printf 'Compilation OK : %s\n' "$OUTPUT"

printf '\n[3/4] Fermeture de tous les simulateurs Garmin existants...\n'
# Do not close the old simulator unless the new build succeeded.
pkill -x ConnectIQ 2>/dev/null || true
pkill -f '/ConnectIQ.app/Contents/MacOS/ConnectIQ' 2>/dev/null || true
sleep 1

printf '\n[4/4] Lancement du simulateur et execution de la nouvelle version...\n'
open -na "$SDK/bin/ConnectIQ.app"
sleep 3
"$SDK/bin/monkeydo" "$OUTPUT" "$DEVICE"

printf '\nCourse Generator IQ demarre pour %s.\n' "$DEVICE"
