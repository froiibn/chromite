/*
 * Chromite - Database Workbench
 * Copyright (C) 2026 Chromite contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.seizia.chromite.model.update;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Starts the helper that applies a checked update AFTER Chromite has ended, and tells whether one is running.
 *
 * 설계: 실행 중인 앱은 자기 파일을 바꿀 수 없으므로(Windows는 쓰는 중인 파일을 바꾸지 못한다) 작은 도우미 프로세스를
 * 띄우고 앱은 끝난다. 도우미는 앱 프로세스가 끝나기를 기다린 뒤에만 바꾼다(Turbrix {@code updateRun}·{@code macUpdate}와 같음).
 * <ul>
 *     <li>Windows: PowerShell 5.1에 스크립트를 {@code -EncodedCommand}로 넘긴다(스크립트 파일이 없어 실행 정책과 상관없음).
 *     경로와 값은 환경 변수로만 넘긴다. 도우미는 앱이 끝나기를 기다리고({@value #WAIT_SECONDS}초까지), 설치 파일의 SHA-256을
 *     한 번 더 보고(앱이 확인한 뒤 바뀌지 않았는지), {@code setup.exe /S}(조용한 설치, 같은 폴더에 덮어씀)를 실행하고
 *     끝나기를 기다린다. 설치 프로그램은 앱을 띄우지 않는다(종료 코드 0 완료, 1 취소, 2 실패, 실패하면 아무것도 바꾸지 않음,
 *     docs/chromite/distribution.md). 다시 시작을 골랐으면 설치 결과와 상관없이 런처를 다시 띄운다(실패면 이전 버전이 뜬다).
 *     다시 띄울 때 작업 폴더는 설치 폴더다(시작 메뉴 바로 가기와 같게. 2026-11-07 현재 작업 공간 위치가 작업 폴더를 따르는 문제가 있어
 *     다른 작업에서 고치는 중이고, 고친 뒤에도 해가 없다). 도우미 자신의 작업 폴더는 업데이트 폴더다(설치 폴더를 붙잡지 않게).</li>
 *     <li>결과({@value #RESULT_FILE}): 도우미가 설치한 버전과 종료 코드(또는 음수의 도우미 결과)를 적고, 다음 시작이 한 번 읽고 지운다
 *     ({@link #takeOutcome}). 설치하지 못했으면 화면이 알린다.</li>
 *     <li>macOS: 셸 스크립트({@value #MAC_HELPER})를 업데이트 폴더에 쓰고 {@code /bin/sh}로 띄운다. 경로는 스크립트 글이 아니라
 *     인자로 넘긴다. 앱이 끝나면 지금 앱 묶음을 {@code <updates>/before-<새 버전>/}로 옮기고(되돌리기용), 확인한 새 앱 묶음을 같은
 *     경로로 옮긴다. 새 앱을 옮기지 못하면 옛 앱을 제자리로 돌린다. 다른 것은 지우지 않는다.</li>
 *     <li>잠금 파일({@value #LOCK_FILE}): 도우미의 PID를 적어 두고, 도우미가 다시 띄우기 직전에 지운다. 그사이 사용자가 Chromite를
 *     다시 열면 {@link #isRunning}이 참이라 시작하지 않는다(설치 중인 파일을 쓰지 않게). 오래된 잠금({@value #LOCK_MINUTES}분)이나
 *     끝난 PID의 잠금은 버린다(PID 재사용으로 영영 열리지 않는 일이 없게).</li>
 * </ul>
 * 도우미의 기록은 {@code <updates>/apply-update.log}다.
 * <p>
 * Windows의 [지금 다시 시작]은 이 도우미 대신 앱의 사본({@link UpdateHandOver})이 설치 프로그램을 실행하고 진행 창을 보인다.
 * 이 클래스는 그 사본도 같은 잠금으로 띄운다({@link #startWindowCopy}). 이 도우미는 그냥 닫을 때, 사본을 만들지 못했을 때,
 * macOS에서 쓴다. 이 도우미의 결과는 그대로 {@value #RESULT_FILE}에 남고(셸 스크립트가 JSON 기록을 고치지 않게), 사본의 결과는
 * 업데이트 기록({@link UpdateRun})에 남는다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/auto-update.md "적용")
 * - 2026-11-08 앱의 사본을 도우미와 같은 잠금으로 띄움({@link #startWindowCopy}), 결과를 지우지 않고 읽기({@link #peekOutcome}, 업데이트 뒤
 *   첫 시작의 판단) (docs/chromite/auto-update.md "업데이트 진행 창")
 */
public final class UpdateApplier {

    static final String LOCK_FILE = "apply.lock"; //$NON-NLS-1$
    static final String LOG_FILE = "apply-update.log"; //$NON-NLS-1$
    static final String MAC_HELPER = "apply-update.sh"; //$NON-NLS-1$
    static final int WAIT_SECONDS = 300;
    static final int LOCK_MINUTES = 15;

    private static final String V_PID = "CHROMITE_UPDATE_PID"; //$NON-NLS-1$
    private static final String V_INSTALLER = "CHROMITE_UPDATE_INSTALLER"; //$NON-NLS-1$
    private static final String V_SHA256 = "CHROMITE_UPDATE_SHA256"; //$NON-NLS-1$
    private static final String V_LAUNCHER = "CHROMITE_UPDATE_LAUNCHER"; //$NON-NLS-1$
    private static final String V_LOG = "CHROMITE_UPDATE_LOG"; //$NON-NLS-1$
    private static final String V_LOCK = "CHROMITE_UPDATE_LOCK"; //$NON-NLS-1$
    private static final String V_DRY_RUN = "CHROMITE_UPDATE_DRYRUN"; //$NON-NLS-1$
    private static final String V_VERSION = "CHROMITE_UPDATE_VERSION"; //$NON-NLS-1$
    private static final String V_RESULT = "CHROMITE_UPDATE_RESULT"; //$NON-NLS-1$

    static final String RESULT_FILE = "apply-result.json"; //$NON-NLS-1$
    /** The helper failed before or while running the installer. */
    static final int RESULT_HELPER_FAILED = -1;
    /** The installer changed after the app's check: not run. */
    static final int RESULT_CHANGED = -2;
    /** The app did not end in time: nothing done. */
    static final int RESULT_NOT_ENDED = -3;
    /** macOS: the new bundle was missing, or the bundles could not be moved (the old app stays). */
    static final int RESULT_NOT_REPLACED = -4;

    /**
     * The Windows helper. {@code $launcher} is started again only when the app ended (else the new start would find
     * the workspace still locked).
     */
    static final String WINDOWS_HELPER = String.join("\n", //$NON-NLS-1$
        "$ErrorActionPreference = 'Stop'", //$NON-NLS-1$
        "$ProgressPreference = 'SilentlyContinue'", //$NON-NLS-1$
        // (the variables are read once: they are removed before Chromite is started again)
        "$log = $env:" + V_LOG, //$NON-NLS-1$
        "function Say([string]$m) { try { Add-Content -LiteralPath $log" //$NON-NLS-1$
            + " -Value ((Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ') + ' ' + $m) -Encoding UTF8 } catch { } }", //$NON-NLS-1$
        "$launcher = $env:" + V_LAUNCHER, //$NON-NLS-1$
        "$installer = $env:" + V_INSTALLER, //$NON-NLS-1$
        "$version = $env:" + V_VERSION, //$NON-NLS-1$
        "$result = $env:" + V_RESULT, //$NON-NLS-1$
        "$ended = $false", //$NON-NLS-1$
        // (the outcome for the next start: the setup's exit code, or one of the RESULT_* values)
        "$code = " + RESULT_HELPER_FAILED, //$NON-NLS-1$
        "try {", //$NON-NLS-1$
        "  $p = Get-Process -Id ([int]$env:" + V_PID + ") -ErrorAction SilentlyContinue", //$NON-NLS-1$ //$NON-NLS-2$
        "  if ($p -and -not $p.WaitForExit(" + WAIT_SECONDS * 1000 + ")) { $code = " + RESULT_NOT_ENDED //$NON-NLS-1$ //$NON-NLS-2$
            + "; Say 'the app did not end: nothing changed' }", //$NON-NLS-1$
        "  else {", //$NON-NLS-1$
        "    $ended = $true", //$NON-NLS-1$
        "    Say 'the app ended'", //$NON-NLS-1$
        "    if ($installer) {", //$NON-NLS-1$
        "      $h = (Get-FileHash -LiteralPath $installer -Algorithm SHA256).Hash.ToLowerInvariant()", //$NON-NLS-1$
        "      if ($h -ne $env:" + V_SHA256 + ") { $code = " + RESULT_CHANGED //$NON-NLS-1$ //$NON-NLS-2$
            + "; Say 'the installer changed after its check: nothing changed' }", //$NON-NLS-1$
        "      elseif ($env:" + V_DRY_RUN + " -eq '1') { $code = 0; Say ('test mode: not run: ' + $installer) }", //$NON-NLS-1$ //$NON-NLS-2$
        "      else {", //$NON-NLS-1$
        "        Say ('running ' + $installer + ' /S')", //$NON-NLS-1$
        "        $r = Start-Process -FilePath $installer -ArgumentList '/S' -Wait -PassThru", //$NON-NLS-1$
        "        $code = [int]$r.ExitCode", //$NON-NLS-1$
        "        Say ('installer exit code ' + $code)", //$NON-NLS-1$
        "      }", //$NON-NLS-1$
        "    }", //$NON-NLS-1$
        "  }", //$NON-NLS-1$
        "} catch { Say ('failed: ' + $_.Exception.Message) }", //$NON-NLS-1$
        "finally {", //$NON-NLS-1$
        "  if ($installer) { try { [pscustomobject]@{ version = $version; code = [int]$code } | ConvertTo-Json -Compress" //$NON-NLS-1$
            + " | Set-Content -LiteralPath $result -Encoding ASCII } catch { Say 'no result written' } }", //$NON-NLS-1$
        "  Remove-Item -LiteralPath $env:" + V_LOCK + " -Force -ErrorAction SilentlyContinue", //$NON-NLS-1$
        "}", //$NON-NLS-1$
        "if ($ended -and $launcher) {", //$NON-NLS-1$
        "  foreach ($n in '" + V_PID + "','" + V_INSTALLER + "','" + V_SHA256 + "','" + V_LAUNCHER + "','" + V_LOG + "','" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            + V_LOCK + "','" + V_DRY_RUN + "','" + V_VERSION + "','" + V_RESULT //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            + "') { Remove-Item -LiteralPath ('Env:' + $n) -ErrorAction SilentlyContinue }", //$NON-NLS-1$
        // (the installation folder as the working directory, as the Start menu shortcut does)
        "  try { Start-Process -FilePath $launcher -WorkingDirectory (Split-Path -LiteralPath $launcher); Say 'started again' }" //$NON-NLS-1$
            + " catch { Say ('not started again: ' + $_.Exception.Message) }", //$NON-NLS-1$
        "}"); //$NON-NLS-1$

    /**
     * The macOS helper. Arguments: $1 the app's process id, $2 the app bundle, $3 the new bundle (empty: restart
     * only), $4 where the bundle goes aside, $5 1 = start it again, $6 the log, $7 the lock file, $8 the new
     * version, $9 the result file (code 0 replaced, -3 the app did not end, -4 not replaced).
     */
    static final String MAC_HELPER_SCRIPT = """
        #!/bin/sh
        # Chromite: replaces the app once it has ended (written by the app: UpdateApplier)
        pid=$1 app=$2 next=$3 aside=$4 restart=$5 log=$6 lock=$7 version=$8 result=$9
        say() { printf '%s %s\\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >> "$log"; }
        outcome() { [ -n "$next" ] && printf '{"version":"%s","code":%s}\\n' "$version" "$1" > "$result"; }
        n=0
        while kill -0 "$pid" 2>/dev/null; do
          n=$((n + 1))
          if [ "$n" -gt 1500 ]; then say "the app did not end: nothing changed"; outcome -3; rm -f "$lock"; exit 1; fi
          sleep 0.2
        done
        say "the app ended"
        if [ -n "$next" ]; then
          if [ ! -d "$next" ]; then
            say "no new app: nothing changed"; outcome -4
          elif ! mkdir -p "$(dirname "$aside")" || ! rm -rf "$aside"; then
            say "no place aside: nothing changed"; outcome -4
          elif ! mv "$app" "$aside"; then
            say "the app could not be moved aside: nothing changed"; outcome -4
          elif ! mv "$next" "$app"; then
            mv "$aside" "$app"
            say "the new app could not be moved in: the app is back"; outcome -4
          else
            say "replaced"; outcome 0
          fi
        fi
        rm -f "$lock"
        if [ "$restart" = 1 ]; then
          /usr/bin/open "$app" && say "started again"
        fi
        exit 0
        """; //$NON-NLS-1$

    private UpdateApplier() {
    }

    /**
     * Starts the Windows helper, which runs {@code installer} (null: restart only) once this process has ended.
     *
     * @param launcher the program to start again, null for none
     */
    static void startWindowsHelper(
        @NotNull Path folder,
        @Nullable Path installer,
        @Nullable String sha256,
        @Nullable String version,
        @Nullable Path launcher,
        boolean dryRun
    ) throws IOException {
        Files.createDirectories(folder);
        Files.deleteIfExists(folder.resolve(RESULT_FILE));
        Path lock = folder.resolve(LOCK_FILE);
        Map<String, String> env = new LinkedHashMap<>();
        env.put(V_VERSION, version != null && UpdateVersion.isValid(version) ? version : ""); //$NON-NLS-1$
        env.put(V_RESULT, folder.resolve(RESULT_FILE).toString());
        env.put(V_PID, Long.toString(ProcessHandle.current().pid()));
        env.put(V_INSTALLER, installer != null ? installer.toAbsolutePath().toString() : ""); //$NON-NLS-1$
        env.put(V_SHA256, sha256 != null ? sha256 : ""); //$NON-NLS-1$
        env.put(V_LAUNCHER, launcher != null ? launcher.toString() : ""); //$NON-NLS-1$
        env.put(V_LOG, folder.resolve(LOG_FILE).toString());
        env.put(V_LOCK, lock.toString());
        env.put(V_DRY_RUN, dryRun ? "1" : "0"); //$NON-NLS-1$ //$NON-NLS-2$
        start(List.of(WindowsUpdateVerifier.powerShell(), "-NoLogo", "-NoProfile", "-NonInteractive", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "-WindowStyle", "Hidden", "-EncodedCommand", UpdateProcess.encodePowerShell(WINDOWS_HELPER)), env, lock); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Starts the macOS helper, which moves {@code next} (null: restart only) into {@code app}'s place once this
     * process has ended.
     */
    static void startMacHelper(
        @NotNull Path folder,
        @NotNull Path app,
        @Nullable Path next,
        @NotNull Path aside,
        @Nullable String version,
        boolean restart
    ) throws IOException {
        Files.createDirectories(folder);
        Files.deleteIfExists(folder.resolve(RESULT_FILE));
        Path script = folder.resolve(MAC_HELPER);
        Files.writeString(script, MAC_HELPER_SCRIPT, StandardCharsets.UTF_8);
        Path lock = folder.resolve(LOCK_FILE);
        start(List.of("/bin/sh", script.toString(), Long.toString(ProcessHandle.current().pid()), app.toString(), //$NON-NLS-1$
            next != null ? next.toString() : "", aside.toString(), restart ? "1" : "0", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            folder.resolve(LOG_FILE).toString(), lock.toString(),
            version != null && UpdateVersion.isValid(version) ? version : "", folder.resolve(RESULT_FILE).toString()), //$NON-NLS-1$
            Map.of(), lock);
    }

    /**
     * Starts the copy of the app that shows the update window and runs the installer ({@link UpdateHandOver}), with the
     * helper's lock: a start of Chromite waits while it installs. The copy removes the lock before it starts Chromite.
     */
    public static void startWindowCopy(@NotNull Path folder, @NotNull List<String> command) throws IOException {
        Files.createDirectories(folder);
        start(command, Map.of(), folder.resolve(LOCK_FILE));
    }

    /**
     * What the helper of the last update said: the version it installed and the installer's exit code (0: done), or
     * null when no update was applied since. The file is removed: each outcome is told once.
     */
    @Nullable
    static Outcome takeOutcome(@NotNull Path folder) {
        Outcome outcome = peekOutcome(folder);
        try {
            Files.deleteIfExists(folder.resolve(RESULT_FILE));
        } catch (IOException ignored) {
            // (told again at the next start)
        }
        return outcome;
    }

    /**
     * The helper's result as {@link #takeOutcome} reads it, the file left in place (the first start after an update
     * reads it before the updater tells it).
     */
    @Nullable
    public static Outcome peekOutcome(@NotNull Path folder) {
        Path file = folder.resolve(RESULT_FILE);
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            String text = Files.readString(file, StandardCharsets.US_ASCII).trim();
            long at = Files.getLastModifiedTime(file).toMillis();
            com.google.gson.JsonElement root = com.google.gson.JsonParser.parseString(text);
            if (root instanceof com.google.gson.JsonObject o
                && o.get("version") instanceof com.google.gson.JsonPrimitive v && v.isString() //$NON-NLS-1$
                && UpdateVersion.isValid(v.getAsString())
                && o.get("code") instanceof com.google.gson.JsonPrimitive c && c.isNumber()) { //$NON-NLS-1$
                return new Outcome(v.getAsString(), c.getAsInt(), at);
            }
        } catch (IOException | RuntimeException ignored) {
            // (no outcome known)
        }
        return null;
    }

    /**
     * The outcome of one applied update.
     *
     * @param code the setup's exit code (0 done, 1 cancelled, 2 failed), or a negative RESULT_* value of the helper
     * @param at   when the helper wrote it (ms since the epoch)
     */
    public record Outcome(@NotNull String version, int code, long at) {
    }

    private static void start(@NotNull List<String> command, @NotNull Map<String, String> env, @NotNull Path lock) throws IOException {
        ProcessBuilder builder = UpdateProcess.builder(command, env);
        // Not the installation folder: the installer replaces it
        builder.directory(lock.getParent().toFile());
        builder.redirectInput(ProcessBuilder.Redirect.from(UpdateProcess.nullDevice()));
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process helper = builder.start();
        Files.writeString(lock, Long.toString(helper.pid()), StandardCharsets.US_ASCII);
    }

    /**
     * Whether a helper is applying an update now (a new start must wait until it is done).
     */
    public static boolean isRunning(@NotNull Path folder) {
        Path lock = folder.resolve(LOCK_FILE);
        try {
            if (!Files.isRegularFile(lock)) {
                return false;
            }
            FileTime written = Files.getLastModifiedTime(lock);
            long pid = Long.parseLong(Files.readString(lock, StandardCharsets.US_ASCII).trim());
            boolean fresh = Duration.between(written.toInstant(), Instant.now()).toMinutes() < LOCK_MINUTES;
            if (fresh && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                return true;
            }
            Files.deleteIfExists(lock);
        } catch (IOException | RuntimeException ignored) {
            // (no helper known)
        }
        return false;
    }
}
