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

import com.seizia.chromite.Log;
import com.seizia.chromite.utils.GeneralUtils;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

/**
 * The in-app updater: checks the update site, receives and checks a newer release in the background, and applies it
 * when Chromite ends.
 *
 * 설계: Turbrix 데스크톱 {@code updater.cjs}의 규칙을 옮겼다(docs/chromite/auto-update.md).
 * <ul>
 *     <li>언제: 시작하고 {@value #FIRST_CHECK_MS}ms 뒤 한 번, 그 뒤 켜져 있는 동안 한 시간마다. 자동 확인은 끌 수 있고 기본은 켬이다.
 *     [지금 확인]은 자동 확인이 꺼져 있어도 돈다.</li>
 *     <li>무엇을: {@link UpdateFeed}가 고른, 지금보다 엄격히 높은 버전. 이미 받아 확인한 버전이면 그 차례를 끝낸다.
 *     앞선 시작에 받아 둔 파일은 다시 받지 않고 확인만 한다.</li>
 *     <li>실패: 오프라인·시간 초과·사이트 오류·형식 오류는 조용히 넘어가고 다음 차례에 다시 본다(폐쇄망에서 아무것도 보이지 않음).
 *     받은 파일이 확인을 통과하지 못하면 지우고 그 버전을 실패로 적는다. 그 버전은 스스로는 하루에 한 번까지만 다시 받는다
 *     ([다시 시도]는 바로 받는다). 실패 기록은 {@code update.json}에 남아 다시 시작해도 하루가 줄지 않는다.
 *     [닫기]는 그 버전의 실패 알림을 숨긴다(하루 한 번 재시도는 계속, 통과하면 기록이 지워짐, 다른 버전이 실패하면 다시 보임).</li>
 *     <li>받아서 확인한 버전은 더 높은 버전이 확인을 마쳐야 바뀐다(더 높은 버전이 실패해도 받아 둔 것은 계속 보이고 끝날 때 적용됨).</li>
 *     <li>되돌리기용으로 지금 버전의 설치 파일도 한 번 받아 같은 확인을 거친다(Windows, {@code previous/<버전>/}).
 *     업데이트 뒤 새 버전이 시작하지 못하면 업데이트 진행 창의 [이전 버전으로 되돌리기]가 이 파일을 설치한다(기록의 {@code previous}).</li>
 *     <li>적용: 끝날 때({@link #applyAtExit}) 확인한 파일을 한 번 더 확인한 뒤 도우미({@link UpdateApplier})를 띄운다.
 *     사용자의 정상 종료 경로(저장 안 한 편집기·트랜잭션 질문)를 거친 뒤에만 불린다. 자동 확인이 꺼져 있거나 Windows 모든 사용자 설치이면
 *     [다시 시작]을 눌렀을 때만 적용한다(모든 사용자 설치는 관리자 확인 창이 뜨므로 진행 창과 함께만). 지난번 끝날 때 설치하지 못한 버전({@link #getInstallFailure}, 도우미가 남긴 결과)은
 *     다음 시작에 한 번 알리고, 그냥 닫을 때는 다시 적용하지 않는다([다시 시작]은 다시 시도한다, 끝날 때마다 실패를 되풀이하지 않게).</li>
 * </ul>
 * 모든 확인·받기는 데몬 스레드 하나({@value #THREAD_NAME})에서 차례로 돈다(겹치지 않아 잠금이 거의 필요 없다).
 * 화면은 {@link Listener}로 상태를 받는다(이 스레드에서 불리므로 UI 스레드로 넘겨야 한다).
 * Eclipse Job 대신 실행기를 쓴 이유: 진행 표시 UI가 없는 주기 작업이고, OSGi 없이 같은 클래스를 시험할 수 있다.
 *
 * <p>
 * 업데이트 진행 창(Turbrix #747, docs/chromite/auto-update.md "업데이트 진행 창"): 적용할 때마다 업데이트 기록({@link UpdateRun})을 쓴다.
 * [지금 다시 시작]이면 화면({@link ExitWindow})이 다시 확인하는 동안 창을 보이고, Windows에서는 화면이 만든 앱의 사본이 설치 프로그램을
 * 실행한다(사본을 만들지 못하면 지금까지처럼 도우미). 받는 동안의 시각·받은 양({@link #getDetail})은 창이 보인다.
 * 지난번 설치 결과는 도우미의 결과 파일이나 기록의 종료 코드에서 읽는다. 사본의 창이 실패를 이미 보였으면 알림을 되풀이하지 않는다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/auto-update.md)
 * - 2026-11-08 업데이트 진행 창: 업데이트 기록, [지금 다시 시작]의 창과 앱의 사본({@link ExitWindow}), 받기 시각({@link #getDetail}),
 *   업데이트 뒤 첫 시작의 판단({@link #detectFirstStart}) (docs/chromite/auto-update.md "업데이트 진행 창")
 * - 2026-11-09 Windows 모든 사용자 설치({@link #isAllUsers}): [지금 다시 시작]에서만 관리자 권한으로 설치하고 그냥 닫을 때는 적용하지 않음.
 *   macOS: 새 앱 묶음을 zip 안의 이름으로 놓음 (docs/chromite/auto-update.md "관리자 권한으로 설치", "적용")
 */
public final class UpdateService {

    private static final Log log = Log.getLog(UpdateService.class);

    /**
     * The one update site (never another host, never dbeaver.io).
     */
    public static final String ORIGIN_TEXT = "https://chromite.seizia.com"; //$NON-NLS-1$
    public static final URI ORIGIN = URI.create(ORIGIN_TEXT);
    public static final long FIRST_CHECK_MS = 10_000;
    public static final long EVERY_MS = 60 * 60 * 1000L;
    public static final long RETRY_FAILED_MS = 24 * 60 * 60 * 1000L;
    private static final long EXIT_CHECK_SECONDS = 120;
    private static final String THREAD_NAME = "Chromite updater"; //$NON-NLS-1$
    private static final String PREVIOUS_FOLDER = "previous"; //$NON-NLS-1$
    private static final String ASIDE_PREFIX = "before-"; //$NON-NLS-1$

    public enum Phase {
        /** Nothing to show. */
        IDLE,
        /** Receiving {@link State#version()}, {@link State#progress()} percent. */
        DOWNLOADING,
        /** {@link State#version()} is received and checked; it is applied when Chromite ends. */
        READY,
        /** {@link State#version()} is out, but this copy is not updated in place ({@link UpdatePlace.Mode#NOTIFY}). */
        AVAILABLE,
        /** The file of {@link State#version()} did not pass {@link State#step()}. */
        FAILED
    }

    /**
     * What the last check found (Options shows it after [Check now]).
     */
    public enum CheckResult {
        NONE,
        UP_TO_DATE,
        UNREACHABLE,
        FOUND
    }

    /**
     * What a window shows. Nothing of it is sent anywhere.
     *
     * @param dismissed the user dismissed the failure of this version
     * @param checking  a check runs now
     */
    public record State(
        @NotNull UpdatePlace.Mode mode,
        boolean enabled,
        @NotNull Phase phase,
        @Nullable String version,
        @Nullable String notesUrl,
        @Nullable UpdateStep step,
        int progress,
        boolean dismissed,
        @NotNull CheckResult lastCheck,
        boolean checking
    ) {
    }

    public interface Listener {
        /**
         * Called on the updater's thread.
         */
        void stateChanged(@NotNull State state);
    }

    /**
     * The last applied update did not install: this start still runs the version before it.
     *
     * @param code the installer's exit code (1 cancelled, 2 failed), or a negative value of the helper
     *             (-1 the helper failed, -2 the installer changed after its check, -3 Chromite did not end, -4 macOS:
     *             the app was not replaced)
     */
    public record InstallFailure(@NotNull String version, int code) {
    }

    /**
     * What the update window shows of a download: when it began, how much is received of how much, and how long the
     * download of the version ready took (null when it was received at an earlier start).
     */
    public record Detail(@Nullable Long startedAt, long bytes, long size, @Nullable Long receivedMs) {
    }

    /**
     * The update window while Chromite ends (the UI's). Called on the exiting thread.
     */
    public interface ExitWindow {
        /**
         * Adds what the window knows to the record of an update applied now: language, theme, its place, the log folder.
         */
        void describe(@NotNull UpdateRun.Record record);

        /**
         * [Restart now]: the window shows while the file of {@code to} is checked again.
         */
        void checking(@NotNull String from, @NotNull String to, long restartAt, @Nullable Long downloadMs);

        /**
         * The file passed its check again.
         */
        void checked(long checkEndAt);

        /**
         * Windows: the command that starts the copy of this app that shows the window and runs the installer (made since
         * {@link #checking}), or null when there is none (the helper applies the update as before).
         */
        @Nullable
        List<String> copyCommand();

        /**
         * The copy runs: returns once its window is up (or after a while). The window goes.
         */
        void handedOver(@NotNull UpdateRun run);

        /**
         * The window goes: nothing is applied, or the helper applies it.
         */
        void close();
    }

    /**
     * A checked file to apply, and the bundle extracted from it (macOS).
     */
    private record Ready(@NotNull UpdateEntry entry, @NotNull Path file, @Nullable Path app) {
    }

    private static UpdateService instance;

    private final Callable<UpdatePlace> detector;
    private final Path folder;
    private final UpdateHttp http;
    private final Function<UpdatePlace, UpdateVerifier> verifiers;
    private final LongSupplier clock;
    private final ScheduledExecutorService executor;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final UpdateRun runs;

    // Read and written on the updater's thread only, except where volatile
    private volatile UpdatePlace place = UpdatePlace.off("not detected yet"); //$NON-NLS-1$
    private volatile State state;
    private volatile boolean cancelled;
    private volatile boolean stopped;
    @Nullable
    private volatile InstallFailure installFailure;
    /**
     * The version whose installation failed at the last end: not applied again by a plain close, only by [Restart now].
     */
    @Nullable
    private String failedInstallVersion;
    private UpdatePrefs prefs;
    private UpdateVerifier verifier;
    @Nullable
    private Ready ready;
    @Nullable
    private UpdateEntry previous;
    @Nullable
    private ScheduledFuture<?> timer;
    private volatile Detail detail = new Detail(null, 0, 0, null);

    /**
     * @param detector  where this copy runs ({@link UpdatePlace#detect}), called once on the updater's thread
     * @param folder    the updates folder
     * @param verifiers the checks for a place ({@link #defaultVerifier})
     * @param clock     milliseconds since the epoch
     */
    public UpdateService(
        @NotNull Callable<UpdatePlace> detector,
        @NotNull Path folder,
        @NotNull UpdateHttp http,
        @NotNull Function<UpdatePlace, UpdateVerifier> verifiers,
        @NotNull LongSupplier clock
    ) {
        this.detector = detector;
        this.folder = folder;
        this.http = http;
        this.verifiers = verifiers;
        this.clock = clock;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, THREAD_NAME);
            t.setDaemon(true);
            return t;
        });
        this.runs = new UpdateRun(folder);
        this.prefs = UpdatePrefs.read(folder);
        this.state = new State(UpdatePlace.Mode.OFF, prefs.enabled, Phase.IDLE, null, null, null, 0, false, CheckResult.NONE, false);
    }

    /**
     * The updater of this application: the running product version, the system's place, the default folder.
     */
    @NotNull
    public static synchronized UpdateService getInstance() {
        if (instance == null) {
            String version = GeneralUtils.getPlainVersion();
            instance = new UpdateService(() -> UpdatePlace.detect(version), defaultFolder(), new UpdateHttp(),
                UpdateService::defaultVerifier, System::currentTimeMillis);
        }
        return instance;
    }

    /**
     * Windows {@code %LOCALAPPDATA%/Chromite/updates} (not the roaming profile: installers are large),
     * macOS {@code ~/Library/Application Support/Chromite/updates}.
     */
    @NotNull
    public static Path defaultFolder() {
        String home = System.getProperty("user.home"); //$NON-NLS-1$
        if (System.getProperty("os.name", "").startsWith("Windows")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            String local = System.getenv("LOCALAPPDATA"); //$NON-NLS-1$
            return Path.of(local != null ? local : Path.of(home, "AppData", "Local").toString(), "Chromite", "updates"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        return Path.of(home, "Library", "Application Support", "Chromite", "updates"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /**
     * The production checks of a place, or the dev test mode's.
     */
    @Nullable
    public static UpdateVerifier defaultVerifier(@NotNull UpdatePlace place) {
        if (place.target() == null || place.home() == null) {
            return null;
        }
        UpdateTestMode test = place.test();
        if (UpdateTarget.PLATFORM_WINDOWS.equals(place.target().platform())) {
            return test == null ? new WindowsUpdateVerifier() : new WindowsUpdateVerifier(
                test.publisher() != null ? test.publisher() : WindowsUpdateVerifier.PUBLISHER,
                test.productName() != null ? test.productName() : WindowsUpdateVerifier.PRODUCT_NAME,
                WindowsUpdateVerifier::readSignature);
        }
        return new MacUpdateVerifier(place.home(), test != null);
    }

    /**
     * Whether a helper is applying an update now: a new start waits for it.
     */
    public static boolean isApplyRunning() {
        return UpdateApplier.isRunning(defaultFolder());
    }

    /**
     * Whether this start of {@code version} is the first start after an update (the update window shows it). Call before
     * the updater starts: it reads the helper's result, which the updater then removes. A record of this version that is
     * not of a first start any more is removed with the copy.
     */
    @Nullable
    public static UpdateRun.FirstStart detectFirstStart(@NotNull String version) {
        Path folder = defaultFolder();
        UpdateRun runs = new UpdateRun(folder);
        UpdateRun.Record run = runs.read();
        UpdateRun.FirstStart first = UpdateRun.firstStart(run, UpdateApplier.peekOutcome(folder), previousVersion(folder),
            version, System.currentTimeMillis());
        if (first == null && run != null && version.equals(run.to)) {
            runs.remove();
            runs.removeCopy();
        }
        return first;
    }

    /**
     * The version of the installer kept for a rollback ({@code previous/<version>/}), when there is exactly one.
     */
    @Nullable
    private static String previousVersion(@NotNull Path folder) {
        try (Stream<Path> children = Files.list(folder.resolve(PREVIOUS_FOLDER))) {
            List<String> versions = children.filter(Files::isDirectory).map(p -> p.getFileName().toString())
                .filter(UpdateVersion::isValid).toList();
            return versions.size() == 1 ? versions.get(0) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * The record of the updates of this installation.
     */
    @NotNull
    public UpdateRun getRuns() {
        return runs;
    }

    @NotNull
    public Detail getDetail() {
        return detail;
    }

    public void addListener(@NotNull Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(@NotNull Listener listener) {
        listeners.remove(listener);
    }

    @NotNull
    public State getState() {
        return state;
    }

    /**
     * The update the last end did not install, until {@link #acknowledgeInstallFailure} (the window told the user).
     */
    @Nullable
    public InstallFailure getInstallFailure() {
        return installFailure;
    }

    public void acknowledgeInstallFailure() {
        installFailure = null;
    }

    /**
     * The update site of this copy ({@link #ORIGIN}, or the dev test mode's).
     */
    @NotNull
    public URI getOrigin() {
        UpdateTestMode test = place.test();
        return test != null ? test.origin() : ORIGIN;
    }

    /**
     * Windows: this copy is installed for all users. A version received is installed by [Restart now] only, after the
     * administrator prompt; a plain close does not install it.
     */
    public boolean isAllUsers() {
        return place.allUsers();
    }

    /**
     * Detects the place and schedules the first check. Call once, when the main window is up.
     */
    public void start() {
        executor.execute(() -> {
            try {
                place = detector.call();
            } catch (Exception e) {
                place = UpdatePlace.off("detection failed"); //$NON-NLS-1$
                log.debug("Update place detection failed: " + e.getMessage()); //$NON-NLS-1$
            }
            log.debug("Updates: " + place.mode() + " (" + place.reason() + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            verifier = verifiers.apply(place);
            if (verifier == null && place.mode() == UpdatePlace.Mode.APPLY) {
                place = UpdatePlace.off("no checks for this place"); //$NON-NLS-1$
            }
            // The outcome of the update applied at the last end: told once, on this start. A helper writes it to its result
            // file, the copy of the app to the record (its window has told a failure already).
            UpdateApplier.Outcome outcome = UpdateApplier.takeOutcome(folder);
            UpdateRun.Record run = runs.read();
            boolean toldByWindow = false;
            if (run != null && place.target() != null && !run.to.equals(place.target().version())) {
                if (outcome == null && run.installCode != null) {
                    outcome = new UpdateApplier.Outcome(run.to, run.installCode, run.installEndAt != null ? run.installEndAt : 0);
                    toldByWindow = run.helperShownAt != null;
                }
                // Not an update to this version: done with (the first start of a version removes its own record)
                runs.remove();
                runs.removeCopy();
            }
            if (outcome != null && outcome.code() != 0 && place.target() != null
                && !outcome.version().equals(place.target().version())) {
                log.warn("Update " + outcome.version() + " was not installed: code " + outcome.code()); //$NON-NLS-1$ //$NON-NLS-2$
                failedInstallVersion = outcome.version();
                if (!toldByWindow) {
                    installFailure = new InstallFailure(outcome.version(), outcome.code());
                }
            }
            publish(state.phase(), state.version(), state.notesUrl(), state.step(), state.progress());
            if (place.mode() != UpdatePlace.Mode.OFF && prefs.enabled) {
                schedule(FIRST_CHECK_MS);
            }
        });
    }

    /**
     * [Check now] in Options and [Try again] on a failure: checks at once, and receives a failed version again.
     */
    public void checkNow() {
        if (stopped) {
            return;
        }
        executor.execute(() -> check(true));
    }

    /**
     * Options' "Check for updates automatically". Off stops a download that runs; a received version stays.
     */
    public void setEnabled(boolean enabled) {
        if (!enabled) {
            cancelled = true;
        }
        executor.execute(() -> {
            prefs.enabled = enabled;
            prefs.write();
            cancelled = false;
            if (timer != null) {
                timer.cancel(false);
                timer = null;
            }
            if (enabled && place.mode() != UpdatePlace.Mode.OFF) {
                schedule(FIRST_CHECK_MS);
            }
            if (!enabled && state.phase() == Phase.DOWNLOADING) {
                publish(ready != null ? Phase.READY : Phase.IDLE, ready != null ? ready.entry().version() : null,
                    ready != null ? ready.entry().notesUrl() : null, null, 0);
            } else {
                publish(state.phase(), state.version(), state.notesUrl(), state.step(), state.progress());
            }
        });
    }

    /**
     * [Dismiss] on a failure: hides the failure of that version for good (its record keeps it).
     */
    public void dismiss() {
        executor.execute(() -> {
            UpdatePrefs.Failure f = prefs.failed;
            if (state.phase() != Phase.FAILED || f == null || !f.version().equals(state.version()) || f.dismissed()) {
                return;
            }
            prefs.failed = new UpdatePrefs.Failure(f.version(), f.at(), f.step(), true);
            prefs.write();
            publish(state.phase(), state.version(), state.notesUrl(), state.step(), state.progress());
        });
    }

    private void schedule(long delayMs) {
        if (timer != null) {
            timer.cancel(false);
        }
        timer = executor.schedule(() -> {
            timer = null;
            check(false);
            if (prefs.enabled && !stopped) {
                schedule(EVERY_MS);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * One round (on the updater's thread).
     *
     * @param manual [Check now] / [Try again]: runs while automatic checks are off, and receives a failed version again
     */
    void check(boolean manual) {
        if (stopped || place.mode() == UpdatePlace.Mode.OFF || place.target() == null || (!manual && !prefs.enabled)) {
            return;
        }
        UpdateTarget here = place.target();
        setChecking(true);
        try {
            String text = http.fetchFeed(getOrigin());
            if (text == null) {
                lastCheck(CheckResult.UNREACHABLE);
                return;
            }
            List<UpdateEntry> entries = UpdateFeed.parse(text, getOrigin());
            UpdateEntry entry = UpdateFeed.pick(entries, here);
            if (entry == null) {
                lastCheck(CheckResult.UP_TO_DATE);
                if (state.phase() == Phase.AVAILABLE || state.phase() == Phase.FAILED) {
                    publish(Phase.IDLE, null, null, null, 0);
                }
                return;
            }
            lastCheck(CheckResult.FOUND);
            if (place.mode() == UpdatePlace.Mode.NOTIFY) {
                publish(Phase.AVAILABLE, entry.version(), entry.notesUrl(), null, 0);
                return;
            }
            if (ready != null && ready.entry().version().equals(entry.version())) {
                return;
            }
            UpdatePrefs.Failure failed = prefs.failed;
            if (!manual && failed != null && failed.version().equals(entry.version()) && clock.getAsLong() - failed.at() < RETRY_FAILED_MS) {
                // Not received again by itself within a day of its failure
                if (!keepReady() && (state.phase() != Phase.FAILED || !entry.version().equals(state.version()))) {
                    publish(Phase.FAILED, entry.version(), entry.notesUrl(), failed.step(), 0);
                }
                return;
            }
            receive(entry, entries, here);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            setChecking(false);
        }
    }

    private void receive(@NotNull UpdateEntry entry, @NotNull List<UpdateEntry> entries, @NotNull UpdateTarget here)
        throws InterruptedException {
        publish(Phase.DOWNLOADING, entry.version(), entry.notesUrl(), null, 0);
        Path versionFolder = folder.resolve(entry.version());
        Path file = versionFolder.resolve(entry.file());
        UpdateVerifier.Verdict verdict = null;
        try {
            // Received at an earlier start: checked again, not received again
            if (Files.isRegularFile(file)) {
                verdict = verifier.verify(file, entry);
            }
            if (verdict == null || !verdict.ok()) {
                cancelled = false;
                long startedAt = clock.getAsLong();
                detail = new Detail(startedAt, 0, entry.size(), null);
                file = http.download(entry, versionFolder, bytes -> {
                    detail = new Detail(startedAt, bytes, entry.size(), null);
                    int progress = (int) (bytes * 100 / entry.size());
                    if (progress != state.progress()) {
                        publish(Phase.DOWNLOADING, entry.version(), entry.notesUrl(), null, progress);
                    }
                }, () -> cancelled || stopped);
                detail = new Detail(startedAt, entry.size(), entry.size(), clock.getAsLong() - startedAt);
                verdict = verifier.verify(file, entry);
            } else {
                // Received at an earlier start: its time is not known
                detail = new Detail(null, entry.size(), entry.size(), null);
            }
        } catch (IOException e) {
            // Offline, cut, cancelled: quietly, the next round tries again
            log.debug("Update download stopped: " + e.getMessage()); //$NON-NLS-1$
            if (!keepReady()) {
                publish(Phase.IDLE, null, null, null, 0);
            }
            return;
        }
        if (!verdict.ok()) {
            log.warn("Update " + entry.version() + " did not pass the check: " + verdict.step()); //$NON-NLS-1$ //$NON-NLS-2$
            UpdateFiles.deleteTree(versionFolder);
            failedNow(entry.version(), verdict.step());
            if (!keepReady()) {
                publish(Phase.FAILED, entry.version(), entry.notesUrl(), verdict.step(), 0);
            }
            return;
        }
        ready = new Ready(entry, file, verdict.app());
        if (prefs.failed != null) {
            prefs.failed = null;
            prefs.write();
        }
        removeOtherVersions(entry.version());
        receivePrevious(entries, here);
        publish(Phase.READY, entry.version(), entry.notesUrl(), null, 100);
    }

    /**
     * The installer of the version installed now, for a rollback: once, with the same checks (Windows; the macOS
     * helper keeps the replaced bundle instead). A failure only means a rollback has to fetch it from the site.
     */
    private void receivePrevious(@NotNull List<UpdateEntry> entries, @NotNull UpdateTarget here) throws InterruptedException {
        if (previous != null || !UpdateTarget.PLATFORM_WINDOWS.equals(here.platform())) {
            return;
        }
        UpdateEntry current = UpdateFeed.current(entries, here);
        if (current == null) {
            return;
        }
        Path previousFolder = folder.resolve(PREVIOUS_FOLDER).resolve(current.version());
        Path file = previousFolder.resolve(current.file());
        try {
            if (!Files.isRegularFile(file) || !verifier.verify(file, current).ok()) {
                file = http.download(current, previousFolder, bytes -> { }, () -> cancelled || stopped);
                if (!verifier.verify(file, current).ok()) {
                    UpdateFiles.deleteTree(previousFolder);
                    return;
                }
            }
            previous = current;
        } catch (IOException e) {
            log.debug("Previous installer not received: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Removes the received files of other versions (a version superseded by a newer one). The installer kept for a
     * rollback ({@code previous/}) and the bundles put aside ({@code before-*}) stay.
     */
    private void removeOtherVersions(@NotNull String keep) {
        try (Stream<Path> children = Files.list(folder)) {
            children.filter(p -> Files.isDirectory(p) && UpdateVersion.isValid(p.getFileName().toString())
                    && !p.getFileName().toString().equals(keep))
                .forEach(UpdateFiles::deleteTree);
        } catch (IOException ignored) {
            // (left for the next time)
        }
    }

    /**
     * A version received and checked stays what is shown (it is still applied at the end) while a newer one is cut,
     * fails its check or waits for its day.
     */
    private boolean keepReady() {
        if (ready == null) {
            return false;
        }
        UpdateEntry e = ready.entry();
        if (state.phase() != Phase.READY || !e.version().equals(state.version())) {
            publish(Phase.READY, e.version(), e.notesUrl(), null, 100);
        }
        return true;
    }

    private void failedNow(@NotNull String version, @Nullable UpdateStep step) {
        // The same version failing again stays dismissed
        boolean dismissed = prefs.failed != null && prefs.failed.version().equals(version) && prefs.failed.dismissed();
        prefs.failed = new UpdatePrefs.Failure(version, clock.getAsLong(), step, dismissed);
        prefs.write();
    }

    /**
     * Applies a checked update when Chromite ends: called after the normal exit path (unsaved editors, open
     * transactions) has finished, on the exiting thread. Checks the file once more, writes the record of the update,
     * then starts the copy of the app ([Restart now] on Windows) or the helper, and stops the updater. Without an update,
     * [Restart now] still starts Chromite again.
     *
     * @param restart the user chose [Restart now]
     * @param window  the update window ([Restart now] shows it while the file is checked again)
     */
    public void applyAtExit(boolean restart, @Nullable ExitWindow window) {
        long endAt = clock.getAsLong();
        stopped = true;
        cancelled = true;
        UpdatePlace here = place;
        State shown = state;
        String from = here.target() != null ? here.target().version() : null;
        boolean showing = restart && window != null && from != null && here.mode() == UpdatePlace.Mode.APPLY
            && shown.phase() == Phase.READY && shown.version() != null;
        if (showing) {
            window.checking(from, shown.version(), endAt, detail.receivedMs());
        }
        Ready plan = null;
        try {
            Future<Ready> checked = executor.submit(() -> toApply(restart));
            plan = checked.get(EXIT_CHECK_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            log.warn("Update not applied: " + e.getMessage()); //$NON-NLS-1$
        } finally {
            executor.shutdownNow();
        }
        long checkEndAt = clock.getAsLong();
        try {
            if (here.home() == null || (plan == null && !restart)) {
                return;
            }
            boolean windows = here.target() != null && UpdateTarget.PLATFORM_WINDOWS.equals(here.target().platform());
            UpdateRun.Record record = plan != null && from != null ? newRecord(plan, from, restart, endAt, checkEndAt, windows) : null;
            if (record != null && window != null) {
                window.describe(record);
            }
            if (windows) {
                if (record != null && showing) {
                    window.checked(checkEndAt);
                    if (handOver(window, plan, record, here)) {
                        return;
                    }
                }
                writeRecord(record);
                UpdateApplier.startWindowsHelper(folder, plan != null ? plan.file() : null, plan != null ? plan.entry().sha256() : null,
                    plan != null ? plan.entry().version() : null, restart ? here.launcher() : null, here.test() != null, here.allUsers());
            } else {
                writeRecord(record);
                Path app = here.home();
                Path aside = folder.resolve(ASIDE_PREFIX + (plan != null ? plan.entry().version() : "restart")).resolve(app.getFileName()); //$NON-NLS-1$
                Path next = plan != null && here.test() == null ? plan.app() : null;
                UpdateApplier.startMacHelper(folder, app, next, UpdateApplier.macTarget(app, next), aside,
                    plan != null ? plan.entry().version() : null, restart);
            }
            log.debug("Update helper started" + (plan != null ? " for " + plan.entry().version() : "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        } catch (IOException | RuntimeException e) {
            log.warn("Update helper not started: " + e.getMessage()); //$NON-NLS-1$
        } finally {
            if (showing) {
                window.close();
            }
        }
    }

    /**
     * The record of the update applied now: [Restart now] counts from its press, a plain close from the new version's
     * first start.
     */
    @NotNull
    private UpdateRun.Record newRecord(@NotNull Ready plan, @NotNull String from, boolean restart, long endAt, long checkEndAt,
                                       boolean windows) {
        UpdateRun.Record r = new UpdateRun.Record();
        r.from = from;
        r.to = plan.entry().version();
        if (restart) {
            r.restartAt = endAt;
            r.downloadMs = detail.receivedMs();
            r.checkEndAt = checkEndAt;
        } else {
            r.quitAt = endAt;
        }
        r.language = Locale.getDefault().getLanguage();
        r.downloadPage = getOrigin() + "/"; //$NON-NLS-1$
        UpdateEntry back = previous;
        if (windows && back != null) {
            Path file = folder.resolve(PREVIOUS_FOLDER).resolve(back.version()).resolve(back.file());
            if (Files.isRegularFile(file)) {
                r.previous = new UpdateRun.Installer(back.version(), file, back.sha256());
            }
        }
        return r;
    }

    /**
     * Windows, [Restart now]: the copy of the app made by the window shows the installation and runs the installer as
     * its child. False when there is no copy, or it did not start: the helper applies the update as before.
     */
    private boolean handOver(@NotNull ExitWindow window, @NotNull Ready plan, @NotNull UpdateRun.Record r, @NotNull UpdatePlace here) {
        List<String> command = window.copyCommand();
        if (command == null || here.launcher() == null || here.home() == null) {
            return false;
        }
        try {
            r.installer = new UpdateRun.Installer(plan.entry().version(), plan.file().toAbsolutePath(), plan.entry().sha256());
            r.installDir = here.home().toString();
            r.launcher = here.launcher().toString();
            r.pid = ProcessHandle.current().pid();
            r.allUsers = here.allUsers() ? Boolean.TRUE : null;
            if (here.test() != null) {
                r.dryRun = true;
                r.testCode = here.test().installerCode();
            }
            runs.write(r);
            UpdateApplier.startWindowCopy(folder, command);
        } catch (IOException | RuntimeException e) {
            log.warn("The update window's copy did not start: " + e.getMessage()); //$NON-NLS-1$
            r.installer = null;
            r.installDir = null;
            r.launcher = null;
            r.pid = null;
            r.allUsers = null;
            r.dryRun = null;
            r.testCode = null;
            return false;
        }
        log.debug("Update " + r.to + " handed over to the update window's copy"); //$NON-NLS-1$ //$NON-NLS-2$
        window.handedOver(runs);
        return true;
    }

    private void writeRecord(@Nullable UpdateRun.Record record) {
        if (record == null) {
            return;
        }
        try {
            runs.write(record);
        } catch (IOException e) {
            // (the new version's first start then shows no window)
            log.debug("Update record not written: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * What the end applies: the checked file, checked ONCE MORE (it has not changed since), or nothing.
     */
    @Nullable
    private Ready toApply(boolean restart) throws InterruptedException {
        if (timer != null) {
            timer.cancel(false);
            timer = null;
        }
        if (ready == null || place.mode() != UpdatePlace.Mode.APPLY || (!prefs.enabled && !restart)
            // An installation that failed at the last end is tried again only when the user asks ([Restart now])
            || (!restart && ready.entry().version().equals(failedInstallVersion))
            // An installation for all users asks for administrator rights: only with the update window ([Restart now]),
            // never as a prompt out of nowhere after the window was closed
            || (!restart && place.allUsers())) {
            return null;
        }
        UpdateVerifier.Verdict again = verifier.verify(ready.file(), ready.entry());
        if (!again.ok()) {
            log.warn("Update " + ready.entry().version() + " changed since its check: " + again.step()); //$NON-NLS-1$ //$NON-NLS-2$
            UpdateFiles.deleteTree(folder.resolve(ready.entry().version()));
            failedNow(ready.entry().version(), again.step());
            ready = null;
            return null;
        }
        return again.app() != null ? new Ready(ready.entry(), ready.file(), again.app()) : ready;
    }

    private void setChecking(boolean checking) {
        State s = state;
        state = new State(place.mode(), prefs.enabled, s.phase(), s.version(), s.notesUrl(), s.step(), s.progress(),
            s.dismissed(), s.lastCheck(), checking);
        fire();
    }

    private void lastCheck(@NotNull CheckResult result) {
        State s = state;
        state = new State(place.mode(), prefs.enabled, s.phase(), s.version(), s.notesUrl(), s.step(), s.progress(),
            s.dismissed(), result, s.checking());
        fire();
    }

    private void publish(@NotNull Phase phase, @Nullable String version, @Nullable String notesUrl, @Nullable UpdateStep step, int progress) {
        UpdatePrefs.Failure f = prefs.failed;
        boolean dismissed = phase == Phase.FAILED && f != null && f.dismissed() && f.version().equals(version);
        State s = state;
        state = new State(place.mode(), prefs.enabled, phase, version, notesUrl, step, progress, dismissed, s.lastCheck(), s.checking());
        fire();
    }

    private void fire() {
        State s = state;
        for (Listener listener : listeners) {
            try {
                listener.stateChanged(s);
            } catch (RuntimeException e) {
                log.debug(e);
            }
        }
    }
}
