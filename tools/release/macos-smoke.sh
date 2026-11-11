#!/bin/bash
# Install-and-run smoke test of the Chromite macOS app (Apple silicon) for
# .github/workflows/macos-smoke.yml, one subcommand per workflow step. It runs on a GitHub-hosted
# macos-15 runner, which has a logged-in GUI session, and checks what had only been checked by reading
# code: the app starts from a quarantined copy, Quit goes through the main window's closing path
# (docs/chromite/platform-startup.md), saved passwords go to the Keychain-protected secure storage
# (docs/chromite/credential-storage.md), the About and Preferences items of the app menu.
# Design and results: docs/chromite/ci.md "macOS 설치·실행 시험". bash 3.2 (macOS's own) is enough.
#
#   install <dmg>            quarantines the disk image (as a browser download does), mounts it,
#                            copies the app to /Applications, detaches; writes APP to $GITHUB_ENV
#   unpack <tar.gz>          (unsigned build from a branch) unpacks the app to /Applications and
#                            signs it ad hoc (arm64 runs no unsigned code); writes APP to $GITHUB_ENV
#   verify <dmg>             codesign --verify --deep --strict, Developer ID and timestamp, Gatekeeper
#                            (spctl) accepts the app and the disk image as notarized, stapled tickets
#   workspace                test data folder (CHROMITE_DATA) and a workspace with one SQLite
#                            connection whose fake password is in the pre-#111 credentials file
#   launch <label>           open(1) with CHROMITE_DATA and -data, waits for the startup log line and a
#                            window of the app (answers Gatekeeper's first-launch question once),
#                            screenshots <label>-splash.png (and -splash-2..4 when the windows change) of the
#                            startup splash, <label>-main-window.png and <label>-title-bar.png; every
#                            splash-sized window polled (<label>-splash-positions.txt) must be at one place
#                            (the launcher's splash hands over to the Swing one in place, app change #52);
#                            the splash must be gone once the main window is up;
#                            without a window: process, native stacks (sample) and a JVM thread dump
#                            (SIGQUIT into <label>-stdout.txt)
#   secure-storage           the secure storage file, the Keychain item and the debug log
#   menus                    the window menus in the screen menu bar, About and Preferences of the app
#                            menu (System Events), screenshots
#   titlebar                 the main window's unified title bar (app change #62): title-bar-light.png,
#                            title-bar-dark.png (system appearance switched to dark and back),
#                            main-window-fullscreen.png
#   quit <appleevent|keystroke>
#                            Quit by the Apple event quit or by Cmd+Q; the process must end through
#                            the main window's closing path (debug log), no crash report
#   txn                      Cmd+Q with an open transaction of the SQLite connection: the transaction
#                            question, Cancel keeps the app, Roll Back rolls back and ends it
#   stop                     stops an app copy this test started that still runs (after a failed quit)
#   collect                  stop, then logs, Info.plist, launcher ini and crash reports to the output
#                            folder and result.txt to the job summary
#
# Everything lands in $SMOKE_DIR/out (uploaded as a workflow artifact); result.txt holds one line
# per check. The password is a fake constant and is never printed; `security find-generic-password`
# runs without -g/-w, so it prints the item's attributes only.
set -euo pipefail

SMOKE_DIR=${SMOKE_DIR:-${RUNNER_TEMP:-/tmp}/chromite-smoke}
OUT=$SMOKE_DIR/out
DATA=$SMOKE_DIR/data
WS=$SMOKE_DIR/workspace
DEBUG_LOG=$WS/.metadata/chromite-debug.log
LAUNCH_TIMEOUT=${LAUNCH_TIMEOUT:-120}
QUIT_TIMEOUT=${QUIT_TIMEOUT:-60}

# The connection of the test workspace; the password is fake and only has to reach the secure storage
CONN_ID=sqlite_jdbc-smoke-1
FAKE_USER=smoke
FAKE_PASSWORD=smoke-fake-password-0000
# MacOSKeychain.SERVICE / ACCOUNT (plugins/com.seizia.chromite.model.security)
KEYCHAIN_SERVICE=com.seizia.chromite.SecureStorage
KEYCHAIN_ACCOUNT=master-password
# BaseProjectImpl.LOCAL_KEY_CACHE: the fixed key of the pre-#111 credentials-config.json, public in the source
LEGACY_KEY=babb4a9f774ab853c96c2d653dfe544a
# ChromiteSwingApplication.run logs this once the main window is shown, with the milliseconds of each startup step
# after the process started. Before it, the only window of the process may be the startup splash
# (docs/chromite/branding.md "시작 화면"), so launch also waits for this line and not for a window alone.
STARTUP_LINE='Startup times'
# Size of the startup splash in points, the native launcher's and the Swing one's (plugins/com.seizia.chromite.splash)
SPLASH_SIZE='560, 320'
# The same size as window_rects prints it
SPLASH_RECT_SIZE='560 320'
# Height in points of the main window's top strip in the title bar screenshots: the title bar row and the SQL Editor row
TITLE_STRIP=90

mkdir -p "$OUT"

result() {
  echo "$1: $2"
  echo "$1: $2" >> "$OUT/result.txt"
}
pass() { result PASS "$*"; }
note() { result NOTE "$*"; }
fail() {
  result FAIL "$*" >&2
  exit 1
}
# perl's alarm ends a command that waits for a TCC or Gatekeeper question nobody answers
with_timeout() {
  local seconds=$1
  shift
  perl -e 'alarm shift; exec @ARGV' "$seconds" "$@"
}
shot() {
  screencapture -x "$OUT/$1.png" 2> /dev/null && echo "screenshot $1.png" || note "screencapture failed for $1"
}
need_app() {
  [ -n "${APP:-}" ] && [ -d "$APP" ] || fail "APP is not set or is not an app folder (install or unpack first)"
}
plist() { /usr/libexec/PlistBuddy -c "Print :$1" "$APP/Contents/Info.plist"; }
app_pid() {
  pgrep -f "/Contents/MacOS/$(plist CFBundleExecutable)( |\$)" | head -1 || true
}
log_lines() {
  if [ -f "$DEBUG_LOG" ]; then wc -l < "$DEBUG_LOG" | tr -d ' '; else echo 0; fi
}

# Windows through the Accessibility API (System Events, which has that permission on the runner). The window
# server's list (CGWindowListCopyWindowInfo) shows other processes' windows only with the Screen Recording
# permission: the first runs saw only the Dock, not the app nor Gatekeeper's question.
ax_process() {
  if [ "$1" = pid ]; then echo "(first process whose unix id is $2)"; else echo "process \"$2\""; fi
}
window_count() {
  with_timeout 20 osascript -e "tell application \"System Events\" to count windows of $(ax_process "$@")" 2> /dev/null || echo 0
}
window_list() {
  with_timeout 20 osascript -e "tell application \"System Events\" to get {name, position, size} of every window of $(ax_process "$@")" 2>&1 || true
}
# One line per window: x y width height (points, from the top left of the main screen)
window_rects() {
  with_timeout 20 osascript - << EOF 2> /dev/null || true
tell application "System Events"
  set out to ""
  repeat with w in (every window of $(ax_process "$@"))
    set {px, py} to position of w
    set {sx, sy} to size of w
    set out to out & px & " " & py & " " & sx & " " & sy & linefeed
  end repeat
  return out
end tell
EOF
}
now() { perl -MTime::HiRes=time -e 'printf "%.2f", time'; }
# Screenshot of the top strip of the app's largest window (the main window)
title_bar_shot() {
  local pid=$1 name=$2 rect
  rect=$(window_rects pid "$pid" | awk 'NF == 4 && $3 * $4 > best { best = $3 * $4; r = $1 "," $2 "," $3 } END { print r }')
  if [ -z "$rect" ]; then
    note "no main window rectangle for $name"
    return 0
  fi
  screencapture -x -R "$rect,$TITLE_STRIP" "$OUT/$name.png" 2> /dev/null && echo "screenshot $name.png ($rect,$TITLE_STRIP)" || note "screencapture failed for $name"
}

gatekeeper_on() {
  local status
  status=$(spctl --status 2>&1 || true)
  echo "Gatekeeper: $status"
  if grep -q disabled <<< "$status"; then
    # The runner's own machine only; an assessment is meaningless while Gatekeeper is off
    [ "${GITHUB_ACTIONS:-}" = true ] || fail "Gatekeeper is off; switch it on (spctl --global-enable)"
    sudo -n spctl --global-enable 2> /dev/null || sudo -n spctl --master-enable
    status=$(spctl --status 2>&1 || true)
    grep -q enabled <<< "$status" || fail "Gatekeeper assessments are off"
    note "Gatekeeper was off on the runner and was switched on"
  fi
}

cmd_install() {
  local dmg=${1:?install: the disk image} mnt name
  [ -f "$dmg" ] || fail "no disk image $dmg"
  gatekeeper_on
  # As a browser download: Gatekeeper assesses quarantined files when they are first opened
  xattr -w com.apple.quarantine "0083;$(printf '%x' "$(date +%s)");Safari;$(uuidgen)" "$dmg"
  mnt=$SMOKE_DIR/mnt
  mkdir -p "$mnt"
  hdiutil attach -readonly -nobrowse -noautoopen -mountpoint "$mnt" "$dmg" > /dev/null
  ls -la "$mnt" > "$OUT/dmg-contents.txt"
  name=$(cd "$mnt" && ls -d -- *.app)
  [ "$(printf '%s\n' "$name" | wc -l | tr -d ' ')" = 1 ] || { hdiutil detach "$mnt" > /dev/null; fail "the disk image holds not one app: $name"; }
  [ -L "$mnt/Applications" ] || note "the disk image has no Applications link"
  rm -rf "/Applications/$name"
  # ditto keeps the extended attributes, as a copy out of the mounted image does
  ditto "$mnt/$name" "/Applications/$name"
  hdiutil detach "$mnt" > /dev/null
  APP=/Applications/$name
  if xattr -p com.apple.quarantine "$APP" > /dev/null 2>&1; then
    pass "installed $name from $(basename "$dmg") to /Applications; the copy carries the quarantine of the image ($(xattr -p com.apple.quarantine "$APP"))"
  else
    xattr -w com.apple.quarantine "0083;$(printf '%x' "$(date +%s)");Safari;$(uuidgen)" "$APP"
    note "the copy did not inherit the quarantine of the image; set on $name by hand"
  fi
  echo "APP=$APP" >> "${GITHUB_ENV:-/dev/null}"
}

cmd_unpack() {
  local tarball=${1:?unpack: the app tar.gz} name
  name=$(tar -tzf "$tarball" | head -1 | cut -d/ -f1)
  case "$name" in *.app) ;; *) fail "the archive holds no app: $name" ;; esac
  rm -rf "/Applications/$name"
  tar -xzf "$tarball" -C /Applications
  APP=/Applications/$name
  # The Linux build carries third-party signatures and an Info.plist that breaks the launcher's seal;
  # Apple silicon runs no unsigned code, so the unsigned check signs ad hoc (no hardened runtime)
  codesign --force --deep --sign - "$APP"
  note "unsigned build: $name unpacked to /Applications and signed ad hoc, no quarantine; signature and Gatekeeper checks skipped"
  echo "APP=$APP" >> "${GITHUB_ENV:-/dev/null}"
}

cmd_verify() {
  local dmg=${1:?verify: the disk image} out
  need_app
  gatekeeper_on
  codesign --verify --deep --strict --verbose=2 "$APP" > "$OUT/codesign-verify.txt" 2>&1 \
    || { cat "$OUT/codesign-verify.txt"; fail "codesign --verify --deep --strict rejects $APP"; }
  cat "$OUT/codesign-verify.txt"
  codesign -dvv "$APP" 2>&1 | grep -E '^(Identifier|Format|Authority|TeamIdentifier|Timestamp|Runtime Version)=' > "$OUT/codesign-display.txt" || true
  cat "$OUT/codesign-display.txt"
  grep -q '^Authority=Developer ID Application: ' "$OUT/codesign-display.txt" || fail "the app is not signed with a Developer ID Application certificate"
  grep -q '^Timestamp=' "$OUT/codesign-display.txt" || fail "the app signature has no secure timestamp"
  pass "codesign --verify --deep --strict: $(basename "$APP") ($(grep '^Authority=Developer ID' "$OUT/codesign-display.txt" | head -1 | cut -d= -f2))"
  out=$(spctl -a -vv -t exec "$APP" 2>&1) || { echo "$out" > "$OUT/spctl-app.txt"; fail "Gatekeeper rejects the app: $out"; }
  echo "$out" | tee "$OUT/spctl-app.txt"
  grep -q accepted <<< "$out" && grep -q '^source=Notarized Developer ID' <<< "$out" || fail "the app is not accepted as a notarized Developer ID"
  pass "spctl --assess --type execute: accepted, source=Notarized Developer ID (quarantined copy)"
  xcrun stapler validate "$APP" > "$OUT/stapler-app.txt" 2>&1 || { cat "$OUT/stapler-app.txt"; fail "no valid ticket stapled to the app"; }
  xcrun stapler validate "$dmg" > "$OUT/stapler-dmg.txt" 2>&1 || { cat "$OUT/stapler-dmg.txt"; fail "no valid ticket stapled to the disk image"; }
  pass "xcrun stapler validate: the app and $(basename "$dmg")"
  out=$(spctl -a -vv -t open --context context:primary-signature "$dmg" 2>&1) || { echo "$out" > "$OUT/spctl-dmg.txt"; fail "Gatekeeper rejects the disk image: $out"; }
  echo "$out" | tee "$OUT/spctl-dmg.txt"
  grep -q '^source=Notarized Developer ID' <<< "$out" || fail "the disk image is not accepted as a notarized Developer ID"
  pass "spctl --assess --type open: $(basename "$dmg") accepted, source=Notarized Developer ID"
  # Apple's own pre-distribution check (macOS 14+); informational
  if command -v syspolicy_check > /dev/null; then
    if syspolicy_check distribution "$APP" > "$OUT/syspolicy-check.txt" 2>&1; then
      pass "syspolicy_check distribution: no issues"
    else
      note "syspolicy_check distribution reports issues (syspolicy-check.txt)"
    fi
  fi
}

cmd_workspace() {
  local project=$WS/General db iv json
  rm -rf "$DATA" "$WS"
  mkdir -p "$DATA" "$project/.chromite"
  db=$SMOKE_DIR/smoke.sqlite
  rm -f "$db"
  sqlite3 "$db" "create table smoke(id integer primary key, note text); insert into smoke(note) values ('before');"
  # DesktopWorkspaceImpl adds every workspace folder with a .project as a project
  cat > "$project/.project" << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<projectDescription>
	<name>General</name>
	<comment></comment>
	<projects>
	</projects>
	<buildSpec>
	</buildSpec>
	<natures>
		<nature>com.seizia.chromite.ChromiteNature</nature>
	</natures>
</projectDescription>
EOF
  # Connection type "smoke": manual commit, so the txn check's INSERT leaves a transaction open; no
  # confirmations before executing
  cat > "$project/.chromite/data-sources.json" << EOF
{
	"connection-types": {
		"smoke": {
			"name": "Smoke",
			"color": "255,255,255",
			"description": "macOS smoke test: manual commit",
			"auto-commit": false,
			"confirm-execute": false,
			"confirm-data-change": false
		}
	},
	"connections": {
		"$CONN_ID": {
			"provider": "sqlite",
			"driver": "sqlite_jdbc",
			"name": "Smoke SQLite",
			"save-password": true,
			"configuration": {
				"database": "$db",
				"url": "jdbc:sqlite:$db",
				"configurationType": "MANUAL",
				"type": "smoke"
			}
		}
	}
}
EOF
  # The pre-#111 file: IV + AES/CBC/PKCS5 with the fixed key (DefaultValueEncryptor). The first start
  # moves it to the secure storage and deletes it (ProjectCredentialsStorage), which creates the Keychain item
  json="{\"$CONN_ID\":{\"#connection\":{\"user\":\"$FAKE_USER\",\"password\":\"$FAKE_PASSWORD\"}}}"
  iv=$(openssl rand -hex 16)
  {
    printf '%s' "$iv" | xxd -r -p
    printf '%s' "$json" | openssl enc -aes-128-cbc -K "$LEGACY_KEY" -iv "$iv"
  } > "$project/.chromite/credentials-config.json"
  [ "$(tail -c +17 "$project/.chromite/credentials-config.json" | openssl enc -d -aes-128-cbc -K "$LEGACY_KEY" -iv "$iv")" = "$json" ] \
    || fail "the legacy credentials file does not decrypt back"
  pass "test workspace: one SQLite connection with a saved fake password in the pre-#111 credentials file; CHROMITE_DATA=$DATA"
  echo "CHROMITE_DATA_DIR=$DATA" >> "${GITHUB_ENV:-/dev/null}"
}

# What a start that shows no window leaves behind: the process and its native stacks, the folders it should
# have written, Equinox's own logs (configuration area, also inside a translocated copy), its unified log
launch_diagnostics() {
  local label=$1 pid=$2 f=$OUT/$1-diagnostics.txt exe
  {
    echo "== process"
    [ -n "$pid" ] && ps -o pid=,ppid=,stat=,etime=,command= -p "$pid" | cut -c1-600
    echo "== windows (System Events)"
    [ -n "$pid" ] && window_list pid "$pid"
    echo "== workspace, data folder, default data folder"
    find "$WS" "$DATA" "$HOME/Library/ChromiteData" -maxdepth 4 2> /dev/null | head -100
    echo "== Equinox logs"
    exe=$(ps -o command= -p "${pid:-0}" 2> /dev/null | awk '{print $1}')
    for dir in "$APP/Contents/Eclipse/configuration" "${exe%/Contents/MacOS/*}/Contents/Eclipse/configuration" "$HOME/.eclipse"; do
      find "$dir" -maxdepth 4 -name '*.log' 2> /dev/null | while read -r log; do echo "--- $log"; tail -n 80 "$log"; done
    done
    for log in "$WS/.metadata/.log" "$HOME/Library/ChromiteData/workspace6/.metadata/.log" "$HOME/Library/ChromiteData/workspace6/.metadata/chromite-debug.log"; do
      [ -f "$log" ] && { echo "--- $log"; tail -n 80 "$log"; }
    done
    echo "== unified log of the process (last 5 minutes)"
    log show --last 5m --style compact --predicate 'process == "chromite-swing"' 2> /dev/null | tail -n 200
  } > "$f" 2>&1 || true
  [ -n "$pid" ] && sample "$pid" 3 -file "$OUT/$label-sample.txt" > /dev/null 2>&1 || true
  # SIGQUIT: the JVM prints its thread dump to the app's stdout ($label-stdout.txt, open --stdout)
  if [ -n "$pid" ]; then
    kill -QUIT "$pid" 2> /dev/null || true
    sleep 3
  fi
}

cmd_launch() {
  local label=${1:?launch: a label} before pid= deadline clicked=0 up=0 splash=0 n prompt times rects last_rects= positions line
  need_app
  [ -z "$(app_pid)" ] || fail "an app copy already runs"
  # A start may rotate or rewrite the debug log: keep the previous one aside and wait for a new file
  if [ -f "$DEBUG_LOG" ]; then mv "$DEBUG_LOG" "$OUT/debug-log-before-$label.txt"; fi
  before=0
  # open(1) waits while LaunchServices shows Gatekeeper's question, so it runs in the background
  # LAUNCHER_ARGS (workflow input launcher_args): extra launcher options for an experiment, split on spaces
  # shellcheck disable=SC2086
  open -n --env "CHROMITE_DATA=$DATA" --stdout "$OUT/$label-stdout.txt" --stderr "$OUT/$label-stderr.txt" "$APP" --args ${LAUNCHER_ARGS:-} -data "$WS" > "$OUT/$label-open.txt" 2>&1 &
  [ -z "${LAUNCHER_ARGS:-}" ] || note "$label: extra launcher arguments for this run: $LAUNCHER_ARGS"
  deadline=$((SECONDS + LAUNCH_TIMEOUT))
  while [ $SECONDS -lt $deadline ]; do
    pid=$(app_pid)
    if [ $clicked = 0 ]; then
      n=$(window_count name CoreServicesUIAgent)
      if [ "${n:-0}" -gt 0 ]; then
        # Gatekeeper's first-launch question for a quarantined app (the process exists already and waits for
        # the answer): shown, then answered once
        sleep 2
        shot "$label-gatekeeper-question"
        prompt=$(with_timeout 30 osascript -e 'tell application "System Events" to tell process "CoreServicesUIAgent" to get value of every static text of window 1' 2>&1 || true)
        echo "$prompt" > "$OUT/$label-gatekeeper-question.txt"
        note "Gatekeeper asked on first launch: $prompt"
        if with_timeout 30 osascript -e 'tell application "System Events" to tell process "CoreServicesUIAgent" to click button "Open" of window 1' >> "$OUT/$label-gatekeeper-question.txt" 2>&1; then
          note "answered Gatekeeper's question with Open (System Events)"
        else
          note "could not press Open in Gatekeeper's question: $(tail -1 "$OUT/$label-gatekeeper-question.txt")"
        fi
        clicked=1
      fi
    fi
    if [ -n "$pid" ] && [ -f "$DEBUG_LOG" ] && tail -n +$((before + 1)) "$DEBUG_LOG" | grep -q "$STARTUP_LINE"; then
      n=$(window_count pid "$pid" || echo 0)
      if [ "${n:-0}" -gt 0 ]; then up=1; break; fi
    elif [ -n "$pid" ]; then
      # Windows before the main window: the launcher's splash, then the Swing one in its place (app change #52).
      # Every poll is logged, and a screenshot is taken when the windows change (at most 4), so a jump shows in the
      # pictures too. Short polls: the hand-over takes a moment
      rects=$(window_rects pid "$pid")
      if [ -n "$rects" ]; then
        echo "$rects" | sed "s/^/$(now) /" >> "$OUT/$label-splash-positions.txt"
        if [ "$rects" != "$last_rects" ] && [ $splash -lt 4 ]; then
          splash=$((splash + 1))
          if [ $splash = 1 ]; then
            window_list pid "$pid" > "$OUT/$label-splash-windows.txt"
            shot "$label-splash"
          else
            shot "$label-splash-$splash"
          fi
          last_rects=$rects
        fi
      fi
      sleep 0.3
      continue
    fi
    sleep 2
  done
  if [ $up = 0 ]; then
    shot "$label-timeout"
    launch_diagnostics "$label" "${pid:-}"
    fail "$label: no main window within ${LAUNCH_TIMEOUT}s (pid ${pid:-none}; open: $(tr '\n' ' ' < "$OUT/$label-open.txt"))"
  fi
  # The window is up; give it time to lay out and paint
  sleep 8
  window_list pid "$pid" | tee "$OUT/$label-windows.txt"
  shot "$label-main-window"
  title_bar_shot "$pid" "$label-title-bar"
  if grep -q "$SPLASH_SIZE" "$OUT/$label-windows.txt"; then
    fail "$label: a window of the startup splash's size ($SPLASH_SIZE) is still open after the main window (see $label-windows.txt)"
  fi
  if [ $splash -gt 0 ] && grep -q "$SPLASH_SIZE" "$OUT/$label-splash-windows.txt"; then
    pass "$label: the startup splash was shown before the main window and is closed"
  else
    note "$label: no splash-sized window was seen before the main window (startup may have been faster than one poll)"
  fi
  # The launcher's splash and the Swing one: one place for every splash-sized window of every poll (reported, not fatal).
  # When the app is up before a poll sees a splash window, no poll wrote the file; awk on a missing file fails, and with
  # pipefail that ended the script here with exit code 2 although the app had started (devel run of 2026-11-12)
  [ -f "$OUT/$label-splash-positions.txt" ] || : > "$OUT/$label-splash-positions.txt"
  positions=$(awk -v size="$SPLASH_RECT_SIZE" '$4 " " $5 == size { print $2 ", " $3 }' "$OUT/$label-splash-positions.txt" 2> /dev/null | sort -u)
  n=$(printf '%s' "$positions" | grep -c . || true)
  if [ "$n" = 1 ]; then
    pass "$label: the startup splash stayed at $positions ($(grep -c . "$OUT/$label-splash-positions.txt") window polls)"
  elif [ "$n" -gt 1 ]; then
    result FAIL "$label: the startup splash moved: $(echo "$positions" | tr '\n' ';') ($label-splash-positions.txt)"
  else
    note "$label: no splash position was polled"
  fi
  line=$(grep 'Startup splash at' "$DEBUG_LOG" | tail -1 | sed 's/.*Startup splash at/Startup splash at/' || true)
  [ -z "$line" ] || note "$label: $line"
  line=$(grep 'macOS unified title bar' "$DEBUG_LOG" | tail -1 || true)
  case "$line" in
    '') note "$label: no 'macOS unified title bar' line in the debug log (menus not in the screen menu bar?)" ;;
    *'without FlatLaf'*) result FAIL "$label: macOS unified title bar${line#*macOS unified title bar}: the tool bar row can't move the window" ;;
    *) note "$label: macOS unified title bar${line#*macOS unified title bar}" ;;
  esac
  times=$(grep "$STARTUP_LINE" "$DEBUG_LOG" | tail -1 | sed 's/.*Startup times/Startup times/' || true)
  note "$label: $times"
  ps -o pid=,etime=,command= -p "$pid" | cut -c1-400 > "$OUT/$label-process.txt"
  if grep -q AppTranslocation "$OUT/$label-process.txt"; then
    note "$label: the app runs translocated (App Translocation, read-only random path)"
  fi
  pass "$label: main window shown, pid $pid, startup line '$STARTUP_LINE' in the debug log"
}

cmd_secure_storage() {
  local store=$DATA/ChromiteData/secure/secure_storage props=$WS/General/.chromite/credentials-storage.properties errors failed=0
  [ -s "$store" ] || fail "no secure storage file $store"
  ls -la "$(dirname "$store")" > "$OUT/secure-folder.txt"
  pass "secure storage file under the test data folder: $store ($(wc -c < "$store" | tr -d ' ') bytes)"
  if grep -q -- "$FAKE_PASSWORD" "$store"; then
    result FAIL "the secure storage file holds the password in plain text"
    failed=1
  fi
  # Keys and module ids only: Equinox writes <key>=<module id>\t<iv>,<salt>,<data> (a properties file, \t escaped)
  sed -E 's/(\\t).*/\1<encrypted>/' "$store" > "$OUT/secure-storage-redacted.txt"
  # Equinox writes module ids in lower case
  if grep -qi 'MacOSSecureStorageKeychain' "$store"; then
    pass "values encrypted by the Keychain provider (MacOSSecureStorageKeychain), no plain text password"
  else
    result FAIL "no value of the secure storage names the Keychain provider (secure-storage-redacted.txt)"
    failed=1
  fi
  if [ ! -e "$WS/General/.chromite/credentials-config.json" ] && grep -q '^legacy.migrated.credentials-config.json=' "$props" 2> /dev/null; then
    pass "the pre-#111 credentials-config.json was moved to the secure storage and deleted"
  else
    result FAIL "the pre-#111 credentials file was not moved (or credentials-storage.properties has no record)"
    failed=1
  fi
  {
    security list-keychains
    security default-keychain
    security show-keychain-info "$HOME/Library/Keychains/login.keychain-db" 2>&1 || true
  } > "$OUT/keychains.txt" 2>&1
  cat "$OUT/keychains.txt"
  # Attributes only: without -g/-w the password is not printed (nor asked for)
  if security find-generic-password -s "$KEYCHAIN_SERVICE" -a "$KEYCHAIN_ACCOUNT" > "$OUT/keychain-item.txt" 2>&1; then
    pass "Keychain item $KEYCHAIN_SERVICE / $KEYCHAIN_ACCOUNT in $(sed -n 's/^keychain: //p' "$OUT/keychain-item.txt")"
  else
    cat "$OUT/keychain-item.txt"
    result FAIL "no Keychain item $KEYCHAIN_SERVICE / $KEYCHAIN_ACCOUNT in the file keychains (the data protection keychain is not searchable by security(1))"
    failed=1
  fi
  [ ! -e "$HOME/Library/ChromiteData" ] || note "the default data folder ~/Library/ChromiteData exists too"
  errors=$(grep -inE 'secur|keychain|credential' "$DEBUG_LOG" | grep -iE 'error|exception|fail' || true)
  if [ -n "$errors" ]; then
    echo "$errors" > "$OUT/secure-storage-errors.txt"
    result FAIL "the debug log has secure storage errors (secure-storage-errors.txt): $(echo "$errors" | head -3)"
    failed=1
  else
    pass "no secure storage error in the debug log"
  fi
  [ $failed = 0 ] || exit 1
}

# System Events: the app menu is menu bar item 2 (item 1 is the Apple menu)
app_menu() {
  local pid=$1 script=$2
  with_timeout 30 osascript - << EOF
tell application "System Events"
  set p to first process whose unix id is $pid
  set frontmost of p to true
  delay 1
  tell menu 1 of menu bar item 2 of menu bar 1 of p
    $script
  end tell
end tell
EOF
}
close_dialog() {
  local pid=$1 label=$2 n
  # About and Options close on Escape (their WHEN_IN_FOCUSED_WINDOW key binding)
  with_timeout 30 osascript -e 'tell application "System Events" to key code 53' > /dev/null 2>&1 || true
  sleep 2
  n=$(window_count pid "$pid")
  [ "$n" -le 1 ] || note "$label: still $n windows after Escape"
}

cmd_menus() {
  local pid items bar before rc=0 n
  need_app
  pid=$(app_pid)
  [ -n "$pid" ] || fail "the app does not run"
  items=$(app_menu "$pid" 'return name of every menu item' 2>&1) || { echo "$items" > "$OUT/app-menu.txt"; fail "System Events cannot read the app menu: $items"; }
  echo "$items" | tee "$OUT/app-menu.txt"
  [ -n "$items" ] || fail "the application menu is empty"
  # The main window's menus in the screen menu bar after the Apple and application menus (docs/chromite/mac-ui.md)
  bar=$(with_timeout 30 osascript -e "tell application \"System Events\" to get name of every menu bar item of menu bar 1 of (first process whose unix id is $pid)" 2>&1 || true)
  echo "$bar" | tee "$OUT/menu-bar.txt"
  case "$bar" in
    *File*Edit*View*Query*Tools*Window*) pass "the window menus are in the screen menu bar: $bar" ;;
    *) note "the window menus are not in the screen menu bar: $bar"; rc=1 ;;
  esac
  before=$(window_count pid "$pid")
  if app_menu "$pid" 'click menu item 1' > "$OUT/menu-about.txt" 2>&1; then
    sleep 3
    n=$(window_count pid "$pid")
    window_list pid "$pid" >> "$OUT/menu-about.txt"
    shot about
    if [ "$n" -gt "$before" ]; then pass "About (app menu item 1) opened a window"; else note "About: no new window ($n windows)"; rc=1; fi
    close_dialog "$pid" About
  else
    note "About: System Events could not click: $(tail -1 "$OUT/menu-about.txt")"
    rc=1
  fi
  # Chromite's application menu says Options… (MacOSAppMenu); AWT's own says Settings… (macOS 13+) or Preferences…
  if app_menu "$pid" 'click (first menu item whose name starts with "Settings" or name starts with "Preferences" or name starts with "Options")' > "$OUT/menu-preferences.txt" 2>&1; then
    sleep 3
    n=$(window_count pid "$pid")
    window_list pid "$pid" >> "$OUT/menu-preferences.txt"
    shot preferences
    if [ "$n" -gt "$before" ]; then pass "Settings/Preferences (app menu) opened a window"; else note "Preferences: no new window ($n windows)"; rc=1; fi
    close_dialog "$pid" Preferences
  else
    note "Preferences: System Events could not click: $(tail -1 "$OUT/menu-preferences.txt")"
    rc=1
  fi
  [ $rc = 0 ] || fail "the menu check is incomplete (see the notes above and the screenshots)"
}

# System appearance (light or dark). The app reads it again when it becomes active after another app (SystemTheme).
set_dark_mode() {
  with_timeout 30 osascript -e "tell application \"System Events\" to tell appearance preferences to set dark mode to $1" > /dev/null 2>&1 || note "could not set dark mode to $1"
}
reactivate() {
  local pid=$1
  with_timeout 30 osascript -e 'tell application "Finder" to activate' > /dev/null 2>&1 || true
  sleep 2
  with_timeout 30 osascript -e "tell application \"System Events\" to set frontmost of (first process whose unix id is $pid) to true" > /dev/null 2>&1 || true
  sleep 6
}
full_screen() {
  with_timeout 30 osascript -e "tell application \"System Events\" to set value of attribute \"AXFullScreen\" of window 1 of (first process whose unix id is $1) to $2" >> "$OUT/fullscreen.txt" 2>&1
}

# The unified title bar (app change #62) in both appearances and in full screen, judged from the pictures: the title
# bar area has the app's canvas colour, the traffic-light buttons sit left of the first tool bar button, no title
# text; in full screen the tool bar starts at the left edge.
cmd_titlebar() {
  local pid rc=0
  need_app
  pid=$(app_pid)
  [ -n "$pid" ] || fail "the app does not run"
  echo "system appearance: $(defaults read -g AppleInterfaceStyle 2> /dev/null || echo Light)"
  reactivate "$pid"
  title_bar_shot "$pid" title-bar-light
  set_dark_mode true
  reactivate "$pid"
  title_bar_shot "$pid" title-bar-dark
  shot main-window-dark
  set_dark_mode false
  reactivate "$pid"
  if full_screen "$pid" true; then
    sleep 6
    shot main-window-fullscreen
    full_screen "$pid" false || rc=1
    sleep 6
  else
    note "titlebar: could not switch to full screen: $(tail -1 "$OUT/fullscreen.txt")"
    rc=1
  fi
  window_list pid "$pid" > "$OUT/titlebar-windows.txt"
  if [ $rc = 0 ]; then
    pass "titlebar: screenshots title-bar-light, title-bar-dark, main-window-dark, main-window-fullscreen (judged by eye)"
  else
    note "titlebar: the full screen check is incomplete (see the notes above)"
  fi
}

cmd_quit() {
  local method=${1:?quit: appleevent or keystroke} pid bid before deadline tail_log crashes
  need_app
  pid=$(app_pid)
  [ -n "$pid" ] || fail "the app does not run"
  bid=$(plist CFBundleIdentifier)
  before=$(log_lines)
  touch "$SMOKE_DIR/quit-$method.stamp"
  case "$method" in
    appleevent)
      # The quit Apple event goes to the handler of ChromiteSwingApplication.installDesktopHandlers
      with_timeout 60 osascript -e "tell application id \"$bid\" to quit" > "$OUT/quit-$method-osascript.txt" 2>&1 \
        || note "osascript quit: $(tr '\n' ' ' < "$OUT/quit-$method-osascript.txt")"
      ;;
    keystroke)
      with_timeout 60 osascript - > "$OUT/quit-$method-osascript.txt" 2>&1 << EOF || note "osascript Cmd+Q: $(tr '\n' ' ' < "$OUT/quit-$method-osascript.txt")"
tell application "System Events"
  set frontmost of (first process whose unix id is $pid) to true
  delay 1
  keystroke "q" using command down
end tell
EOF
      ;;
    *) fail "quit: appleevent or keystroke, not $method" ;;
  esac
  deadline=$((SECONDS + QUIT_TIMEOUT))
  while kill -0 "$pid" 2> /dev/null && [ $SECONDS -lt $deadline ]; do sleep 1; done
  tail_log=$OUT/quit-$method-debug-log.txt
  tail -n +$((before + 1)) "$DEBUG_LOG" > "$tail_log" 2> /dev/null || true
  if kill -0 "$pid" 2> /dev/null; then
    shot "quit-$method-still-running"
    fail "quit ($method): the app still runs after ${QUIT_TIMEOUT}s"
  fi
  # Main window closed: the latch of ChromiteSwingApplication.start after MainFrame's closing path;
  # an exit by the JDK's default quit (System.exit) would end the process without these lines
  grep -q 'Main window closed' "$tail_log" || fail "quit ($method): the process ended without 'Main window closed' (not the main window's closing path)"
  grep -q '(Swing) shutdown completed' "$tail_log" || fail "quit ($method): no 'shutdown completed' in the debug log"
  crashes=$(find "$HOME/Library/Logs/DiagnosticReports" -newer "$SMOKE_DIR/quit-$method.stamp" -iname '*chromite*' 2> /dev/null || true)
  [ -z "$crashes" ] || fail "quit ($method): crash report $crashes"
  pass "quit ($method): the process ended through the main window's closing path (Main window closed, platform shutdown completed), no crash report"
}

# System Events on the app process: keys and AX clicks (Swing exposes its buttons to the Accessibility API)
on_app() {
  local pid=$1 script=$2
  with_timeout 60 osascript - << EOF
tell application "System Events"
  set p to first process whose unix id is $pid
  set frontmost of p to true
  delay 0.5
  tell p
    $script
  end tell
end tell
EOF
}
# Presses the button named $2 in the app's front window (a JOptionPane), searching its whole AX tree
# ("entire contents" is a list, not an element: it is read once into a variable and walked, no "whose" filter on it)
press_button() {
  on_app "$1" "set elements to entire contents of window 1
    repeat with e in elements
      try
        if role of e is \"AXButton\" then
          -- Swing buttons have no AX title (name); their text is the AX description
          if name of e is \"$2\" or description of e is \"$2\" then
            click e
            return \"pressed $2\"
          end if
        end if
      end try
    end repeat
    error \"no button $2 in \" & name of window 1"
}
# The title, texts and buttons of the app's front window (its AX tree), for the evidence files
front_window_text() {
  on_app "$1" 'set out to {name of window 1}
    set elements to entire contents of window 1
    repeat with e in elements
      try
        set r to role of e
        if r is "AXStaticText" or r is "AXTextArea" then set end of out to (value of e as text)
        if r is "AXButton" then set end of out to ("[" & description of e & "]")
      end try
    end repeat
    return out' 2>&1 || true
}

# Quit while a transaction is open (A-S14 with ExitTransactionCheck): one INSERT in a new query window of the
# manual-commit SQLite connection, then Cmd+Q -> "Don't Save" for the query window -> the transaction question.
# Cancel must keep the app; the second Cmd+Q answered "Roll Back" must roll back and end the app normally.
cmd_txn() {
  local pid n before db=$SMOKE_DIR/smoke.sqlite
  need_app
  pid=$(app_pid)
  [ -n "$pid" ] || fail "the app does not run"
  before=$(log_lines)
  # The explorer tree has the focus (MainFrame.windowOpened); Down selects its first row, the connection
  # (Home is not bound to the tree's first row on macOS)
  on_app "$pid" 'key code 125' > /dev/null
  sleep 1
  # Cmd+N: new query window for the selected connection (MainFrame.newQueryAction, UiKeys.command: Command on macOS
  # since 0.1.4; the Ctrl+N this step sent before did nothing there, so Cmd+Q ended the app without a question)
  on_app "$pid" 'keystroke "n" using command down' > /dev/null
  sleep 4
  shot txn-1-new-query
  on_app "$pid" "keystroke \"insert into smoke(note) values ('during');\"" > /dev/null
  sleep 1
  # F5: execute (MainFrame.executeQueryAction); the connection opens on the first execute
  on_app "$pid" 'key code 96' > /dev/null
  sleep 10
  shot txn-2-executed
  [ "$(sqlite3 "$db" "select count(*) from smoke where note = 'during'")" = 0 ] || fail "txn: the INSERT was committed (auto-commit?)"
  # Cmd+Q: the query window is modified, so the save question comes first
  on_app "$pid" 'keystroke "q" using command down' > /dev/null
  sleep 3
  front_window_text "$pid" > "$OUT/txn-3-save-question.txt"
  shot txn-3-save-question
  press_button "$pid" "Don't Save" >> "$OUT/txn-3-save-question.txt" 2>&1 || fail "txn: cannot press Don't Save: $(tail -1 "$OUT/txn-3-save-question.txt")"
  sleep 3
  front_window_text "$pid" > "$OUT/txn-4-transaction-question.txt"
  shot txn-4-transaction-question
  grep -q 'Uncommitted Transactions' "$OUT/txn-4-transaction-question.txt" \
    || fail "txn: Cmd+Q did not ask about the open transaction (txn-4-transaction-question.txt: $(head -c 300 "$OUT/txn-4-transaction-question.txt"))"
  pass "txn: Cmd+Q with an open transaction asks Commit / Roll Back / Cancel (txn-4-transaction-question.png)"
  press_button "$pid" Cancel >> "$OUT/txn-4-transaction-question.txt" 2>&1 || fail "txn: cannot press Cancel"
  sleep 4
  kill -0 "$pid" 2> /dev/null || fail "txn: the app ended after Cancel"
  n=$(window_count pid "$pid")
  shot txn-5-after-cancel
  [ "$(sqlite3 "$db" "select count(*) from smoke where note = 'during'")" = 0 ] || fail "txn: Cancel committed the INSERT"
  pass "txn: Cancel keeps the app and the transaction open ($n window(s))"
  on_app "$pid" 'keystroke "q" using command down' > /dev/null
  sleep 3
  press_button "$pid" "Don't Save" > "$OUT/txn-6-quit-again.txt" 2>&1 || fail "txn: cannot press Don't Save the second time"
  sleep 3
  front_window_text "$pid" >> "$OUT/txn-6-quit-again.txt"
  press_button "$pid" "Roll Back" >> "$OUT/txn-6-quit-again.txt" 2>&1 || fail "txn: cannot press Roll Back"
  n=$((SECONDS + QUIT_TIMEOUT))
  while kill -0 "$pid" 2> /dev/null && [ $SECONDS -lt $n ]; do sleep 1; done
  tail -n +$((before + 1)) "$DEBUG_LOG" > "$OUT/txn-debug-log.txt" 2> /dev/null || true
  if kill -0 "$pid" 2> /dev/null; then
    shot txn-7-still-running
    fail "txn: the app still runs after Roll Back"
  fi
  grep -q 'Main window closed' "$OUT/txn-debug-log.txt" || fail "txn: no 'Main window closed' after Roll Back"
  [ "$(sqlite3 "$db" "select count(*) from smoke where note = 'during'")" = 0 ] || fail "txn: the INSERT is in the database after Roll Back"
  [ "$(sqlite3 "$db" "select count(*) from smoke")" = 1 ] || fail "txn: the test table changed"
  pass "txn: Roll Back rolled the INSERT back and the app ended through the main window's closing path"
}

cmd_stop() {
  local pid
  [ -n "${APP:-}" ] && [ -d "$APP" ] || return 0
  pid=$(app_pid)
  [ -n "$pid" ] || return 0
  shot "left-running-$pid"
  note "an app copy this test started still ran (pid $pid); stopped"
  kill "$pid" 2> /dev/null || true
  sleep 5
  kill -9 "$pid" 2> /dev/null || true
}

cmd_collect() {
  cmd_stop
  if [ -n "${APP:-}" ] && [ -d "$APP" ]; then
    cp "$APP/Contents/Info.plist" "$OUT/Info.plist" 2> /dev/null || true
    find "$APP/Contents/Eclipse" -maxdepth 1 -name '*.ini' -exec cp {} "$OUT/" \; 2> /dev/null || true
  fi
  cp "$DEBUG_LOG" "$OUT/chromite-debug.log" 2> /dev/null || true
  cp "$WS/.metadata/.log" "$OUT/equinox.log" 2> /dev/null || true
  mkdir -p "$OUT/crash-reports"
  find "$HOME/Library/Logs/DiagnosticReports" -iname '*chromite*' -exec cp {} "$OUT/crash-reports/" \; 2> /dev/null || true
  # Gatekeeper's and the Keychain's view of the run (compact, last 30 minutes)
  log show --last 30m --style compact \
    --predicate '(process == "syspolicyd" AND subsystem BEGINSWITH "com.apple.syspolicy") OR process == "CoreServicesUIAgent" OR (process == "securityd" AND eventMessage CONTAINS[c] "chromite")' \
    2> /dev/null | tail -n 3000 > "$OUT/unified-log.txt" || true
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ] && [ -f "$OUT/result.txt" ]; then
    {
      echo "### macOS smoke test"
      echo '```'
      cat "$OUT/result.txt"
      echo '```'
    } >> "$GITHUB_STEP_SUMMARY"
  fi
}

case "${1:-}" in
  install) cmd_install "${2:-}" ;;
  unpack) cmd_unpack "${2:-}" ;;
  verify) cmd_verify "${2:-}" ;;
  workspace) cmd_workspace ;;
  launch) cmd_launch "${2:-}" ;;
  secure-storage) cmd_secure_storage ;;
  menus) cmd_menus ;;
  titlebar) cmd_titlebar ;;
  quit) cmd_quit "${2:-}" ;;
  txn) cmd_txn ;;
  stop) cmd_stop ;;
  collect) cmd_collect ;;
  *)
    sed -n '2,30p' "$0" >&2
    exit 2
    ;;
esac
