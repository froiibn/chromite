#!/bin/bash
# Signing, notarization and packaging of the Chromite macOS apps (Apple silicon) for the job
# macos-sign of .github/workflows/release.yml, one subcommand per step, so the job and a check on
# a Mac run the same commands. Design and decisions: docs/chromite/signing.md (adapted from the
# Turbrix signing guide and its tools/release-macos.sh, 2026-11-06). bash 3.2 (macOS's own) is enough.
#
#   keychain-create          (CI only) temporary keychain with the Developer ID Application identity
#                            of the .p12 and the notary key file; writes MAC_SIGN_IDENTITY (SHA-1),
#                            MAC_SIGN_KEYCHAIN and NOTARY_KEY_FILE to $GITHUB_ENV
#   sign <app>               (CI only) every Mach-O inside the app (loose files and jar entries,
#                            tools/release/macos_natives.py), then the app itself with the hardened
#                            runtime, a secure timestamp and tools/release/entitlements.mac.plist
#   check-signed <app>       the app and every Mach-O in it carry the team's Developer ID, a secure
#                            timestamp and the hardened runtime
#   notarize <app|dmg>       (CI only) xcrun notarytool submit --wait (an app goes as a zip of it),
#                            then xcrun stapler staple and validate (with retries)
#   package <app> <base> <dir> [<zip app name>]
#                            <dir>/<base>.zip (ditto, the app with its stapled ticket; the bundle folder
#                            is named <zip app name>, default the app's own name) and
#                            <dir>/<base>.dmg (dmgbuild with tools/release/dmg: volume "Chromite", the app
#                            at the top, an Applications link below it, 144 pt icons, the arrow background;
#                            dmgbuild is $DMGBUILD or on PATH, tools/release/dmg/requirements.txt)
#   sign-dmg <dmg>           (CI only) signs the disk image (secure timestamp)
#   verify <signed|unsigned> <app> <dmg> <zip>
#                            the app as signed, inside the disk image and inside the zip (whatever the
#                            name of its one bundle) verifies; signed: team's Developer ID everywhere,
#                            tickets stapled, Gatekeeper accepts the apps and the disk image as notarized
#                            Developer ID
#   dmg-window <dmg> <dir>   mounts the disk image as a user would, opens it in Finder and writes
#                            <dir>/dmg-window.png (screencapture) and <dir>/dmg-ds-store.txt (the
#                            window's .DS_Store records, read with ds_store: $DMG_PYTHON or python3)
#   keychain-delete          (CI only) deletes the temporary keychain and the key files
#
# Nothing here prints a secret: the .p12, its password and the notary key come in through the
# environment and go to files under $RUNNER_TEMP that keychain-delete removes; the keychain password
# is a random value masked in the log. A signing step refuses to run outside GitHub Actions
# (signing happens in CI only).
#
# Change history:
# - 2026-11-09 package: the disk image window by dmgbuild (app changes #26) and the zip's bundle name
#   (0.1.4 ships the zip with the old name ChromiteSwing.app, app change #22); verify takes the zip's one
#   bundle by its own name; dmg-window (docs/chromite/distribution.md).
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
NATIVES="$HERE/macos_natives.py"
ENTITLEMENTS="$HERE/entitlements.mac.plist"
KEYCHAIN_NAME=chromite-signing.keychain-db
DMG_DIR="$HERE/dmg"
# The volume name: what the Finder window and the desktop show (not the bundle's file name)
VOLUME=Chromite

fail() {
  echo "release-macos: $*" >&2
  exit 1
}
need() {
  local name
  for name in "$@"; do
    [ -n "${!name:-}" ] || fail "$name is not set"
  done
}
ci_only() {
  [ "${GITHUB_ACTIONS:-}" = true ] || fail "$1 runs only in GitHub Actions (docs/chromite/signing.md)"
  need RUNNER_TEMP
}
sha() { shasum -a 256 "$1" | awk '{print $1}'; }
# `xcrun stapler staple|validate <path>` asks Apple's ticket service over the network; a dropped
# connection is not a defect of the file. Up to 4 tries, 20 s apart; the last failure stays a failure.
stapler_try() {
  local verb=$1 target=$2 try
  for try in 1 2 3 4; do
    if xcrun stapler "$verb" "$target"; then return 0; fi
    [ "$try" = 4 ] && break
    echo "stapler $verb $(basename "$target"): try $try failed, again in 20 s" >&2
    sleep 20
  done
  fail "stapler $verb failed 4 times: $target"
}

cmd_keychain_create() {
  ci_only keychain-create
  need MAC_P12_BASE64 MAC_P12_PASSWORD MAC_TEAM_ID NOTARY_KEY_P8_BASE64 GITHUB_ENV
  local kc="$RUNNER_TEMP/$KEYCHAIN_NAME" pass p12="$RUNNER_TEMP/devid.p12" ids count hash
  pass=$(openssl rand -hex 24)
  echo "::add-mask::$pass"
  security create-keychain -p "$pass" "$kc"
  # no automatic lock during the job (6 h)
  security set-keychain-settings -lut 21600 "$kc"
  security unlock-keychain -p "$pass" "$kc"
  (umask 077 && printf '%s' "$MAC_P12_BASE64" | base64 --decode > "$p12")
  security import "$p12" -k "$kc" -P "$MAC_P12_PASSWORD" -f pkcs12 -T /usr/bin/codesign > /dev/null
  rm -f "$p12"
  # codesign may use the key without a prompt
  security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$pass" "$kc" > /dev/null
  # the keychain in the user's search list (codesign builds the chain from it and the system's)
  # shellcheck disable=SC2046 # the current list is split into its keychain paths on purpose
  security list-keychains -d user -s "$kc" $(security list-keychains -d user | tr -d '"')
  ids=$(security find-identity -v -p codesigning "$kc" | grep -F "Developer ID Application: " | grep -F "($MAC_TEAM_ID)\"" || true)
  count=$(printf '%s' "$ids" | grep -c . || true)
  [ "$count" = 1 ] || fail "the .p12 holds $count valid Developer ID Application identities of the team (one is needed)"
  hash=$(printf '%s' "$ids" | awk '{print $2}')
  echo "MAC_SIGN_IDENTITY=$hash" >> "$GITHUB_ENV"
  echo "MAC_SIGN_KEYCHAIN=$kc" >> "$GITHUB_ENV"
  (umask 077 && printf '%s' "$NOTARY_KEY_P8_BASE64" | base64 --decode > "$RUNNER_TEMP/notary-key.p8")
  echo "NOTARY_KEY_FILE=$RUNNER_TEMP/notary-key.p8" >> "$GITHUB_ENV"
  echo "keychain: one Developer ID Application identity of the team (SHA-1 $hash)"
}

cmd_keychain_delete() {
  local kc="${RUNNER_TEMP:-/nonexistent}/$KEYCHAIN_NAME"
  if [ -f "$kc" ]; then
    security delete-keychain "$kc" || true
    echo "the temporary keychain is deleted"
  fi
  rm -f "${RUNNER_TEMP:-/nonexistent}/devid.p12" "${RUNNER_TEMP:-/nonexistent}/notary-key.p8"
}

cmd_sign() {
  ci_only sign
  need MAC_SIGN_IDENTITY MAC_SIGN_KEYCHAIN
  local app=${1:?sign: the app}
  [ -d "$app" ] || fail "no app: $app"
  # codesign refuses files with extended attributes ("resource fork, Finder information, or
  # similar detritus not allowed")
  xattr -cr "$app"
  # inside out: every nested Mach-O (loose files and jar entries) before the app
  python3 -I "$NATIVES" sign "$app"
  # the app last: this signs its main executable with the entitlements and seals the bundle
  codesign --force --sign "$MAC_SIGN_IDENTITY" --keychain "$MAC_SIGN_KEYCHAIN" \
    --options runtime --timestamp --entitlements "$ENTITLEMENTS" "$app"
  codesign --verify --deep --strict --verbose=2 "$app"
}

# the signature of one file or bundle: the team's Developer ID, a secure timestamp, the hardened runtime
signed_by_team() {
  local file=$1 info
  info=$(codesign -dvv "$file" 2>&1) || fail "$file is not signed"
  grep -q '^Authority=Developer ID Application: ' <<< "$info" || fail "$file is not signed with a Developer ID Application certificate"
  grep -q "^TeamIdentifier=$MAC_TEAM_ID\$" <<< "$info" || fail "$file is not signed by the team"
  grep -q '^Timestamp=' <<< "$info" || fail "$file has no secure timestamp"
  grep -q '^CodeDirectory .*flags=.*runtime' <<< "$info" || fail "$file is not signed with the hardened runtime"
  echo "$file: Developer ID of the team, timestamp $(sed -n 's/^Timestamp=//p' <<< "$info"), hardened runtime"
}

cmd_check_signed() {
  need MAC_TEAM_ID
  local app=${1:?check-signed: the app} signed
  codesign --verify --deep --strict --verbose=2 "$app"
  signed_by_team "$app"
  # the entitlements the app was signed with are the repository's file (compared as plists)
  signed=$(mktemp "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/chromite-entitlements.XXXXXX")
  codesign -d --entitlements - --xml "$app" > "$signed" 2> /dev/null || fail "the entitlements of $app cannot be read"
  python3 -I -c 'import plistlib, sys
a, b = (plistlib.load(open(p, "rb")) for p in sys.argv[1:3])
sys.exit(0 if a == b else "signed with %r, the repository file is %r" % (a, b))' "$signed" "$ENTITLEMENTS" \
    || fail "$app is not signed with $ENTITLEMENTS"
  rm -f "$signed"
  echo "$app: signed with the entitlements of $(basename "$ENTITLEMENTS")"
  python3 -I "$NATIVES" check "$app" "$MAC_TEAM_ID"
}

cmd_notarize() {
  ci_only notarize
  need NOTARY_KEY_FILE NOTARY_KEY_ID NOTARY_ISSUER
  local target=${1:?notarize: an app or a disk image}
  [ -e "$target" ] || fail "no $target"
  local sub=$target result status id
  if [ -d "$target" ]; then
    # notarytool takes an app as a zip of it; the ticket is stapled to the app itself
    sub="$RUNNER_TEMP/notarize-$(basename "$target" .app).zip"
    ditto -c -k --keepParent "$target" "$sub"
  fi
  result="$RUNNER_TEMP/notary-$(basename "$sub").json"
  xcrun notarytool submit "$sub" --key "$NOTARY_KEY_FILE" --key-id "$NOTARY_KEY_ID" \
    --issuer "$NOTARY_ISSUER" --wait --timeout 1h --output-format json > "$result" || true
  status=$(plutil -extract status raw -o - "$result" 2> /dev/null || echo "no answer")
  id=$(plutil -extract id raw -o - "$result" 2> /dev/null || echo "")
  echo "notarization of $(basename "$sub"): $status (submission ${id:-none})"
  if [ "$status" != Accepted ]; then
    if [ -n "$id" ]; then
      xcrun notarytool log "$id" --key "$NOTARY_KEY_FILE" --key-id "$NOTARY_KEY_ID" --issuer "$NOTARY_ISSUER" || true
    fi
    fail "$(basename "$sub") was not notarized: $status"
  fi
  [ "$sub" = "$target" ] || rm -f "$sub"
  stapler_try staple "$target"
  stapler_try validate "$target"
}

cmd_package() {
  local app=${1:?package: the app} base=${2:?package: the file name without extension} dir=${3:?package: the output folder}
  local zipname=${4:-} name stage zip dmg dmgbuild=${DMGBUILD:-dmgbuild}
  [ -d "$app" ] || fail "no app: $app"
  name=$(basename "$app")
  zipname=${zipname:-$name}
  case "$zipname" in */* | .* | *[!A-Za-z0-9._-]*) fail "not a bundle name: $zipname" ;; *.app) ;; *) fail "not a bundle name: $zipname" ;; esac
  command -v "$dmgbuild" > /dev/null || fail "dmgbuild not found ($dmgbuild): pip install --require-hashes --only-binary :all: --no-deps -r tools/release/dmg/requirements.txt"
  mkdir -p "$dir"
  zip="$dir/$base.zip"
  dmg="$dir/$base.dmg"
  rm -f "$zip" "$dmg"
  stage=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/chromite-dmg.XXXXXX")
  # ditto keeps the executable bits, symlinks and the stapled ticket (Contents/CodeResources)
  if [ "$zipname" = "$name" ]; then
    ditto -c -k --keepParent "$app" "$zip"
  else
    # The same signed, stapled bundle under another folder name: the name of the bundle folder is not part
    # of its seal or its notarization ticket (verify checks the copy in the zip like the app itself)
    ditto "$app" "$stage/$zipname"
    ditto -c -k --keepParent "$stage/$zipname" "$zip"
    rm -rf "${stage:?}/$zipname"
  fi
  # The window background: one TIFF with the 1x and the 2x picture, so Retina displays get the sharp one
  tiffutil -cathidpicheck "$DMG_DIR/background.png" "$DMG_DIR/background@2x.png" -out "$stage/background.tiff" > /dev/null
  # dmgbuild writes the window's .DS_Store itself (no Finder scripting), copies the app and links /Applications
  "$dmgbuild" -s "$DMG_DIR/dmg-settings.py" -D app="$app" -D background="$stage/background.tiff" "$VOLUME" "$dmg" > /dev/null
  rm -rf "$stage"
  echo "$(basename "$zip"): $(wc -c < "$zip" | tr -d ' ') bytes, sha256 $(sha "$zip")"
  echo "$(basename "$dmg"): $(wc -c < "$dmg" | tr -d ' ') bytes, sha256 $(sha "$dmg")"
}

cmd_sign_dmg() {
  ci_only sign-dmg
  need MAC_SIGN_IDENTITY MAC_SIGN_KEYCHAIN MAC_TEAM_ID
  local dmg=${1:?sign-dmg: the disk image} info
  codesign --sign "$MAC_SIGN_IDENTITY" --keychain "$MAC_SIGN_KEYCHAIN" --timestamp "$dmg"
  codesign --verify --strict --verbose=2 "$dmg"
  info=$(codesign -dvv "$dmg" 2>&1)
  grep -q '^Authority=Developer ID Application: ' <<< "$info" || fail "the disk image is not signed with a Developer ID Application certificate"
  grep -q "^TeamIdentifier=$MAC_TEAM_ID\$" <<< "$info" || fail "the disk image is not signed by the team"
  grep -q '^Timestamp=' <<< "$info" || fail "the disk image has no secure timestamp"
  echo "$(basename "$dmg"): signed by the team, timestamped"
}

# Gatekeeper's verdict must be "accepted" from a notarized Developer ID; the check is meaningless
# while assessments are off (switched on here, on CI's own machine only)
gatekeeper_on() {
  local status
  status=$(spctl --status 2>&1 || true)
  if grep -q disabled <<< "$status"; then
    ci_only "switching Gatekeeper on"
    sudo -n spctl --global-enable 2> /dev/null || sudo -n spctl --master-enable
    status=$(spctl --status 2>&1 || true)
    grep -q enabled <<< "$status" || fail "Gatekeeper assessments are off"
  fi
}
accepted_notarized() {
  local what=$1 out
  shift
  out=$(spctl "$@" 2>&1) || fail "Gatekeeper rejects $what: $out"
  echo "$out"
  grep -q 'accepted' <<< "$out" || fail "Gatekeeper does not accept $what"
  grep -q '^source=Notarized Developer ID' <<< "$out" || fail "$what is not accepted as a notarized Developer ID"
}

# one copy of the app (as signed, from the disk image, from the zip)
check_app() {
  local app=$1 mode=$2 exe
  echo "== $app"
  exe=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$app/Contents/Info.plist")
  [ -x "$app/Contents/MacOS/$exe" ] || fail "$app/Contents/MacOS/$exe is missing or not executable"
  if [ "$mode" = signed ]; then
    codesign --verify --deep --strict --verbose=2 "$app"
    signed_by_team "$app"
    python3 -I "$NATIVES" check "$app" "$MAC_TEAM_ID"
    stapler_try validate "$app"
    accepted_notarized "$app" -a -vv -t exec "$app"
  fi
}

cmd_verify() {
  local mode=${1:?verify: signed or unsigned} app=${2:?verify: the app} dmg=${3:?verify: the disk image} zip=${4:?verify: the zip}
  case "$mode" in signed | unsigned) ;; *) fail "verify: signed or unsigned, not $mode" ;; esac
  if [ "$mode" = signed ]; then
    need MAC_TEAM_ID
    gatekeeper_on
  fi
  local name work mnt zipped
  name=$(basename "$app")
  check_app "$app" "$mode"
  work=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/chromite-verify.XXXXXX")
  mnt="$work/dmg"
  mkdir -p "$mnt"
  hdiutil attach -readonly -nobrowse -noautoopen -mountpoint "$mnt" "$dmg" > /dev/null
  # (the disk image is detached however the check ends)
  trap 'hdiutil detach "'"$mnt"'" -force > /dev/null 2>&1 || true; rm -rf "'"$work"'"' EXIT
  [ -L "$mnt/Applications" ] || fail "the disk image has no Applications link"
  check_app "$mnt/$name" "$mode"
  hdiutil detach "$mnt" > /dev/null
  mkdir -p "$work/zip"
  ditto -x -k "$zip" "$work/zip"
  # the zip holds one bundle; its name may differ from the app's (package's <zip app name>)
  zipped=$(cd "$work/zip" && ls -A)
  case "$zipped" in *.app) ;; *) fail "the zip holds not exactly one app bundle: $(echo $zipped)" ;; esac
  [ "$(printf '%s\n' "$zipped" | wc -l | tr -d ' ')" = 1 ] || fail "the zip holds not exactly one app bundle: $(echo $zipped)"
  check_app "$work/zip/$zipped" "$mode"
  if [ "$mode" = signed ]; then
    stapler_try validate "$dmg"
    accepted_notarized "$(basename "$dmg")" -a -vv -t open --context context:primary-signature "$dmg"
  fi
  echo "verified ($mode): $name, $(basename "$dmg"), $(basename "$zip") ($zipped)"
}

cmd_dmg_window() {
  local dmg=${1:?dmg-window: the disk image} out=${2:?dmg-window: the output folder} attached mnt py=${DMG_PYTHON:-python3}
  [ -f "$dmg" ] || fail "no disk image $dmg"
  mkdir -p "$out"
  # browsable, as a double click mounts it (under /Volumes/<volume name>)
  attached=$(hdiutil attach -readonly -noautoopen "$dmg")
  mnt=$(printf '%s\n' "$attached" | awk -F '\t' '/\/Volumes\// {print $NF; exit}')
  [ -n "$mnt" ] || fail "the disk image was not mounted under /Volumes: $attached"
  trap 'osascript -e "tell application \"Finder\" to close every window" > /dev/null 2>&1 || true; hdiutil detach "'"$mnt"'" -force > /dev/null 2>&1 || true' EXIT
  echo "mounted at $mnt"
  ls -la "$mnt"
  "$py" -I - "$mnt/.DS_Store" > "$out/dmg-ds-store.txt" << 'EOF' || fail "the .DS_Store cannot be read (ds_store: DMG_PYTHON)"
import sys
from ds_store import DSStore
with DSStore.open(sys.argv[1], "r") as store:
    for entry in sorted(store, key=lambda e: (e.filename, e.code)):
        print(entry.filename, entry.code.decode("ascii", "replace"), entry.type, entry.value)
EOF
  cat "$out/dmg-ds-store.txt"
  # the window as Finder shows it from the .DS_Store
  open "$mnt"
  sleep 5
  screencapture -x "$out/dmg-window.png" || fail "screencapture failed"
  echo "dmg-window.png: $(wc -c < "$out/dmg-window.png" | tr -d ' ') bytes"
}

case "${1:-}" in
  keychain-create) cmd_keychain_create ;;
  keychain-delete) cmd_keychain_delete ;;
  sign) cmd_sign "${2:-}" ;;
  check-signed) cmd_check_signed "${2:-}" ;;
  notarize) cmd_notarize "${2:-}" ;;
  package) cmd_package "${2:-}" "${3:-}" "${4:-}" ;;
  sign-dmg) cmd_sign_dmg "${2:-}" ;;
  verify) cmd_verify "${2:-}" "${3:-}" "${4:-}" "${5:-}" ;;
  dmg-window) cmd_dmg_window "${2:-}" "${3:-}" ;;
  *) fail "usage: release-macos.sh keychain-create|sign <app>|check-signed <app>|notarize <app|dmg>|package <app> <base> <dir> [<zip app name>]|sign-dmg <dmg>|verify signed|unsigned <app> <dmg> <zip>|dmg-window <dmg> <dir>|keychain-delete" ;;
esac
