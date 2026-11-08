#!/bin/sh
# Chromite - Database Workbench
# Copyright (C) 2026 Chromite contributors
# Licensed under the Apache License, Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
#
# The server half of tools/release/deploy-chromite-site.ps1 (docs/chromite/distribution.md "Deploy").
# The PC copies a staging folder to <root>/incoming/<stamp>/ and runs this script from there:
#   sh <root>/incoming/<stamp>/deploy-remote.sh <root> <stamp> <version> <data_generation> <notes_url>
# Staging folder: SUMS (sha256 of every other file), files/ (the release files), site/ (the download
# site), releases.sh, deploy-remote.sh.
#
# Layout under <root> (default /srv/chromite; the proxy serves site/ and downloads/ read-only):
#   site/                 https://chromite.seizia.com/
#   downloads/            https://chromite.seizia.com/downloads/  (releases.json, releases.js, the release files)
#   releases-meta.tsv     data_generation and notes_url per version (read by releases.sh, not served)
#   bin/releases.sh       the feed generator
#   incoming/<stamp>/     what each deploy brought (the two newest are kept, see step 5)
#   old/<stamp>/          what each deploy moved aside (kept)
#
# Design:
#   * Nothing served is deleted or overwritten in place: a replaced file is moved to old/<stamp>/ first.
#     The only deletion is step 5: staging folders in incoming/ beyond the two newest. Each holds a full
#     copy of a release (about 0.5 GB) that is already served from downloads/ or kept in old/<stamp>/.
#   * Every check runs before the first change. A release file that is already there with other
#     bytes, an older version than the newest one there, or a different meta line for the version
#     stops the script (a version number is never reused).
#   * site/ and downloads/ are bind-mounted into the proxy container, so their entries are moved,
#     never the folders themselves (a renamed folder would stay mounted as it was).
#   * Release files come in by rename on the same file system: a half-copied file is never served.
#   * Files of the two newest versions stay in downloads/ (the previous setup is the updater's
#     rollback); older ones move to old/<stamp>/downloads/.
#   * Everything served gets mode 644/755: the proxy reads as a user without file capabilities.
#
# Change history:
# - 2026-11-07 Created.
# - 2026-11-08 Step 5: keep only the two newest incoming/<stamp>/ folders (user decision: the copies piled up
#   to 2 GB). old/<stamp>/ is untouched, so rolling back with -Back still works.
set -eu
[ $# -eq 5 ] || { echo 'usage: deploy-remote.sh <root> <stamp> <version> <data_generation> <notes_url>' >&2; exit 2; }
root=$1 stamp=$2 version=$3 generation=$4 notes=$5
stage=$root/incoming/$stamp
old=$root/old/$stamp
meta=$root/releases-meta.tsv
tab=$(printf '\t')
umask 022

say() { printf '%s\n' "$*"; }
stop() { printf 'STOP: %s\n' "$*" >&2; exit 1; }
sum() { sha256sum "$1" | cut -c1-64; }
# major.minor.dev -> a number that sorts like the version (each part below 100000)
vkey() { printf '%s\n' "$1" | awk -F. '{ printf "%d%05d%05d\n", $1, $2, $3 }'; }
# versions present in downloads/, one per line
versions_there() {
  find "$root/downloads" -maxdepth 1 -type f -name 'Chromite-*' | sed -n -E \
    -e 's/^.*\/Chromite-([0-9]+\.[0-9]+\.[0-9]+)-(x64-setup\.exe|arm64\.zip|arm64\.dmg)$/\1/p' | sort -u
}

# ---- 1. checks (nothing changes)
case $version in *[!0-9.]*|'') stop "bad version '$version'" ;; esac
printf '%s\n' "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' || stop "bad version '$version'"
case $generation in ''|*[!0-9]*|0*) stop "data_generation '$generation' is not a positive integer" ;; esac
case $notes in https://*[\"\\\ ]*) stop 'notes_url has a quote, backslash or space' ;; https://?*) ;; *) stop 'notes_url is not https' ;; esac
for d in site downloads; do
  [ -d "$root/$d" ] && [ -w "$root/$d" ] || stop "$root/$d is missing or not writable"
done
[ -d "$stage" ] || stop "no staging folder $stage"
cd "$stage"
sha256sum --quiet -c SUMS || stop 'the copy differs from SUMS'
say "staging $stage: every file matches SUMS"
files="Chromite-$version-x64-setup.exe Chromite-$version-arm64.zip Chromite-$version-arm64.dmg"
for f in $files; do
  [ -f "files/$f" ] || stop "files/$f is missing"
  if [ -e "$root/downloads/$f" ]; then
    [ "$(sum "$root/downloads/$f")" = "$(sum "files/$f")" ] || stop "downloads/$f is there with other bytes (a version is never reused)"
  fi
done
[ -f site/index.html ] || stop 'site/index.html is missing'
newest=$(versions_there | while read -r v; do printf '%s %s\n' "$(vkey "$v")" "$v"; done | sort -n | tail -n 1 | cut -d' ' -f2)
if [ -n "$newest" ] && [ "$(vkey "$newest")" -gt "$(vkey "$version")" ]; then
  stop "downloads/ has $newest, newer than $version"
fi
line=$(printf '%s\t%s\t%s' "$version" "$generation" "$notes")
had=
if [ -f "$meta" ]; then
  had=$(grep -E "^$(printf '%s' "$version" | sed 's/\./\\./g')$tab" "$meta" || true)
  [ -z "$had" ] || [ "$had" = "$line" ] || stop "releases-meta.tsv has another line for $version: $had"
fi
say 'checks passed'

# ---- 2. feed generator and meta line
mkdir -p "$old" "$root/bin"
if [ -f "$root/bin/releases.sh" ] && ! cmp -s "$root/bin/releases.sh" releases.sh; then
  mkdir -p "$old/bin"; mv "$root/bin/releases.sh" "$old/bin/releases.sh"
fi
[ -f "$root/bin/releases.sh" ] || { cp releases.sh "$root/bin/.releases.sh.new"; chmod 755 "$root/bin/.releases.sh.new"; mv "$root/bin/.releases.sh.new" "$root/bin/releases.sh"; }
if [ -z "$had" ]; then
  if [ -f "$meta" ]; then cp -p "$meta" "$old/releases-meta.tsv"; cp "$meta" "$meta.new"; else printf '# version\tdata_generation\tnotes_url\n' > "$meta.new"; fi
  printf '%s\n' "$line" >> "$meta.new"
  chmod 644 "$meta.new"; mv "$meta.new" "$meta"
  say "releases-meta.tsv: added $version $generation $notes"
else
  say "releases-meta.tsv: the line for $version is there already"
fi

# ---- 3. release files in, versions older than the two newest out
for f in $files; do
  if [ -e "$root/downloads/$f" ]; then say "downloads/$f: there already (same bytes)"; continue; fi
  chmod 644 "files/$f"
  mv "files/$f" "$root/downloads/$f"
  say "downloads/$f: in ($(wc -c < "$root/downloads/$f" | tr -d ' ') bytes)"
done
keep=$(versions_there | while read -r v; do printf '%s %s\n' "$(vkey "$v")" "$v"; done | sort -n | tail -n 2 | cut -d' ' -f2 | tr '\n' ' ')
for v in $(versions_there); do
  case " $keep" in *" $v "*) continue ;; esac
  mkdir -p "$old/downloads"
  for f in "$root/downloads/Chromite-$v-"*; do
    [ -f "$f" ] || continue
    mv "$f" "$old/downloads/"
    say "downloads/${f##*/}: moved to $old/downloads/ (older than the two newest versions: $keep)"
  done
done
for f in releases.json releases.js; do
  if [ -f "$root/downloads/$f" ]; then cp -p "$root/downloads/$f" "$old/$f"; fi
done
sh "$root/bin/releases.sh" "$root/downloads" "$meta"

# ---- 4. the site: every entry moved aside, then the new entries moved in
mkdir -p "$old/site"
for e in "$root/site"/* "$root/site"/.[!.]*; do
  [ -e "$e" ] || continue
  mv "$e" "$old/site/"
done
chmod -R a+rX,go-w site
for e in site/* site/.[!.]*; do
  [ -e "$e" ] || continue
  mv "$e" "$root/site/"
done
say "site: $(find "$root/site" -type f | wc -l | tr -d ' ') files in place; the previous site is in $old/site/"

say "releases.json: $(head -c 200 "$root/downloads/releases.json")"
# ---- 5. incoming/: keep the two newest staging folders (this one included), delete the older ones
for d in $(find "$root/incoming" -mindepth 1 -maxdepth 1 -type d -name '[0-9]*T[0-9]*Z' | sort | head -n -2); do
  [ "$d" = "$stage" ] && continue
  rm -rf -- "$d"
  say "incoming/${d##*/}: deleted (older than the two newest staging folders)"
done

say "DONE: $version deployed (moved aside: $old)"
