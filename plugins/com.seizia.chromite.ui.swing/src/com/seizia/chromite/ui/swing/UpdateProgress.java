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
package com.seizia.chromite.ui.swing;

import com.seizia.chromite.Log;
import com.seizia.chromite.model.update.UpdateApplier;
import com.seizia.chromite.model.update.UpdatePlace;
import com.seizia.chromite.model.update.UpdateRun;
import com.seizia.chromite.model.update.UpdateService;
import com.seizia.chromite.model.update.UpdateTestMode;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.update.UpdateView;
import com.seizia.chromite.ui.swing.update.UpdateWindow;
import com.seizia.chromite.utils.GeneralUtils;
import com.seizia.chromite.utils.RuntimeUtils;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The update window in the app: when it is shown and what it says, on three occasions.
 *
 * 설계: Turbrix #747 {@code electron/updateProgress.cjs}를 옮겼다(docs/chromite/auto-update.md "업데이트 진행 창").
 * <ol>
 *     <li>받는 중: 제목 표시줄의 "새 버전 X" 단추를 누르면 창이 받기를 보이고, 받기가 끝나면 그 창이 "받았습니다"가 된다
 *     ([지금 다시 시작]·[나중에]·[업데이트 내용]). 창은 스스로 뜨지 않고, 닫아도 받기는 계속된다.</li>
 *     <li>[지금 다시 시작](옛 버전): 주 창이 닫힌 뒤 파일을 다시 확인하는 동안 창이 뜨고({@link UpdateService.ExitWindow}),
 *     Windows에서는 그동안 앱의 사본({@link UpdateWindowCopy})을 만든다. 사본의 창이 같은 자리에 뜨면 이 창은 닫히고 앱이 끝난다.
 *     사본을 쓰지 않는 경우(macOS, 사본을 만들지 못함)에는 도우미가 지금처럼 설치하고 창은 앱과 함께 닫힌다.</li>
 *     <li>업데이트 뒤 첫 시작(새 버전): Look and Feel을 설치한 직후 이 창이 시작 화면 대신 뜨고({@link #openFirstStart}), 기록이
 *     [지금 다시 시작]이면 창을 이어받았다고 적는다(사본이 닫힘). 작업 공간과 번들을 읽는 동안 "Chromite 시작"이고, 주 창이 뜨면
 *     "업데이트를 마쳤습니다"를 보인 뒤 {@value #DONE_MS}ms 뒤 닫힌다. 시작하지 못하면 "Chromite를 시작하지 못했습니다"를 보이고
 *     ([이전 버전으로 되돌리기]·[로그 폴더 열기]·[닫기]) 시작 오류 대화상자 대신 이 창이 닫힐 때까지 기다린다.</li>
 * </ol>
 * 창의 모든 상태는 EDT에서만 바꾼다. 다른 스레드(종료하는 애플리케이션 스레드, 시작 스레드)는 EDT로 넘긴다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: 업데이트 진행 창 (docs/chromite/auto-update.md "업데이트 진행 창")
 * - 2026-11-09 되돌리기 기록에 설치 범위: 모든 사용자 설치는 이전 버전도 관리자 권한으로 설치 (docs/chromite/auto-update.md "관리자 권한으로 설치")
 * - 2026-11-11 업데이트 뒤 첫 시작 창은 이전 창의 테마가 라이트여도 그 테마로(시작 때 운영체제 설정의 테마가 설치되므로), 기록이 없으면 그대로
 *   (앱 변경 #38, docs/chromite/swing-options.md "테마")
 */
final class UpdateProgress {

    private static final Log log = Log.getLog(UpdateProgress.class);

    /**
     * The done state is seen this long before the window closes.
     */
    static final int DONE_MS = 600;
    private static final int TICK_MS = 1000;
    /**
     * How long the old app waits for the copy's window before it ends.
     */
    private static final long HELPER_SHOWN_MS = 8000;
    private static final long COPY_WAIT_SECONDS = 120;
    private static final int CLEAN_TRIES = 10;
    private static final long CLEAN_PAUSE_MS = 500;

    private enum Mode {
        BUTTON,
        EXIT,
        FIRST
    }

    // EDT only
    @Nullable
    private static UpdateWindow window;
    @Nullable
    private static Mode mode;
    @Nullable
    private static Timer ticker;
    @Nullable
    private static Supplier<UpdateView.Input> input;

    // The first start after an update
    @Nullable
    private static volatile First first;

    private UpdateProgress() {
    }

    // ---------- A. the title bar's button while a version is received (EDT)

    /**
     * The update button while a version is received: the window shows the download (and the version ready once it is).
     *
     * @param restart [Restart now] of the title bar's menu
     */
    static void openFromButton(@NotNull JFrame owner, @NotNull Runnable restart) {
        if (mode == Mode.FIRST || mode == Mode.EXIT) {
            return;
        }
        UpdateView.Input in = buttonInput();
        if (in == null) {
            return;
        }
        if (window != null && window.isOpen()) {
            window.push(UpdateView.of(in));
            window.toFront();
            return;
        }
        show(Mode.BUTTON, UpdateProgress::buttonInput, null, owner, action -> {
            switch (action) {
                case CLOSE, LATER -> close();
                case NOTES -> {
                    String notes = UpdateService.getInstance().getState().notesUrl();
                    if (notes != null) {
                        DBWorkbench.getPlatformUI().executeShellProgram(notes);
                    }
                }
                case RESTART -> {
                    close();
                    restart.run();
                }
                default -> {
                }
            }
        });
    }

    /**
     * The updater's state changed: the button's window follows it (nothing to show: it closes).
     */
    static void updaterChanged() {
        if (mode != Mode.BUTTON || window == null) {
            return;
        }
        UpdateView.Input in = buttonInput();
        if (in == null) {
            close();
        } else {
            window.push(UpdateView.of(in));
        }
    }

    @Nullable
    private static UpdateView.Input buttonInput() {
        UpdateService service = UpdateService.getInstance();
        UpdateService.State state = service.getState();
        UpdateService.Detail detail = service.getDetail();
        UpdateView.Input in = new UpdateView.Input();
        in.from = GeneralUtils.getPlainVersion();
        in.to = state.version();
        switch (state.phase()) {
            case DOWNLOADING -> {
                in.state = UpdateView.State.DOWNLOADING;
                in.percent = state.progress();
                in.bytes = detail.bytes();
                in.size = detail.size() > 0 ? detail.size() : null;
                in.elapsedMs = detail.startedAt() != null ? System.currentTimeMillis() - detail.startedAt() : null;
            }
            case READY -> {
                in.state = UpdateView.State.READY;
                in.size = detail.size() > 0 ? detail.size() : null;
                in.notes = state.notesUrl() != null;
                in.times[0] = detail.receivedMs();
            }
            default -> {
                return null;
            }
        }
        return in;
    }

    // ---------- B. [Restart now] in the old version

    /**
     * The window of the exit: shown at [Restart now] while the file is checked again, then handed to the copy.
     *
     * @param mainFrame the main window that closed (the window shows over its place)
     */
    @NotNull
    static UpdateService.ExitWindow exitWindow(@Nullable JFrame mainFrame) {
        return new ExitWindow(mainFrame != null ? mainFrame.getBounds() : null);
    }

    private static final class ExitWindow implements UpdateService.ExitWindow {
        @Nullable
        private final Rectangle mainBounds;
        private volatile String from;
        private volatile String to;
        private volatile long restartAt;
        @Nullable
        private volatile Long downloadMs;
        @Nullable
        private volatile Long checkEndAt;
        @Nullable
        private CompletableFuture<List<String>> copy;

        ExitWindow(@Nullable Rectangle mainBounds) {
            this.mainBounds = mainBounds;
        }

        @Override
        public void describe(@NotNull UpdateRun.Record record) {
            record.dark = ChromiteTheme.getCurrent() == ChromiteTheme.DARK;
            try {
                record.logFolder = GeneralUtils.getMetadataFolder().toString();
            } catch (RuntimeException e) {
                log.debug("No log folder for the update record: " + e.getMessage()); //$NON-NLS-1$
            }
            Point at = onEdt(() -> window != null && mode == Mode.EXIT ? window.getLocation() : null);
            if (at != null) {
                record.x = at.x;
                record.y = at.y;
            }
        }

        @Override
        public void checking(@NotNull String from, @NotNull String to, long restartAt, @Nullable Long downloadMs) {
            this.from = from;
            this.to = to;
            this.restartAt = restartAt;
            this.downloadMs = downloadMs;
            if (RuntimeUtils.isWindows()) {
                UpdateRun run = UpdateService.getInstance().getRuns();
                // Made while the file is checked again (both take seconds)
                copy = CompletableFuture.supplyAsync(() -> {
                    try {
                        return UpdateWindowCopy.make(run);
                    } catch (IOException | RuntimeException e) {
                        log.warn("The update window's copy was not made: " + e.getMessage()); //$NON-NLS-1$
                        run.removeCopy();
                        return null;
                    }
                }, runnable -> {
                    Thread t = new Thread(runnable, "Chromite update window copy"); //$NON-NLS-1$
                    t.setDaemon(true);
                    t.start();
                });
            }
            SwingUtilities.invokeLater(() -> {
                close();
                Point at = null;
                if (mainBounds != null) {
                    at = new Point(mainBounds.x + mainBounds.width / 2, mainBounds.y + mainBounds.height / 2);
                }
                show(Mode.EXIT, this::installingInput, null, null, action -> { });
                if (at != null && window != null) {
                    Dimension size = window.getFrame().getSize();
                    window.getFrame().setLocation(at.x - size.width / 2, at.y - size.height / 2);
                }
            });
        }

        @NotNull
        private UpdateView.Input installingInput() {
            UpdateView.Input in = new UpdateView.Input();
            in.state = UpdateView.State.INSTALLING;
            Long checked = checkEndAt;
            in.checking = checked == null;
            in.from = from;
            in.to = to;
            in.times[0] = downloadMs;
            in.times[1] = checked != null ? checked - restartAt : null;
            in.elapsedMs = System.currentTimeMillis() - restartAt;
            return in;
        }

        @Override
        public void checked(long checkEndAt) {
            this.checkEndAt = checkEndAt;
            SwingUtilities.invokeLater(UpdateProgress::refresh);
        }

        @Nullable
        @Override
        public List<String> copyCommand() {
            CompletableFuture<List<String>> made = copy;
            if (made == null) {
                return null;
            }
            try {
                return made.get(COPY_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                log.warn("The update window's copy is not ready: " + e.getMessage()); //$NON-NLS-1$
                return null;
            }
        }

        @Override
        public void handedOver(@NotNull UpdateRun run) {
            // The copy's window covers this one: then this app ends
            waitShown(run);
            close();
        }

        @Override
        public void close() {
            onEdt(() -> {
                UpdateProgress.close();
                return null;
            });
        }
    }

    /**
     * Waits until the copy's window is up ({@code helperShownAt}), {@value #HELPER_SHOWN_MS} ms at most.
     */
    private static void waitShown(@NotNull UpdateRun run) {
        long until = System.currentTimeMillis() + HELPER_SHOWN_MS;
        while (System.currentTimeMillis() < until) {
            UpdateRun.Record r = run.read();
            if (r == null || r.helperShownAt != null) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ---------- C. the first start after an update

    /**
     * What the first start knows: the update, the moments of this start, and the outcome of a failed start.
     */
    private static final class First {
        final UpdateRun.FirstStart start;
        final UpdateRun run;
        final String version;
        final long appStartAt;
        volatile Long startEndAt;
        volatile Long openEndAt;
        volatile UpdateView.State state = UpdateView.State.STARTING;
        volatile String error;
        volatile Path logFolder;
        final CountDownLatch closed = new CountDownLatch(1);

        First(@NotNull UpdateRun.FirstStart start, @NotNull UpdateRun run, @NotNull String version, long appStartAt) {
            this.start = start;
            this.run = run;
            this.version = version;
            this.appStartAt = appStartAt;
        }

        @Nullable
        UpdateRun.Installer previous() {
            UpdateRun.Record r = start.record();
            UpdateRun.Installer back = r != null ? r.previous : null;
            return back != null && back.file != null && Files.isRegularFile(Path.of(back.file)) && RuntimeUtils.isWindows() ? back : null;
        }

        @NotNull
        UpdateView.Input input() {
            UpdateRun.Times times = UpdateRun.timesOf(start, appStartAt, startEndAt, openEndAt);
            UpdateView.Input in = new UpdateView.Input();
            in.state = state;
            in.from = start.from();
            in.to = start.to();
            in.times[0] = times.download();
            in.times[1] = times.check();
            in.times[2] = times.install();
            in.times[3] = times.start();
            in.times[4] = times.open();
            in.elapsedMs = System.currentTimeMillis() - (start.startAt() != null ? start.startAt() : appStartAt);
            in.back = previous() != null;
            in.error = error;
            return in;
        }
    }

    /**
     * At the start, right after the look and feel: whether this is the first start after an update; then its window is
     * up (it is the splash of this start). Call off the EDT.
     */
    static boolean openFirstStart() {
        String version;
        try {
            version = GeneralUtils.getPlainVersion();
        } catch (RuntimeException e) {
            return false;
        }
        UpdateRun.FirstStart start = UpdateService.detectFirstStart(version);
        if (start == null || GraphicsEnvironment.isHeadless()) {
            return false;
        }
        long appStartAt = ProcessHandle.current().info().startInstant().map(Instant::toEpochMilli).orElse(System.currentTimeMillis());
        First f = new First(start, new UpdateRun(UpdateService.defaultFolder()), version, appStartAt);
        UpdateRun.Record record = start.record();
        // The theme of the window before; null without one (the theme of the operating system, installed at start, stays)
        ChromiteTheme before = record != null && record.dark != null ? (record.dark ? ChromiteTheme.DARK : ChromiteTheme.LIGHT) : null;
        Point at = record != null && record.x != null && record.y != null ? new Point(record.x, record.y) : null;
        try {
            SwingUtilities.invokeAndWait(() -> {
                // The theme of the window before (the saved theme is read once the workspace is locked)
                if (before != null && before != ChromiteTheme.getCurrent()) {
                    before.install(false);
                }
                first = f;
                show(Mode.FIRST, f::input, at, null, UpdateProgress::onFirstAction);
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (InvocationTargetException e) {
            log.warn("The update window did not open", e.getTargetException()); //$NON-NLS-1$
            first = null;
            return false;
        }
        log.debug("First start after the update from " + start.from() + " (" + start.source() + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (start.source() == UpdateRun.Source.RUN) {
            // The copy sees this and closes its own window
            long takenAt = System.currentTimeMillis();
            f.run.patch(r -> r.takenAt = takenAt);
        }
        return true;
    }

    /**
     * Chromite has started (workspace, bundles, services): the main window opens next.
     */
    static void started() {
        First f = first;
        if (f != null) {
            f.startEndAt = System.currentTimeMillis();
        }
    }

    /**
     * The main window is up: "The update is done", then the window closes. Call in the EDT.
     */
    static void mainShown() {
        First f = first;
        if (f == null || f.state != UpdateView.State.STARTING || mode != Mode.FIRST || window == null) {
            return;
        }
        f.openEndAt = System.currentTimeMillis();
        f.state = UpdateView.State.DONE;
        refresh();
        // (seen over the main window that opens under it)
        window.setOnTop(true);
        f.run.remove();
        Timer done = new Timer(DONE_MS, e -> {
            first = null;
            close();
            f.closed.countDown();
            cleanCopy(f.run);
        });
        done.setRepeats(false);
        done.start();
    }

    /**
     * The start failed while the window of the first start is up: the window says it ([Go back to the previous version],
     * [Open the log folder], [Close]) instead of the startup error dialog, and this returns once it is closed.
     *
     * @return false when no first start window is up (the caller shows its dialog)
     */
    static boolean showStartFailure(@Nullable String message, @Nullable Path logFolder) {
        First f = first;
        if (f == null || f.state != UpdateView.State.STARTING) {
            return false;
        }
        f.state = UpdateView.State.FAILED_START;
        f.error = message;
        UpdateRun.Record record = f.start.record();
        f.logFolder = logFolder != null ? logFolder : record != null && record.logFolder != null ? Path.of(record.logFolder) : null;
        // This start is not the first start of an update any more
        f.run.remove();
        SwingUtilities.invokeLater(() -> {
            refresh();
            if (window != null) {
                window.toFront();
            }
        });
        try {
            f.closed.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return true;
    }

    /**
     * The start ends (any way): the window of the first start goes.
     */
    static void endFirstStart() {
        First f = first;
        if (f == null) {
            return;
        }
        first = null;
        f.closed.countDown();
        SwingUtilities.invokeLater(() -> {
            if (mode == Mode.FIRST) {
                close();
            }
        });
    }

    private static void onFirstAction(@NotNull UpdateView.Action action) {
        First f = first;
        if (f == null) {
            return;
        }
        switch (action) {
            case LOG -> {
                Path folder = f.logFolder;
                if (folder != null && Files.isDirectory(folder)) {
                    DBWorkbench.getPlatformUI().executeShellProgram(folder.toString());
                }
            }
            case BACK -> goBack(f);
            case CLOSE -> {
                first = null;
                close();
                f.closed.countDown();
            }
            default -> {
            }
        }
    }

    /**
     * [Go back to the previous version] after a failed start: the copy of this app installs the installer of the version
     * updated from, the same way as an update, and starts it.
     */
    private static void goBack(@NotNull First f) {
        UpdateRun.Installer previous = f.previous();
        if (previous == null || previous.version == null) {
            return;
        }
        f.state = UpdateView.State.INSTALLING;
        Point at = window != null ? window.getLocation() : null;
        boolean dark = ChromiteTheme.getCurrent() == ChromiteTheme.DARK;
        UpdateView.Input in = new UpdateView.Input();
        in.state = UpdateView.State.INSTALLING;
        in.checking = true;
        in.from = f.version;
        in.to = previous.version;
        input = () -> in;
        refresh();
        Thread t = new Thread(() -> {
            boolean handed = false;
            try {
                UpdatePlace place = UpdatePlace.detect(f.version);
                if (place.mode() == UpdatePlace.Mode.APPLY && place.home() != null && place.launcher() != null) {
                    List<String> command = UpdateWindowCopy.make(f.run);
                    long now = System.currentTimeMillis();
                    UpdateRun.Record r = new UpdateRun.Record();
                    r.from = f.version;
                    r.to = previous.version;
                    r.restartAt = now;
                    r.checkEndAt = now;
                    r.installer = previous;
                    r.installDir = place.home().toString();
                    r.launcher = place.launcher().toString();
                    r.pid = ProcessHandle.current().pid();
                    r.allUsers = place.allUsers() ? Boolean.TRUE : null;
                    UpdateTestMode test = place.test();
                    if (test != null) {
                        r.dryRun = true;
                        r.testCode = test.installerCode();
                    }
                    r.language = Locale.getDefault().getLanguage();
                    r.dark = dark;
                    r.x = at != null ? at.x : null;
                    r.y = at != null ? at.y : null;
                    r.logFolder = f.logFolder != null ? f.logFolder.toString() : null;
                    r.downloadPage = (test != null ? test.origin() : UpdateService.ORIGIN) + "/"; //$NON-NLS-1$
                    f.run.write(r);
                    UpdateApplier.startWindowCopy(f.run.getFolder(), command);
                    log.info("Going back to version " + previous.version); //$NON-NLS-1$
                    waitShown(f.run);
                    handed = true;
                } else {
                    log.warn("Going back is not possible here: " + place.reason()); //$NON-NLS-1$
                }
            } catch (IOException | RuntimeException e) {
                log.warn("Going back did not start: " + e.getMessage()); //$NON-NLS-1$
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            boolean done = handed;
            SwingUtilities.invokeLater(() -> {
                if (done) {
                    first = null;
                    close();
                    f.closed.countDown();
                } else {
                    // Back to the failure: the user can still open the log folder or close
                    f.state = UpdateView.State.FAILED_START;
                    input = f::input;
                    refresh();
                }
            });
        }, "Chromite going back"); //$NON-NLS-1$
        t.setDaemon(true);
        t.start();
    }

    /**
     * Removes the copy of the update (it ends once it sees the window taken; its files are held until then).
     */
    private static void cleanCopy(@NotNull UpdateRun run) {
        Thread t = new Thread(() -> {
            for (int i = 0; i < CLEAN_TRIES && Files.exists(run.getCopyFolder()); i++) {
                run.removeCopy();
                try {
                    Thread.sleep(CLEAN_PAUSE_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "Chromite update window copy removal"); //$NON-NLS-1$
        t.setDaemon(true);
        t.start();
    }

    // ---------- the window (EDT)

    private static void show(
        @NotNull Mode showMode,
        @NotNull Supplier<UpdateView.Input> source,
        @Nullable Point position,
        @Nullable Component over,
        @NotNull java.util.function.Consumer<UpdateView.Action> onAction
    ) {
        close();
        UpdateView.Input in = source.get();
        if (in == null) {
            return;
        }
        mode = showMode;
        input = source;
        UpdateWindow w = new UpdateWindow(UpdateView.of(in), onAction);
        window = w;
        w.open(position, over);
        ticker = new Timer(TICK_MS, e -> {
            UpdateView.State state = w.getView().state();
            // (the elapsed time and the download, once a second)
            if (state == UpdateView.State.DOWNLOADING || state == UpdateView.State.INSTALLING || state == UpdateView.State.STARTING) {
                refresh();
            }
        });
        ticker.start();
    }

    private static void refresh() {
        Supplier<UpdateView.Input> source = input;
        UpdateView.Input in = source != null ? source.get() : null;
        if (window != null && window.isOpen() && in != null) {
            window.push(UpdateView.of(in));
        }
    }

    private static void close() {
        if (ticker != null) {
            ticker.stop();
            ticker = null;
        }
        if (window != null) {
            window.close();
            window = null;
        }
        mode = null;
        input = null;
    }

    @Nullable
    private static <T> T onEdt(@NotNull Supplier<T> task) {
        if (SwingUtilities.isEventDispatchThread()) {
            return task.get();
        }
        Object[] result = new Object[1];
        try {
            SwingUtilities.invokeAndWait(() -> result[0] = task.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException e) {
            log.debug(e.getTargetException());
        }
        @SuppressWarnings("unchecked")
        T value = (T) result[0];
        return value;
    }
}
