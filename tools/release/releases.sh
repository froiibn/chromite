#!/bin/sh
# Chromite - Database Workbench
# Copyright (C) 2026 Chromite contributors
# Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
#
# Writes releases.json, the update feed and download list of https://chromite.seizia.com, from the
# files that ARE in the download folder (docs/chromite/distribution.md "Feed"):
#   sh releases.sh [downloads-folder] [meta-file]
#     downloads-folder  default /srv/chromite/downloads
#     meta-file         default <downloads-folder>/../releases-meta.tsv (outside the served folder)
# Runs on the server (dash, GNU coreutils) and locally (Git Bash).
#
# Design (mirrors Turbrix service/deploy/releases.sh, format 1):
#   * Only files named the way release.yml names them are listed; anything else is ignored:
#       Chromite-<v>-x64-setup.exe   windows  setup
#       Chromite-<v>-arm64.zip       macos    zip   (the macOS update payload)
#       Chromite-<v>-arm64.dmg       macos    dmg
#   * data_generation and notes_url come from the meta file, one line per version, TAB separated:
#       <version>  <data_generation>  <notes_url>      (# starts a comment)
#     Unlike Turbrix, a listed version WITHOUT a valid line stops the script and the old
#     releases.json stays: every entry of Chromite's feed carries both fields (the shared contract
#     with the in-app updater), and nothing here guesses them.
#   * Newest version first (numeric order, not by name). size and sha256 are computed here.
#   * Also writes releases.js for the download page: the same JSON as a script
#     (window.chromiteReleases = ...;) plus each file's upload time (window.chromiteReleaseDates,
#     unix seconds of its modification time; the feed has no date). The site's CSP (default-src
#     'none', script-src 'self', no connect-src) does not let the page fetch releases.json, but it
#     may load a same-origin script. The updater reads releases.json only.
#   * Atomic: both are written to temporary files in the same folder, then renamed into place.
#   * The top of the file is exactly {"format":1,"generated_at":<unix>,"files":[ (the server's
#     checks match it).
#   * Put only files from a signed CI release there (the deploy script verifies them); this script
#     cannot tell.
#
# Change history:
# - 2026-11-07 Created (Chromite's version of Turbrix's releases.sh).
set -eu
set -f
folder=${1:-/srv/chromite/downloads}
[ -d "$folder" ] || { printf 'releases.sh: no such folder: %s\n' "$folder" >&2; exit 1; }
folder=$(cd "$folder" && pwd)
meta=${2:-$(dirname "$folder")/releases-meta.tsv}
out=$folder/releases.json
tmp=$folder/.releases.json.$$
js=$folder/releases.js
jstmp=$folder/.releases.js.$$
dates=$folder/.releases.dates.$$
tab=$(printf '\t')
trap 'rm -f "$tmp" "$jstmp" "$dates"' EXIT

fail() { printf 'releases.sh: %s - releases.json not changed\n' "$1" >&2; exit 1; }

# "<generation> <notes_url>" of version $1 from the meta file, or nothing.
meta_of() {
  [ -f "$meta" ] || return 0
  while IFS="$tab" read -r version generation notes rest; do
    case $version in '#'*|'') continue ;; esac
    [ "$version" = "$1" ] || continue
    printf '%s %s\n' "$generation" "$notes"
    return 0
  done < "$meta"
}

# One line per known file: "<major> <minor> <dev> <name> <version> <arch> <kind> <platform>"
list=$(find "$folder" -maxdepth 1 -type f -name 'Chromite-*' | while IFS= read -r path; do
  name=${path##*/}
  printf '%s\n' "$name" | sed -n -E \
    -e 's/^Chromite-(([0-9]+)\.([0-9]+)\.([0-9]+))-(x64)-(setup)\.exe$/\2 \3 \4 & \1 \5 \6 windows/p' \
    -e 's/^Chromite-(([0-9]+)\.([0-9]+)\.([0-9]+))-(arm64)\.(zip)$/\2 \3 \4 & \1 \5 \6 macos/p' \
    -e 's/^Chromite-(([0-9]+)\.([0-9]+)\.([0-9]+))-(arm64)\.(dmg)$/\2 \3 \4 & \1 \5 \6 macos/p'
done | sort -k1,1nr -k2,2nr -k3,3nr -k4,4)

umask 022
{
  printf '{"format":1,"generated_at":%s,"files":[' "$(date -u +%s)"
  first=1
  if [ -n "$list" ]; then
    while read -r major minor dev name version arch kind platform; do
      m=$(meta_of "$version")
      [ -n "$m" ] || fail "no line for $version in $meta"
      generation=${m%% *}
      notes=${m#* }
      case $generation in ''|*[!0-9]*|0*) fail "data_generation of $version is not a positive integer: '$generation'" ;; esac
      case $notes in
        https://*[\"\\\ ]*) fail "notes_url of $version has a quote, backslash or space" ;;
        https://?*) ;;
        *) fail "notes_url of $version is not https: '$notes'" ;;
      esac
      path=$folder/$name
      size=$(wc -c < "$path" | tr -d ' ')
      sum=$(sha256sum "$path" | cut -c1-64)
      [ "$first" = 1 ] || printf ','
      first=0
      printf '\n{"file":"%s","version":"%s","arch":"%s","kind":"%s","platform":"%s","size":%s,"sha256":"%s","data_generation":%s,"notes_url":"%s"}' \
        "$name" "$version" "$arch" "$kind" "$platform" "$size" "$sum" "$generation" "$notes"
      # the page shows when a file was put here (its modification time); not in releases.json
      printf '"%s":%s\n' "$name" "$(date -u -r "$path" +%s)" >> "$dates"
    done <<EOF
$list
EOF
  fi
  printf ']}\n'
} > "$tmp"
: >> "$dates"
{
  printf 'window.chromiteReleases = '; cat "$tmp"; printf ';\n'
  printf 'window.chromiteReleaseDates = {%s};\n' "$(paste -sd, "$dates")"
} > "$jstmp"
rm -f "$dates"
chmod 644 "$tmp" "$jstmp"
mv -f "$tmp" "$out"
mv -f "$jstmp" "$js"
trap - EXIT
printf 'releases.json: %s file(s)\n' "$(grep -c '"file":' "$out" || true)"
