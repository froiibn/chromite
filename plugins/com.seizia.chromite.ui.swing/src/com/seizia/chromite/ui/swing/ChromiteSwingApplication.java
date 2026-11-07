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
import com.seizia.chromite.runtime.ui.DBPPlatformUI;
import com.seizia.chromite.utils.GeneralUtils;
import com.seizia.chromite.utils.PrefUtils;
import com.seizia.chromite.utils.RuntimeUtils;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.IOUtils;
import org.jkiss.utils.StandardConstants;

import javax.swing.*;
import java.awt.Desktop;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
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
 * - 2026-11-08 SWT 앱처럼 시작할 때 IP 스택·주소 선호와 네트워크 디버그({@link #configureNetwork}), 클라이언트 시간대
 *   ({@code TimezoneRegistry.overrideTimezone})를 적용 (docs/chromite/platform-startup.md)
 */
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
        installLookAndFeel();

        // The update helper replaces the installation now: a start would use files being replaced
        if (UpdateService.isApplyRunning()) {
            showStartupMessage(UpdateMessages.update_applying_message);
            return EXIT_OK;
        }

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
            if (migrationResult != null) {
                log.info(migrationResult);
            }
            useWindowsTrustStore();
            configureNetwork();

            ChromiteSwingPlatform platform;
            try {
                platform = DBWorkbench.getPlatform(ChromiteSwingPlatform.class);
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

            // Counted here, off the EDT: the first count loads the connection registries of all projects
            String statusText = MainFrame.describeWorkspace(platform.getWorkspace());
            context.applicationRunning();

            SwingUtilities.invokeAndWait(() -> openMainWindow(statusText));
            platform.setWorkbenchStarted(true);
            mainWindowClosed.await();
            platform.setWorkbenchStarted(false);
            log.debug("Main window closed");
            if (!stopRequested) {
                // After the normal exit path (unsaved editors, open transactions): the helper waits until this process ends
                UpdateService.getInstance().applyAtExit(UpdateUi.isRestartRequested());
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
            // The saved theme can't be read yet (the workspace is not locked); see openMainWindow
            ChromiteTheme.LIGHT.install(false);
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
     * the platform supports it, so nothing changes on Windows. Not run on macOS yet (docs/chromite/platform-startup.md).
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
                if (frame.isDisplayable()) {
                    frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
                }
            }));
        }
        if (desktop.isSupported(Desktop.Action.APP_ABOUT)) {
            desktop.setAboutHandler(event -> SwingUtilities.invokeLater(() -> AboutDialog.open(frame)));
        }
        if (desktop.isSupported(Desktop.Action.APP_PREFERENCES)) {
            desktop.setPreferencesHandler(event -> SwingUtilities.invokeLater(() -> OptionsDialog.open(frame)));
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
