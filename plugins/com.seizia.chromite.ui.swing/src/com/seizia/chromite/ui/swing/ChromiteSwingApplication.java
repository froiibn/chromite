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

import com.seizia.chromite.model.update.UpdateService;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.UpdateMessages;
import org.eclipse.core.runtime.Platform;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.osgi.service.datalocation.Location;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.LogOutputStream;
import com.seizia.chromite.ModelPreferences;
import com.seizia.chromite.model.DBConstants;
import com.seizia.chromite.model.app.DBPPlatform;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.rcp.DesktopApplicationImpl;
import com.seizia.chromite.registry.timezone.TimezoneRegistry;
import com.seizia.chromite.runtime.ChromiteNotifications;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.OperationSystemState;
import com.seizia.chromite.runtime.ui.DBPPlatformUI;
import com.seizia.chromite.utils.GeneralUtils;
import com.seizia.chromite.utils.PrefUtils;
import com.seizia.chromite.utils.RuntimeUtils;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.IOUtils;
import org.jkiss.utils.StandardConstants;

import javax.swing.*;
import java.awt.Desktop;
import java.awt.desktop.SystemSleepEvent;
import java.awt.desktop.SystemSleepListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;

/**
 * Chromite Swing 프런트엔드의 Equinox 애플리케이션 진입점.
 *
 * 설계: 헤드리스 앱({@code ChromiteHeadlessApplication})처럼 {@link DesktopApplicationImpl}을 상속해
 * SWT 없이 모델 계층을 띄운다. 작업 공간 잠금과 디버그 로그 처리는 SWT 앱({@code ChromiteApplication})의
 * 핵심 동작만 옮겼다(작업 공간 선택·이전 버전 이전·인스턴스 서버·CLI 명령은 B단계 이후).
 * <ul>
 *     <li>{@link #start}는 Equinox 애플리케이션 스레드에서 플랫폼을 초기화한 뒤, Swing 창은 EDT에서 연다.
 *     모델 초기화가 EDT를 막지 않게 하고, Swing 규칙(컴포넌트는 EDT에서만 다룬다)을 지키기 위해서다.</li>
 *     <li>{@link #start}가 반환되면 Equinox가 종료되므로, 메인 창이 닫힐 때까지 래치로 기다린다.
 *     창이 닫히면 같은 스레드에서 플랫폼을 정리하고 반환한다.</li>
 *     <li>{@link #stop}(외부에서 앱 종료를 요청할 때)은 창을 닫아 같은 종료 경로를 타게 한다.</li>
 * </ul>
 *
 * Look and Feel은 작업 공간을 잠그기 전(시작 오류 대화상자용)에 기본 라이트 테마로 설치하고, 설정 저장소를 읽을 수 있게 된 뒤
 * (작업 공간 잠금과 플랫폼 초기화 이후) 메인 창을 열기 직전에 저장된 테마({@link ChromiteTheme})로 바꾼다.
 *
 * 변경 이력:
 * - 2026-11-06 A단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — A단계")
 * - 2026-11-06 B단계: Chromite 테마 설치와 저장된 테마 적용 (docs/chromite/swing-frontend.md "구현 현황 — B단계")
 * - 2026-11-06 D단계: 연결 수 문구를 {@link MainFrame#describeWorkspace}로 옮김(메인 창이 연결 추가·삭제 때 다시 셈)
 *   (docs/chromite/swing-frontend.md "구현 현황 — D단계")
 * - 2026-11-06 화면 문구를 CoreMessages로 옮김(OS 언어를 따름)
 * - 2026-11-07 오류 문구를 {@link ErrorText}로 만듦(클래스 이름이 화면에 나오지 않게, docs/chromite/swing-frontend.md "오류 문구")
 * - 2026-11-07 {@code -data}가 없으면 현재 폴더와 관계없이 기본 작업 공간을 씀: 번들 설정 저장소를 작업 공간을 잠근 뒤에 만들고
 *   ({@link ChromiteSwingActivator#initPreferences}), 그 전에 고치기 전 빌드의 작업 공간을 복사함({@link WorkspaceLocationMigration},
 *   docs/chromite/workspace-location.md)
 * - 2026-11-07 자동 업데이트: 사용자가 창을 닫아 끝날 때 확인한 업데이트를 적용({@link UpdateService#applyAtExit}),
 *   업데이트 도우미가 설치하는 동안에는 시작하지 않음 (docs/chromite/auto-update.md)
 * - 2026-11-07 SWT 앱처럼 Windows 인증서 저장소({@code WINDOWS-ROOT})를 기본 신뢰 저장소로 씀(설정이 켜져 있을 때),
 *   macOS 앱 메뉴의 끝내기·정보·환경설정을 메인 창 경로로 연결 (docs/chromite/platform-startup.md)
 * - 2026-11-07 모델 알림 처리기({@link SwingModelNotifications}) 등록: 연결 감시가 시작되기 전에 등록해 유휴 연결 닫힘 등이
 *   메인 창 알림으로 보이게 함 (docs/chromite/idle-transactions.md "모델 알림")
 * - 2026-11-08 저장된 밀도(촘촘한 모드, {@link UiDensity})가 다르면 주 창을 열기 전에 테마를 다시 설치함 (docs/chromite/design-spec.md "적용 현황 — 촘촘한 모드")
 * - 2026-11-08 macOS: 런처가 첫(AppKit) 스레드에서 부르면 본문({@code run})을 다른 스레드에서 돌리고 첫 스레드는 AppKit 실행 루프를
 *   돌린다({@link MacOSMainThread}). 첫 스레드가 Swing을 기다려 창이 뜨지 않던 교착 수정 (docs/chromite/platform-startup.md)
 * - 2026-11-08 시스템 절전 알림을 연결 감시에 넘김(SWT {@code systemSleepListener}, JDK가 지원하는 macOS만) (docs/chromite/connection-lifecycle.md "절전")
 * - 2026-11-08 SWT 앱처럼 시작할 때 IP 스택·주소 선호와 네트워크 디버그({@link #configureNetwork}), 클라이언트 시간대
 *   ({@code TimezoneRegistry.overrideTimezone})를 적용. 두 경로(Windows는 첫 스레드, macOS는 {@link MacOSMainThread}의 작업 스레드) 모두
 *   {@code run} 안이라 AWT·연결보다 앞이다 (docs/chromite/platform-startup.md)
 * - 2026-11-08 시작 화면: Look and Feel 설치 직후 {@link StartupSplash}를 띄우고 런처 스플래시를 내림({@code applicationRunning}을
 *   메인 창 직전에서 이 자리로 옮김, Windows에서는 런처 창을 만든 이 스레드만 내릴 수 있다). 스플래시는 메인 창이나 시작 대화상자가
 *   열리면 닫히고, 시작이 어떻게 끝나도 닫힌다({@code runWithSplash}). 프로세스 시작부터 단계별 시간을 디버그 로그 한 줄로 남김
 *   (docs/chromite/branding.md "시작 화면")
 * - 2026-11-08 업데이트 진행 창: 업데이트 뒤 첫 시작이면 시작 화면 대신 그 창을 띄우고({@link UpdateProgress#openFirstStart}), 시작이
 *   끝나거나 실패하면 그 창이 알림. 끝날 때 적용에 그 창을 넘김 (docs/chromite/auto-update.md "업데이트 진행 창")
 * - 2026-11-11 작업 공간 잠금 전 테마를 라이트 대신 운영체제 설정의 테마로 설치하고, 메인 창을 열 때 운영체제 설정 바뀜을 따라가기 시작
 *   (앱 변경 #38, docs/chromite/swing-options.md "테마")
 * - 2026-11-12 저장된 UI 글꼴 선택({@code UiFont})이 다른 글꼴이면 주 창을 열기 전에 테마를 다시 설치함 (앱 변경 #43,
 *   docs/chromite/design-spec.md "적용 현황 — 글자 선명도와 대비")
 * - 2026-11-12 시작 화면의 자리({@link StartupSplash#getPlacement})를 디버그 로그가 열린 뒤 남김: 시작 화면은 로그보다 먼저 열린다
 *   (앱 변경 #52, docs/chromite/branding.md "자리")
 * - 2026-11-12 UI 글꼴 선택이 없어져(운영체제 글꼴 하나) 주 창을 열기 전의 글꼴 비교를 지움 (앱 변경 #67,
 *   docs/chromite/design-spec.md "적용 현황 — 운영체제 UI 글꼴 13") */
public class ChromiteSwingApplication extends DesktopApplicationImpl {

    private static final Log log = Log.getLog(ChromiteSwingApplication.class);

    // Same default project as the SWT application, so both frontends work on the same connections
    private static final String DEFAULT_PROJECT_NAME = "General";

    // JDK system properties of the network settings (configureNetwork)
    private static final String PROP_PREFER_IPV4_STACK = "java.net.preferIPv4Stack"; //$NON-NLS-1$
    private static final String PROP_PREFER_IPV6_ADDRESSES = "java.net.preferIPv6Addresses"; //$NON-NLS-1$
    private static final String PROP_NET_DEBUG = "javax.net.debug"; //$NON-NLS-1$

    private final CountDownLatch mainWindowClosed = new CountDownLatch(1);
    private volatile MainFrame mainFrame;
    /**
     * The application was stopped from outside ({@link #stop}), not closed by the user: no update is applied then.
     */
    private volatile boolean stopRequested;

    private OutputStream debugWriter;
    private PrintStream oldSystemOut;
    private PrintStream oldSystemErr;

    @NotNull
    @Override
    public Object start(IApplicationContext context) throws Exception {
        if (MacOSMainThread.isAppKitMainThread()) {
            // macOS: this thread must serve AppKit for AWT, so the application runs on another one (never returns)
            return MacOSMainThread.runApplication(() -> runWithSplash(context));
        }
        return runWithSplash(context);
    }

    /**
     * {@link #run} with the startup splash closed on every way out: a splash left on screen after a failed start would
     * keep the AWT event thread, and with it the process, alive.
     */
    @NotNull
    private Object runWithSplash(@NotNull IApplicationContext context) throws Exception {
        try {
            return run(context);
        } finally {
            StartupSplash.close();
            UpdateProgress.endFirstStart();
        }
    }

    @NotNull
    private Object run(@NotNull IApplicationContext context) throws Exception {
        StartupTimes times = new StartupTimes();
        installLookAndFeel();
        times.mark("look and feel"); //$NON-NLS-1$

        // The update helper replaces the installation now: a start would use files being replaced
        if (UpdateService.isApplyRunning()) {
            // Takes the launcher's splash down, which would stay over the message
            context.applicationRunning();
            showStartupMessage(UpdateMessages.update_applying_message);
            return EXIT_OK;
        }
        // The first start after an update shows the update window instead of the splash (its "Start Chromite" step).
        // The Swing splash is on screen before the launcher's one goes (applicationRunning), so there is no gap. On Windows
        // the launcher made its splash window on this thread, and only this thread can destroy it.
        if (!UpdateProgress.openFirstStart()) {
            StartupSplash.open();
        }
        times.mark("splash"); //$NON-NLS-1$
        context.applicationRunning();

        Location instanceLoc = Platform.getInstanceLocation();
        // Set at launch only by -data: nothing has read the instance preferences yet (see ChromiteSwingActivator)
        boolean defaultWorkspace = !instanceLoc.isSet();
        if (!lockWorkspace(instanceLoc)) {
            return EXIT_OK;
        }
        setWorkspacePath(RuntimeUtils.getLocalPathFromURL(instanceLoc.getURL()));
        // Before the first read of the instance preferences, which are loaded from the copied files
        String migrationResult = defaultWorkspace ? WorkspaceLocationMigration.migrate(getWorkspacePath()) : null;
        ChromiteSwingActivator.getInstance().initPreferences();

        initializeApplicationServices();
        initDebugWriter();
        try {
            log.debug(GeneralUtils.getProductName() + " (Swing) is starting");
            log.debug("Java version: " + System.getProperty(StandardConstants.ENV_JAVA_VERSION) +
                " by " + System.getProperty(StandardConstants.ENV_JAVA_VENDOR));
            log.debug("Instance path: '" + instanceLoc.getURL() + "'");
            String splashPlacement = StartupSplash.getPlacement();
            if (splashPlacement != null) {
                log.debug("Startup splash at " + splashPlacement);
            }
            if (migrationResult != null) {
                log.info(migrationResult);
            }
            useWindowsTrustStore();
            configureNetwork();

            ChromiteSwingPlatform platform;
            try {
                platform = DBWorkbench.getPlatform(ChromiteSwingPlatform.class);
                times.mark("platform"); //$NON-NLS-1$
            } catch (Throwable e) {
                log.error("Error initializing platform", e);
                showStartupError(CoreMessages.app_error_init, ErrorText.describe(e));
                return EXIT_OK;
            }
            // ChromiteApplication.start: the client time zone of the settings (java.client.timezone) before any date is formatted;
            // it reads the platform's preference store, so it runs once the platform exists
            TimezoneRegistry.overrideTimezone();
            // Before the connection monitor starts: its notices (idle connection closed) go to the main window, not System.out
            ChromiteNotifications.setHandler(new SwingModelNotifications());
            // Activates plugin services and the connection monitor; the SWT workbench window advisor does the same
            platform.postInitialize();
            times.mark("services"); //$NON-NLS-1$

            // Counted here, off the EDT: the first count loads the connection registries of all projects
            String statusText = MainFrame.describeWorkspace(platform.getWorkspace());
            times.mark("workspace"); //$NON-NLS-1$
            UpdateProgress.started();

            // The splash closes when the main window opens (StartupSplash)
            SwingUtilities.invokeAndWait(() -> openMainWindow(statusText));
            times.mark("main window"); //$NON-NLS-1$
            log.debug("Startup times (ms after the process started): " + times);
            platform.setWorkbenchStarted(true);
            mainWindowClosed.await();
            platform.setWorkbenchStarted(false);
            log.debug("Main window closed");
            if (!stopRequested) {
                // After the normal exit path (unsaved editors, open transactions): the helper waits until this process ends
                UpdateService.getInstance().applyAtExit(UpdateUi.isRestartRequested(), UpdateProgress.exitWindow(mainFrame));
            }
            return EXIT_OK;
        } finally {
            shutdown();
        }
    }

    @Override
    public void stop() {
        stopRequested = true;
        SwingUtilities.invokeLater(() -> {
            MainFrame frame = mainFrame;
            if (frame != null) {
                frame.dispose();
            }
        });
        mainWindowClosed.countDown();
    }

    private static void installLookAndFeel() throws InterruptedException, InvocationTargetException {
        SwingUtilities.invokeAndWait(() -> {
            // The saved theme can't be read yet (the workspace is not locked); see openMainWindow. The theme of the
            // operating system is the default choice, so most starts need no second install there
            ChromiteTheme.forSystem().install(false);
            // Dialogs without a parent window (startup errors, SwingPlatformUI before the main window exists)
            // are owned by this shared frame and take its icon instead of the Java runtime's one
            JOptionPane.getRootFrame().setIconImages(ChromiteIcons.getWindowIcons());
        });
    }

    /**
     * Locks the workspace like the SWT application does. Without {@code -data} the default workspace
     * ({@code ChromiteData/workspace6} in the user's application data folder, the SWT frontend's default) is used,
     * whatever the current folder is.
     */
    private boolean lockWorkspace(@NotNull Location instanceLoc) {
        Path workspacePath = getWorkspacePath();
        try {
            if (!instanceLoc.isSet()) {
                if (instanceLoc.set(workspacePath.toUri().toURL(), true)) {
                    return true;
                }
            } else {
                workspacePath = RuntimeUtils.getLocalPathFromURL(instanceLoc.getURL());
                if (!instanceLoc.isLocked() && instanceLoc.lock()) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.error("Error locking workspace", e);
        }
        // Phase B: offer "ignore lock" / workspace switching like ChromiteApplication.setDefaultWorkspacePath
        showStartupError(
            CoreMessages.app_cant_lock_title,
            NLS.bind(CoreMessages.app_cant_lock_message, workspacePath, GeneralUtils.getProductName()));
        return false;
    }

    private void openMainWindow(@NotNull String statusText) {
        ChromiteTheme savedTheme = ChromiteTheme.getSaved();
        boolean savedCompact = UiDensity.isSavedCompact();
        if (savedTheme != ChromiteTheme.getCurrent() || savedCompact != UiDensity.isCompact()) {
            // Only the shared root frame of parentless dialogs exists yet
            UiDensity.setCompact(savedCompact);
            savedTheme.install(true);
        }
        ChromiteTheme.followSystem();
        MainFrame frame = new MainFrame(GeneralUtils.getProductName(), statusText);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                mainWindowClosed.countDown();
            }
        });
        mainFrame = frame;
        installDesktopHandlers(frame);
        frame.setVisible(true);
        // The first start after an update: "The update is done", then its window closes
        UpdateProgress.mainShown();
    }

    /**
     * Makes the default SSL trust store the Windows certificate store ({@code WINDOWS-ROOT}) when the model preference
     * {@link ModelPreferences#PROP_USE_WIN_TRUST_STORE_TYPE} (default on, Windows only) is on, as the SWT application did.
     * Called before the platform is created: the JDK reads {@code javax.net.ssl.trustStoreType} once, when the first
     * default SSL context is made (connections, the update check). A trust store set on the command line wins.
     */
    private void useWindowsTrustStore() {
        if (CommonUtils.isEmpty(System.getProperty(GeneralUtils.PROP_TRUST_STORE))
            && CommonUtils.isEmpty(System.getProperty(GeneralUtils.PROP_TRUST_STORE_TYPE))
            && RuntimeUtils.isWindows()
            && getPreferenceStore().getBoolean(ModelPreferences.PROP_USE_WIN_TRUST_STORE_TYPE)
        ) {
            System.setProperty(GeneralUtils.PROP_TRUST_STORE_TYPE, GeneralUtils.VALUE_TRUST_STORE_TYPE_WINDOWS);
        }
        log.debug("SSL trust store type: " + CommonUtils.notEmpty(System.getProperty(GeneralUtils.PROP_TRUST_STORE_TYPE))
            + " (default " + KeyStore.getDefaultType() + ")");
    }

    /**
     * The network settings of ChromiteApplication.initializeApplication: the preferred IP stack and addresses
     * ({@link ModelPreferences.IPType}, Auto leaves the JDK default) and the SSL/TLS debug output. Set before anything opens
     * a socket: the JDK reads {@code java.net.preferIPv4Stack} and {@code java.net.preferIPv6Addresses} once.
     */
    private static void configureNetwork() {
        ModelPreferences.IPType stack = ModelPreferences.IPType.getPreferredStack();
        if (stack != ModelPreferences.IPType.AUTO) {
            System.setProperty(PROP_PREFER_IPV4_STACK, String.valueOf(stack == ModelPreferences.IPType.IPV4));
        }
        ModelPreferences.IPType address = ModelPreferences.IPType.getPreferredAddresses();
        if (address != ModelPreferences.IPType.AUTO) {
            System.setProperty(PROP_PREFER_IPV6_ADDRESSES, String.valueOf(address == ModelPreferences.IPType.IPV6));
        }
        if (ModelPreferences.getPreferences().getBoolean(ModelPreferences.PROP_DEBUG_NETWORK_CONNECTIONS)) {
            System.setProperty(PROP_NET_DEBUG, "all"); //$NON-NLS-1$
        }
        log.debug("Network: IP stack " + stack + ", addresses " + address);
    }

    /**
     * Connects the macOS application menu (Quit, About, Preferences) to the main window. The JDK's default quit
     * calls {@code System.exit}, which would skip the save question and {@link ExitTransactionCheck}: Quit sends the
     * main window the same closing event as its close button and is cancelled; if the user agreed to exit, the window
     * is gone and the application ends through its normal path ({@link #start}). Each handler is installed only where
     * the platform supports it, so nothing changes on Windows. Under the Eclipse launcher AWT does not own the macOS
     * application and builds no application menu: {@link MacOSAppMenu} does (docs/chromite/platform-startup.md).
     * The system sleep notice goes to the connection monitor (docs/chromite/connection-lifecycle.md "절전").
     */
    private static void installDesktopHandlers(@NotNull MainFrame frame) {
        if (!Desktop.isDesktopSupported()) {
            return;
        }
        Desktop desktop = Desktop.getDesktop();
        if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
            desktop.setQuitHandler((event, response) -> SwingUtilities.invokeLater(() -> {
                // Our own exit path ends the process; the JDK must not exit before it has run
                response.cancelQuit();
                requestQuit(frame);
            }));
        }
        if (desktop.isSupported(Desktop.Action.APP_ABOUT)) {
            desktop.setAboutHandler(event -> SwingUtilities.invokeLater(() -> AboutDialog.open(frame)));
        }
        if (desktop.isSupported(Desktop.Action.APP_PREFERENCES)) {
            desktop.setPreferencesHandler(event -> SwingUtilities.invokeLater(() -> OptionsDialog.open(frame)));
        }
        if (desktop.isSupported(Desktop.Action.APP_EVENT_SYSTEM_SLEEP)) {
            // Close on sleep (connection.closeOnSleep): the connection monitor closes the connections that are not in
            // use when it sees the sleep mode (SWT ApplicationWorkbenchAdvisor.systemSleepListener). The JDK reports
            // sleep on macOS only; elsewhere only the wake-up is seen, from the gap between the monitor's checks.
            desktop.addAppEventListener(new SystemSleepListener() {
                @Override
                public void systemAboutToSleep(SystemSleepEvent e) {
                    OperationSystemState.toggleSleepMode(true);
                }

                @Override
                public void systemAwoke(SystemSleepEvent e) {
                    OperationSystemState.toggleSleepMode(false);
                }
            });
        }
        if (MacOSMainThread.isActive()) {
            // AWT does not build the application menu for an NSApplication it did not create (the launcher's)
            MacOSAppMenu.install(GeneralUtils.getProductName(), () -> AboutDialog.open(frame), () -> OptionsDialog.open(frame),
                () -> requestQuit(frame));
        }
    }

    /**
     * Quit from the macOS application (menu, Apple event, Dock): the main window's close button path. Call in the EDT.
     */
    private static void requestQuit(@NotNull MainFrame frame) {
        if (frame.isDisplayable()) {
            frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
        }
    }

    private static void showStartupMessage(@NotNull String message) {
        try {
            SwingUtilities.invokeAndWait(() ->
                JOptionPane.showMessageDialog(null, message, GeneralUtils.getProductName(), JOptionPane.INFORMATION_MESSAGE));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException e) {
            log.error("Error showing startup message", e.getTargetException());
        }
    }

    private static void showStartupError(@NotNull String title, @Nullable String message) {
        // The first start after an update: its window says it, with the log folder and the way back
        if (UpdateProgress.showStartFailure(message, null)) {
            return;
        }
        try {
            SwingUtilities.invokeAndWait(() ->
                JOptionPane.showMessageDialog(null, message, title, JOptionPane.ERROR_MESSAGE));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException e) {
            log.error("Error showing startup error", e.getTargetException());
        }
    }

    private void shutdown() {
        log.debug(GeneralUtils.getProductName() + " (Swing) is stopping");
        try {
            if (DBWorkbench.isPlatformStarted() && DBWorkbench.getPlatform() instanceof ChromiteSwingPlatform platform) {
                platform.dispose();
            }
        } catch (Throwable e) {
            log.error("Error disposing platform", e);
        } finally {
            mainFrame = null;
            log.debug(GeneralUtils.getProductName() + " (Swing) shutdown completed");
            stopDebugWriter();
        }
    }

    /**
     * Copies stdout/stderr (where {@link Log} writes debug messages) to {@code .metadata/chromite-debug.log},
     * as the SWT application does.
     */
    private void initDebugWriter() {
        // LogOutputStream reads its rotation limits from the main bundle preferences. The SWT product sets these
        // defaults in DesktopPreferencesInitializer (com.seizia.chromite.core), which the Swing product does not have;
        // without them the limits are 0 and the log is rotated away on every write.
        DBPPreferenceStore modelPreferences = ModelPreferences.getPreferences();
        PrefUtils.setDefaultPreferenceValue(modelPreferences, LogOutputStream.LOGS_MAX_FILE_SIZE, LogOutputStream.DEFAULT_MAX_LOG_SIZE);
        PrefUtils.setDefaultPreferenceValue(modelPreferences, LogOutputStream.LOGS_MAX_FILES_COUNT, LogOutputStream.DEFAULT_MAX_LOG_FILES_COUNT);

        Path debugLogFile = GeneralUtils.getMetadataFolder(getWorkspacePath()).resolve(DBConstants.DEBUG_LOG_FILE_NAME);
        try {
            Files.createDirectories(debugLogFile.getParent());
            debugWriter = new LogOutputStream(debugLogFile.toFile());
            oldSystemOut = System.out;
            oldSystemErr = System.err;
            System.setOut(new PrintStream(new TeeOutputStream(debugWriter, oldSystemOut), true));
            System.setErr(new PrintStream(new TeeOutputStream(debugWriter, oldSystemErr), true));
        } catch (IOException e) {
            e.printStackTrace(System.err);
        }
    }

    private void stopDebugWriter() {
        if (oldSystemOut != null) {
            System.setOut(oldSystemOut);
            oldSystemOut = null;
        }
        if (oldSystemErr != null) {
            System.setErr(oldSystemErr);
            oldSystemErr = null;
        }
        if (debugWriter != null) {
            IOUtils.close(debugWriter);
            debugWriter = null;
        }
    }

    @NotNull
    @Override
    public Class<? extends DBPPlatform> getPlatformClass() {
        return ChromiteSwingPlatform.class;
    }

    @Override
    public Class<? extends DBPPlatformUI> getPlatformUIClass() {
        return SwingPlatformUI.class;
    }

    @Nullable
    @Override
    public String getDefaultProjectName() {
        return DEFAULT_PROJECT_NAME;
    }

    @Override
    public boolean isCommunity() {
        return true;
    }

    @NotNull
    @Override
    public DBPPreferenceStore getPreferenceStore() {
        return ChromiteSwingActivator.getInstance().getPreferences();
    }

    /**
     * Milliseconds from the process start (the native launcher, before the JVM) to each startup step, for one debug line
     * that shows where the startup time goes (docs/chromite/branding.md "시작 화면").
     */
    private static final class StartupTimes {
        @Nullable
        private final Instant processStart = ProcessHandle.current().info().startInstant().orElse(null);
        private final StringBuilder steps = new StringBuilder();

        StartupTimes() {
            mark("application"); //$NON-NLS-1$
        }

        void mark(@NotNull String step) {
            if (processStart != null) {
                steps.append(steps.isEmpty() ? "" : ", ").append(step).append(' ') //$NON-NLS-1$ //$NON-NLS-2$
                    .append(Duration.between(processStart, Instant.now()).toMillis());
            }
        }

        @Override
        public String toString() {
            return steps.isEmpty() ? "unknown (no process start time)" : steps.toString(); //$NON-NLS-1$
        }
    }

    private static final class TeeOutputStream extends OutputStream {
        private final OutputStream first;
        private final OutputStream second;

        TeeOutputStream(@NotNull OutputStream first, @NotNull OutputStream second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public void write(int b) throws IOException {
            first.write(b);
            second.write(b);
        }

        @Override
        public void write(@NotNull byte[] b, int off, int len) throws IOException {
            first.write(b, off, len);
            second.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            first.flush();
            second.flush();
        }
    }
}
