#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Render the Phonautograph mockups to android/docs/design/<page>-<theme>.png (412 x 915 dp at 2x).
# usage: render.sh [page ...]        (default: every page in PAGES)
# Needs google-chrome and ImageMagick (magick). Fonts are the staged TTFs in design-src/export/res/font/ (offline).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$(cd "$here/../../docs" && pwd)/design"
mkdir -p "$out"
# [output=]page[:height[:extra-query]]
PAGES=(
  01-home 02-home-full=01-home:auto:"&full=1" 03-home-first-run 04-watch-video 05-watch-audio-offline::"&palette=cvd"
  06-watch-portrait 07-watch-question 08-watch-set-end 09-mode-picker 10-lesson-compare 11-lesson-boundary:1560
  12-learn 13-phenomenon 14-practice 15-library 16-clip-page 17-requests:auto:"&full=1" 18-request-detail:auto:"&full=1" 19-share-connected
  20-share-queued 21-connect-none 22-settings-display 22-settings-full=22-settings-display:auto:"&full=1" 23-welcome 24-saved-empty 27-watch-large-text 25-illustrations:auto:"&full=1" 26-brand:auto:"&full=1"
  # change request CR-01 (v1-complete): the two removals, the deletion state, shared progress
  28-clip-computer:auto:"&full=1" 29-clip-delete-waiting=28-clip-computer:auto:"&full=1&state=waiting" 30-delete-dialog
  31-removal-dialogs:auto:"&full=1" 32-requests-deletions:auto:"&full=1" 33-practice-shared:auto:"&full=1" 34-deletion-states:auto:"&full=1"
)
want=("$@")
render() { # output page height extra theme
  local name=$1 page=$2 h=${3:-915} extra=${4:-} theme=$5
  local png="$out/$name-$theme.png"
  if [ "$h" = "auto" ]; then # measure the page first (full-scroll renders)
    h=$(google-chrome --headless=new --disable-gpu --window-size=412,915 --virtual-time-budget=6000 --allow-file-access-from-files \
      --dump-dom "file://$here/$page.html?theme=$theme$extra" 2>/dev/null | grep -o 'data-h="[0-9]*"' | head -1 | grep -o '[0-9]*')
    h=${h:-915}
  fi
  google-chrome --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=2 \
    --window-size=412,"$h" --virtual-time-budget=6000 --allow-file-access-from-files \
    --screenshot="$png" "file://$here/$page.html?theme=$theme$extra" >/dev/null 2>&1
  magick "$png" -strip -colors 256 "$png"; if [ "$(stat -c %s "$png")" -gt 409600 ]; then magick "$png" -strip -colors 96 "$png"; fi
  echo "$png $(stat -c %s "$png")"
}
for entry in "${PAGES[@]}"; do
  IFS=: read -r head h extra <<<"$entry"
  name=${head%%=*}; page=${head#*=}
  if [ ${#want[@]} -gt 0 ] && [[ ! " ${want[*]} " =~ " $name " ]]; then continue; fi
  [ -f "$here/$page.html" ] || { echo "missing $page.html" >&2; continue; }
  for theme in light dark; do render "$name" "$page" "${h:-915}" "${extra:-}" "$theme"; done
done
