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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * What the copy of the app does while the installer runs (Windows): waits for the old app to end, checks the installer
 * is the file the old app checked, runs it as its own child, and starts the installed Chromite.
 *
 * 설계: Turbrix #747 {@code electron/updateHelper.cjs}의 A안(docs/chromite/auto-update.md "업데이트 진행 창").
 * [지금 다시 시작] 때 옛 앱이 자기 Java 런타임과 진행 창만 띄우는 작은 진입점을 설치 폴더 밖({@code <updates>/window-app/})에
 * 복사하고 그 사본을 띄운다. 사본은 설치 폴더의 파일을 하나도 쓰지 않는다. 그래서
 * <ul>
 *     <li>설치 프로그램({@code tools/installer/chromite.nsi})을 막지 않는다: 설치 프로그램은 {@code $INSTDIR\jre\bin\server\jvm.dll}을
 *     쓰기로 열 수 있을 때까지(조용한 설치는 60초) 기다리고, 그 뒤 옛 파일을 지운다. 사본은 자기 {@code jvm.dll}을 쓰고,
 *     작업 폴더도 업데이트 폴더다.</li>
 *     <li>설치 프로그램이 사본을 끝내지 않는다: Chromite 설치 프로그램은 프로세스를 끝내지 않는다(Turbrix의 electron-builder
 *     설치 프로그램과 다름).</li>
 * </ul>
 * 설치 프로그램을 자식으로 실행하므로 종료 코드(0 완료, 1 취소, 2 실패)를 안다. 옛 앱이 끝나기를 기다리는 것은 옛 앱이 설치 폴더의
 * {@code jvm.dll}을 쥐고 있기 때문이다(그대로 두면 설치 프로그램이 60초 뒤 2로 끝남). Chromite를 다시 띄울 때 런처에 {@value #NO_SPLASH}를
 * 준다: 새 버전의 첫 시작은 진행 창이 시작 화면 몫을 하므로 런처의 그림이 그 위에 잠깐 뜨지 않게 한다.
 * 이 클래스는 사본에서 돌기 때문에 OSGi와 {@code Log}를 쓰지 않는다. 기록은 {@link UpdateRun#log}다.
 * <p>
 * 설치 범위(기록의 {@code allUsers}): 사용자별 설치는 {@code setup.exe /CurrentUser /S}를 자식으로 실행한다(관리자 확인 없음).
 * 모든 사용자 설치(Program Files)는 관리자 권한이 필요하다. 승격된 프로세스는 자식으로 만들 수 없으므로 PowerShell이 셸의 {@code runas}로
 * {@code /AllUsers /S}를 실행하고 종료 코드를 돌려준다({@link #runElevated}). 관리자 확인을 거부하면 설치 프로그램은 실행되지 않고
 * {@link #isElevationRefused}가 참인 코드가 되며, 창은 그 실패 문구와 [다시 시도]를 보인다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: 업데이트 진행 창 (docs/chromite/auto-update.md "업데이트 진행 창")
 * - 2026-11-09 설치 범위: 모든 사용자 설치는 관리자 권한으로 설치({@link #runElevated}), 사용자별은 {@code /CurrentUser}
 *   (docs/chromite/auto-update.md "관리자 권한으로 설치")
 */
public final class UpdateHandOver {

    /**
     * After an installer that ended well: the wait for the new version to take the window, and once more after
     * starting it again.
     */
    public static final long TAKE_MS = 60_000;
    static final String NO_SPLASH = "-nosplash"; //$NON-NLS-1$
    private static final String V_INSTALLER = "CHROMITE_UPDATE_INSTALLER"; //$NON-NLS-1$
    private static final String V_ARGUMENTS = "CHROMITE_UPDATE_ARGUMENTS"; //$NON-NLS-1$

    /**
     * Runs the installer elevated ({@link UpdateApplier#RUN_INSTALLER}) and exits with its exit code; with
     * {@value UpdateApplier#RESULT_HELPER_FAILED} when it could not be started (an uncaught error would exit with 1, the
     * installer's "cancelled").
     */
    static final String ELEVATED_RUN = String.join("\n", //$NON-NLS-1$
        "$ErrorActionPreference = 'Stop'", //$NON-NLS-1$
        "$installer = $env:" + V_INSTALLER, //$NON-NLS-1$
        "$arguments = $env:" + V_ARGUMENTS, //$NON-NLS-1$
        "$elevate = $true", //$NON-NLS-1$
        "try {", //$NON-NLS-1$
        UpdateApplier.RUN_INSTALLER,
        "} catch { [Console]::Error.WriteLine($_.Exception.Message); exit " + UpdateApplier.RESULT_HELPER_FAILED + " }", //$NON-NLS-1$ //$NON-NLS-2$
        "exit $code"); //$NON-NLS-1$

    private UpdateHandOver() {
    }

    /**
     * The installation: waits for the old app, runs the installer and records when and how it ended. The lock goes
     * afterwards (Chromite may start: the new version, or the version updated from after a failure).
     *
     * @return the installer's exit code (0 done), or a negative result of {@link UpdateApplier}
     */
    public static int install(@NotNull UpdateRun run, @NotNull UpdateRun.Record record) throws InterruptedException {
        int code;
        if (!waitForOldApp(record.pid)) {
            run.log("the app did not end: nothing changed"); //$NON-NLS-1$
            code = UpdateApplier.RESULT_NOT_ENDED;
        } else {
            long startAt = System.currentTimeMillis();
            run.patch(r -> r.installStartAt = startAt);
            code = runInstaller(run, record);
        }
        long endAt = System.currentTimeMillis();
        int result = code;
        record.installEndAt = endAt;
        record.installCode = result;
        run.patch(r -> {
            r.installEndAt = endAt;
            r.installCode = result;
        });
        releaseLock(run);
        return result;
    }

    /**
     * Waits until the old app has ended ({@value UpdateApplier#WAIT_SECONDS} s at most): it holds the installation's
     * Java runtime, which the installer waits for.
     *
     * @return false when it did not end in time
     */
    public static boolean waitForOldApp(@Nullable Long pid) throws InterruptedException {
        if (pid == null || pid == ProcessHandle.current().pid()) {
            return true;
        }
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isEmpty()) {
            return true;
        }
        try {
            handle.get().onExit().get(UpdateApplier.WAIT_SECONDS, TimeUnit.SECONDS);
            return true;
        } catch (ExecutionException e) {
            return !handle.get().isAlive();
        } catch (TimeoutException e) {
            return false;
        }
    }

    /**
     * Whether the installer is still the file the old app checked (its SHA-256).
     */
    public static boolean isUnchanged(@Nullable UpdateRun.Installer installer) {
        if (installer == null || installer.file == null || installer.sha256 == null) {
            return false;
        }
        try {
            return UpdateVerifier.sha256Of(Path.of(installer.file)).equals(installer.sha256);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Runs the installer silently as a child of this process and returns its exit code; {@link UpdateApplier}'s
     * negative results when it did not run. An installation for all users runs it elevated ({@link #runElevated}).
     * The test mode does not run it: it reports the record's test code.
     */
    public static int runInstaller(@NotNull UpdateRun run, @NotNull UpdateRun.Record record) throws InterruptedException {
        UpdateRun.Installer installer = record.installer;
        if (!isUnchanged(installer)) {
            run.log("the installer changed after its check: nothing changed"); //$NON-NLS-1$
            return UpdateApplier.RESULT_CHANGED;
        }
        boolean elevate = Boolean.TRUE.equals(record.allUsers);
        String scope = elevate ? UpdateApplier.SWITCH_ALL_USERS : UpdateApplier.SWITCH_CURRENT_USER;
        String how = installer.file + " " + scope + " " + UpdateApplier.SWITCH_SILENT + (elevate ? " (elevated)" : ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        if (Boolean.TRUE.equals(record.dryRun)) {
            int code = record.testCode != null ? record.testCode : 0;
            run.log("test mode: not run: " + how + " (code " + code + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return code;
        }
        run.log("running " + how); //$NON-NLS-1$
        if (elevate) {
            return runElevated(run, installer.file, scope + " " + UpdateApplier.SWITCH_SILENT); //$NON-NLS-1$
        }
        ProcessBuilder builder = UpdateProcess.builder(List.of(installer.file, scope, UpdateApplier.SWITCH_SILENT), Map.of());
        // Not the installation folder: the installer replaces it
        builder.directory(run.getFolder().toFile());
        builder.redirectInput(ProcessBuilder.Redirect.from(UpdateProcess.nullDevice()));
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        try {
            int code = builder.start().waitFor();
            run.log("installer exit code " + code); //$NON-NLS-1$
            return code;
        } catch (IOException e) {
            run.log("the installer did not start: " + e.getMessage()); //$NON-NLS-1$
            return UpdateApplier.RESULT_HELPER_FAILED;
        }
    }

    /**
     * Runs the installer elevated (UAC) through Windows PowerShell and the shell's {@code runas} verb, and waits for it: a
     * process cannot be started elevated as a child of this one, but the shell hands back its exit code. What PowerShell
     * says goes to the log.
     *
     * @return the installer's exit code, {@value UpdateApplier#RESULT_ELEVATION_REFUSED} when the administrator prompt
     * was refused, {@value UpdateApplier#RESULT_HELPER_FAILED} when it did not start
     */
    static int runElevated(@NotNull UpdateRun run, @NotNull String installer, @NotNull String arguments) throws InterruptedException {
        ProcessBuilder builder = UpdateProcess.builder(List.of(WindowsUpdateVerifier.powerShell(), "-NoLogo", "-NoProfile", //$NON-NLS-1$ //$NON-NLS-2$
            "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", UpdateProcess.encodePowerShell(ELEVATED_RUN)), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            Map.of(V_INSTALLER, installer, V_ARGUMENTS, arguments));
        // Not the installation folder: the installer replaces it
        builder.directory(run.getFolder().toFile());
        builder.redirectInput(ProcessBuilder.Redirect.from(UpdateProcess.nullDevice()));
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(run.getFolder().resolve(UpdateApplier.LOG_FILE).toFile()));
        try {
            int code = builder.start().waitFor();
            run.log(code == UpdateApplier.RESULT_ELEVATION_REFUSED
                ? "the administrator prompt was refused: nothing changed" //$NON-NLS-1$
                : "installer exit code " + code); //$NON-NLS-1$
            return code;
        } catch (IOException e) {
            run.log("the installer did not start: " + e.getMessage()); //$NON-NLS-1$
            return UpdateApplier.RESULT_HELPER_FAILED;
        }
    }

    /**
     * Whether an installation failed because the administrator prompt (UAC) was refused: the window says so.
     */
    public static boolean isElevationRefused(int code) {
        return code == UpdateApplier.RESULT_ELEVATION_REFUSED;
    }

    /**
     * Starts the installed Chromite ({@code -nosplash}: the update window is its splash), in the installation folder as
     * the Start menu shortcut does.
     */
    public static boolean startApp(@NotNull UpdateRun run, @NotNull UpdateRun.Record record) {
        if (record.launcher == null || !Files.isRegularFile(Path.of(record.launcher))) {
            run.log("not started again: no launcher"); //$NON-NLS-1$
            return false;
        }
        try {
            ProcessBuilder builder = UpdateProcess.builder(List.of(record.launcher, NO_SPLASH), Map.of());
            builder.directory(Path.of(record.launcher).getParent().toFile());
            builder.redirectInput(ProcessBuilder.Redirect.from(UpdateProcess.nullDevice()));
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.start();
            run.log("started again"); //$NON-NLS-1$
            return true;
        } catch (IOException | RuntimeException e) {
            run.log("not started again: " + e.getMessage()); //$NON-NLS-1$
            return false;
        }
    }

    /**
     * Whether a failed installation left the version updated from in place: the one case its sentence ("The version
     * in use is {0}.") is said.
     */
    public static boolean isVersionKept(@NotNull UpdateRun.Record record) {
        return record.installDir != null && record.from != null
            && record.from.equals(UpdateRun.installedVersion(Path.of(record.installDir)));
    }

    /**
     * Removes the lock the old app wrote for this copy: Chromite may start again (the installer has ended).
     */
    public static void releaseLock(@NotNull UpdateRun run) {
        try {
            Files.deleteIfExists(run.getFolder().resolve(UpdateApplier.LOCK_FILE));
        } catch (IOException ignored) {
            // (a lock of an ended process is dropped by the next start)
        }
    }
}
