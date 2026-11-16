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
package com.seizia.chromite.ui.swing.update;

import com.formdev.flatlaf.FlatLaf;
import com.seizia.chromite.model.update.UpdateHandOver;
import com.seizia.chromite.model.update.UpdateRun;
import com.seizia.chromite.model.update.UpdateService;
import com.seizia.chromite.ui.swing.ChromiteDarkLaf;
import com.seizia.chromite.ui.swing.ChromiteLightLaf;
import com.seizia.chromite.ui.swing.UiSemibold;
import com.seizia.chromite.ui.swing.internal.UpdateMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import java.awt.Desktop;
import java.awt.Point;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The copy of the app that shows the update window while the installer runs (Windows): the main class of
 * {@code <updates>/window-app/}, started by the old app at [Restart now] with the updates folder as its argument.
 *
 * 설계: Turbrix #747 {@code electron/updateHelper.cjs}(A안)를 옮겼다(docs/chromite/auto-update.md "업데이트 진행 창").
 * 설치 프로그램은 설치 폴더의 Java 런타임이 풀릴 때까지 기다리므로 앱 자신은 설치하는 동안 창을 보일 수 없다. 그래서 옛 앱이
 * 자기 런타임과 이 진입점에 필요한 jar만 설치 폴더 밖에 복사해 띄운다({@code UpdateWindowCopy}). 이 프로그램은
 * <ol>
 *     <li>옛 앱의 창이 있던 자리에 같은 창을 띄우고 떴다고 기록한다({@code helperShownAt}, 옛 앱은 그것을 보고 끝난다),</li>
 *     <li>옛 앱이 끝나기를 기다리고, 설치 파일이 옛 앱이 확인한 파일인지(SHA-256) 본 뒤 설치 프로그램을 자식으로 실행한다
 *     ({@link UpdateHandOver}, 그래서 종료 코드를 안다),</li>
 *     <li>잘 끝나면 Chromite를 띄우고, 새 버전이 창을 이어받으면({@code takenAt}) 닫는다. 1분 안에 오지 않으면 한 번 더 띄우고,
 *     다시 1분 안에 오지 않으면 "Chromite를 시작하지 못했습니다"를 보인다(Turbrix는 조용히 끝나지만 사용자 결정 "시작 실패 → 로그·닫기
 *     (+ 되돌리기)"를 따른다),</li>
 *     <li>설치 프로그램이 실패하면 설치 실패를 보인다: [다시 시도]·[받는 곳 열기]·[닫기]. 모든 사용자 설치에서 관리자 확인(UAC)을
 *     거부했으면 그 까닭을 말한다. [다시 시도]는 관리자 확인부터 다시 한다.</li>
 * </ol>
 * OSGi 없이 도는 평범한 Java 프로그램이다. 설정 저장소와 작업 공간을 읽지 않고 잠금을 쥐지 않는다. 언어·테마·자리는 기록에서 읽는다.
 * 글꼴은 앱과 같은 운영체제 UI 글꼴이다(테마 properties의 {@code defaultFont}, 등록할 번들 글꼴이 없다). 한국어 semibold의 굵은 대체 글꼴도 앱과 같이
 * {@link UiSemibold}로 정한다({@code ChromiteTheme}은 {@code Log}를 써서 여기서 부를 수 없다).
 * 새 버전이 첫 시작을 마치면 이 폴더를 지운다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: 업데이트 진행 창 (docs/chromite/auto-update.md "업데이트 진행 창")
 * - 2026-11-09 관리자 확인 거부를 설치 실패 문구로 알림 (docs/chromite/auto-update.md "관리자 권한으로 설치")
 * - 2026-11-12 번들 Pretendard 등록을 지움: UI 글꼴이 운영체제 글꼴이 됨. 한국어 semibold는 앱처럼 굵은 기본 글꼴 (앱 변경 #67, docs/chromite/design-spec.md "적용 현황 — 운영체제 UI 글꼴 13")
 */
public final class UpdateWindowApp {

    private static final int POLL_MS = 250;
    private static final int TICKS_PER_SECOND = 1000 / POLL_MS;
    // A constant: inlined, so UpdateService (which needs the model bundle) is not loaded here
    private static final String SITE_PREFIX = UpdateService.ORIGIN_TEXT + "/"; //$NON-NLS-1$

    private final UpdateRun run;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Chromite update window"); //$NON-NLS-1$
        t.setDaemon(true);
        return t;
    });
    // EDT only
    private UpdateRun.Record record;
    private UpdateWindow window;
    private UpdateView.State state = UpdateView.State.INSTALLING;
    private boolean versionKept;
    private boolean elevationRefused;
    private int ticks;
    private Timer ticker;
    /**
     * How many times Chromite was started after the installer.
     */
    private int starts;

    private UpdateWindowApp(@NotNull UpdateRun run, @NotNull UpdateRun.Record record) {
        this.run = run;
        this.record = record;
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.exit(2);
        }
        UpdateRun run = new UpdateRun(Path.of(args[0]));
        UpdateRun.Record record = run.read();
        if (record == null || record.installer == null) {
            System.exit(0);
        }
        // Before the messages are loaded: they follow the old app's language
        if (record.language != null && !record.language.isBlank()) {
            Locale.setDefault(Locale.forLanguageTag(record.language));
        }
        run.log("update window: up for " + record.to); //$NON-NLS-1$
        UpdateWindowApp app = new UpdateWindowApp(run, record);
        SwingUtilities.invokeLater(app::open);
    }

    private void open() {
        // Swing loads the look and feel's UI classes through the event thread's context class loader. A tool loaded into this
        // process (an accessibility bridge, a debugger agent) may have started that thread without one: then every
        // component would be made without its UI (seen with an attached test agent).
        Thread.currentThread().setContextClassLoader(UpdateWindowApp.class.getClassLoader());
        installLookAndFeel(Boolean.TRUE.equals(record.dark));
        window = new UpdateWindow(UpdateView.of(input()), this::onAction);
        window.open(record.x != null && record.y != null ? new Point(record.x, record.y) : null, null);
        long shownAt = System.currentTimeMillis();
        run.patch(r -> r.helperShownAt = shownAt);
        ticker = new Timer(POLL_MS, e -> tick());
        ticker.start();
        install();
    }

    /**
     * The record is read four times a second: the new version's window is up ({@code takenAt}), or the record is gone
     * (the version updated from was opened again, or the new version is done): this window goes.
     */
    private void tick() {
        UpdateRun.Record now = run.read();
        if (now == null || now.takenAt != null) {
            quit();
            return;
        }
        if ((state == UpdateView.State.INSTALLING || state == UpdateView.State.STARTING) && ++ticks % TICKS_PER_SECOND == 0) {
            push();
        }
    }

    private void install() {
        state = UpdateView.State.INSTALLING;
        record.installEndAt = null;
        push();
        UpdateRun.Record plan = record;
        worker.execute(() -> {
            int code;
            try {
                code = UpdateHandOver.install(run, plan);
            } catch (InterruptedException e) {
                return;
            }
            boolean kept = code != 0 && UpdateHandOver.isVersionKept(plan);
            int result = code;
            SwingUtilities.invokeLater(() -> {
                if (result == 0) {
                    installed();
                } else {
                    failed(kept, UpdateHandOver.isElevationRefused(result));
                }
            });
        });
    }

    /**
     * The installer ended well: Chromite starts; the new version takes the window.
     */
    private void installed() {
        if (state != UpdateView.State.INSTALLING) {
            return;
        }
        state = UpdateView.State.STARTING;
        push();
        starts = 0;
        startApp();
    }

    /**
     * Starts Chromite and waits a minute for the new version to take the window: then once more, then the start failed.
     */
    private void startApp() {
        UpdateRun.Record plan = record;
        starts++;
        worker.execute(() -> UpdateHandOver.startApp(run, plan));
        Timer wait = new Timer((int) UpdateHandOver.TAKE_MS, e -> {
            UpdateRun.Record now = run.read();
            if (now == null || now.takenAt != null || state != UpdateView.State.STARTING) {
                return;
            }
            if (starts < 2) {
                startApp();
                return;
            }
            run.log("the new version did not start"); //$NON-NLS-1$
            state = UpdateView.State.FAILED_START;
            push();
        });
        wait.setRepeats(false);
        wait.start();
    }

    private void failed(boolean kept, boolean refused) {
        UpdateRun.Record now = run.read();
        if (now != null && now.takenAt != null) {
            quit();
            return;
        }
        versionKept = kept;
        elevationRefused = refused;
        state = UpdateView.State.FAILED_INSTALL;
        push();
    }

    private void onAction(@NotNull UpdateView.Action action) {
        switch (action) {
            case AGAIN -> {
                if (state == UpdateView.State.FAILED_INSTALL) {
                    install();
                }
            }
            case SITE -> {
                String page = record.downloadPage;
                // Only the update site's pages (the dev test mode's local site too)
                if (page != null && (page.startsWith(SITE_PREFIX) || Boolean.TRUE.equals(record.dryRun))) {
                    browse(page);
                }
            }
            case BACK -> back();
            case LOG -> {
                if (record.logFolder != null && Files.isDirectory(Path.of(record.logFolder))) {
                    openFolder(Path.of(record.logFolder));
                }
            }
            case CLOSE -> {
                // The record goes: the next start of any version removes this copy
                run.remove();
                quit();
            }
            default -> {
            }
        }
    }

    /**
     * [Go back to the previous version]: the installer of the version updated from (kept and checked when the update was
     * received) is installed the same way.
     */
    private void back() {
        UpdateRun.Installer previous = record.previous;
        if (state != UpdateView.State.FAILED_START || previous == null || previous.version == null) {
            return;
        }
        long now = System.currentTimeMillis();
        UpdateRun.Record r = record;
        r.from = r.to;
        r.to = previous.version;
        r.installer = previous;
        r.previous = null;
        r.restartAt = now;
        r.checkEndAt = now;
        r.downloadMs = null;
        r.installStartAt = null;
        r.installEndAt = null;
        r.installCode = null;
        r.takenAt = null;
        try {
            run.write(r);
        } catch (IOException e) {
            run.log("going back not recorded: " + e.getMessage()); //$NON-NLS-1$
        }
        run.log("going back to " + previous.version); //$NON-NLS-1$
        install();
    }

    @NotNull
    private UpdateView.Input input() {
        UpdateView.Input in = new UpdateView.Input();
        long now = System.currentTimeMillis();
        in.state = state;
        in.from = record.from;
        in.to = record.to;
        in.times[0] = record.downloadMs;
        in.times[1] = span(record.restartAt, record.checkEndAt);
        in.times[2] = span(record.checkEndAt, record.installEndAt);
        in.elapsedMs = record.restartAt != null ? now - record.restartAt : null;
        in.versionKept = versionKept;
        in.elevationRefused = elevationRefused;
        in.back = record.previous != null;
        return in;
    }

    private void push() {
        if (window != null && window.isOpen()) {
            window.push(UpdateView.of(input()));
        }
    }

    private void quit() {
        if (ticker != null) {
            ticker.stop();
        }
        if (window != null) {
            window.close();
        }
        System.exit(0);
    }

    @Nullable
    private static Long span(@Nullable Long from, @Nullable Long to) {
        return from != null && to != null && to >= from ? to - from : null;
    }

    /**
     * The app's theme, with its UI font (the operating system's, named in the theme properties) and, as the app does
     * ({@code ChromiteTheme.install}), the bold default font for semibold text when the semibold font lacks the letters of
     * the UI language (Korean: Segoe UI Semibold has no Hangul).
     */
    private static void installLookAndFeel(boolean dark) {
        FlatLaf.setup(dark ? new ChromiteDarkLaf() : new ChromiteLightLaf());
        if (!UiSemibold.isInSemiboldFont(UpdateMessages.update_window_name)) {
            FlatLaf laf = dark ? new ChromiteDarkLaf() : new ChromiteLightLaf();
            laf.setExtraDefaults(Map.of(UiSemibold.SEMIBOLD_FONT_KEY, UiSemibold.BOLD_FONT_VALUE));
            FlatLaf.setup(laf);
        }
    }

    private static void browse(@NotNull String page) {
        try {
            Desktop.getDesktop().browse(URI.create(page));
        } catch (IOException | RuntimeException ignored) {
            // (nothing to open with)
        }
    }

    private static void openFolder(@NotNull Path folder) {
        try {
            Desktop.getDesktop().open(folder.toFile());
        } catch (IOException | RuntimeException ignored) {
            // (nothing to open with)
        }
    }
}
