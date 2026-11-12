#!/usr/bin/env bash
# Install the CGIq shell command in ~/.zshrc. Run once after pulling the repo.
set -euo pipefail

RC="$HOME/.zshrc"
MARKER="# CGIq command: Course Generator IQ"

touch "$RC"

if grep -Fqx "$MARKER" "$RC"; then
    printf 'La fonction CGIq est deja installee dans %s.\n' "$RC"
else
    cat >> "$RC" <<'ZSHRC'

# CGIq command: Course Generator IQ
CGIq() {
    bash "$HOME/Desktop/Course_Generator/connect-iq/CourseGeneratorIQ/run-cqiq.sh" "$@"
}
ZSHRC
    printf 'Fonction CGIq ajoutee a %s.\n' "$RC"
fi

printf 'Recharge zsh avec : source ~/.zshrc\n'
printf 'Puis lance : CGIq\n'
