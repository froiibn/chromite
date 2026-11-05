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

import org.eclipse.core.runtime.Platform;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.osgi.service.datalocation.Location;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.LogOutputStream;
import org.jkiss.dbeaver.ModelPreferences;
import org.jkiss.dbeaver.model.DBConstants;
import org.jkiss.dbeaver.model.app.DBPPlatform;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.model.rcp.DesktopApplicationImpl;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.runtime.ui.DBPPlatformUI;
import org.jkiss.dbeaver.utils.GeneralUtils;
import org.jkiss.dbeaver.utils.PrefUtils;
import org.jkiss.dbeaver.utils.RuntimeUtils;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.IOUtils;
import org.jkiss.utils.StandardConstants;

import javax.swing.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/**
 * Chromite Swing 프런트엔드의 Equinox 애플리케이션 진입점.
 *
 * 설계: 헤드리스 앱({@code DBeaverHeadlessApplication})처럼 {@link DesktopApplicationImpl}을 상속해
 * SWT 없이 모델 계층을 띄운다. 작업 공간 잠금과 디버그 로그 처리는 SWT 앱({@code DBeaverApplication})의
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
 */
public class ChromiteSwingApplication extends DesktopApplicationImpl {

    private static final Log log = Log.getLog(ChromiteSwingApplication.class);

    // Same default project as the SWT application, so both frontends work on the same connections
    private static final String DEFAULT_PROJECT_NAME = "General";

    private final CountDownLatch mainWindowClosed = new CountDownLatch(1);
    private volatile MainFrame mainFrame;

    private OutputStream debugWriter;
    private PrintStream oldSystemOut;
    private PrintStream oldSystemErr;

    @NotNull
    @Override
    public Object start(IApplicationContext context) throws Exception {
        installLookAndFeel();

        Location instanceLoc = Platform.getInstanceLocation();
        if (!lockWorkspace(instanceLoc)) {
            return EXIT_OK;
        }
        setWorkspacePath(RuntimeUtils.getLocalPathFromURL(instanceLoc.getURL()));

        initializeApplicationServices();
        initDebugWriter();
        try {
            log.debug(GeneralUtils.getProductName() + " (Swing) is starting");
            log.debug("Java version: " + System.getProperty(StandardConstants.ENV_JAVA_VERSION) +
                " by " + System.getProperty(StandardConstants.ENV_JAVA_VENDOR));
            log.debug("Instance path: '" + instanceLoc.getURL() + "'");

            ChromiteSwingPlatform platform;
            try {
                platform = DBWorkbench.getPlatform(ChromiteSwingPlatform.class);
            } catch (Throwable e) {
                log.error("Error initializing platform", e);
                showStartupError("Error initializing platform", CommonUtils.getAllExceptionMessages(e));
                return EXIT_OK;
            }
            // Activates plugin services and the connection monitor; the SWT workbench window advisor does the same
            platform.postInitialize();

            String statusText = describeWorkspace(platform);
            context.applicationRunning();

            SwingUtilities.invokeAndWait(() -> openMainWindow(statusText));
            platform.setWorkbenchStarted(true);
            mainWindowClosed.await();
            platform.setWorkbenchStarted(false);
            log.debug("Main window closed");
            return EXIT_OK;
        } finally {
            shutdown();
        }
    }

    @Override
    public void stop() {
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
     * (shared with the SWT frontend) is used.
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
        // Phase B: offer "ignore lock" / workspace switching like DBeaverApplication.setDefaultWorkspacePath
        showStartupError(
            "Can't lock workspace",
            "Can't lock workspace at " + workspacePath + ".\n" +
                "It seems that another " + GeneralUtils.getProductName() + " instance is running.");
        return false;
    }

    @NotNull
    private static String describeWorkspace(@NotNull DBPPlatform platform) {
        int connections = 0;
        for (DBPProject project : platform.getWorkspace().getProjects()) {
            connections += project.getDataSourceRegistry().getDataSources().size();
        }
        return connections + (connections == 1 ? " connection" : " connections") + " in workspace";
    }

    private void openMainWindow(@NotNull String statusText) {
        ChromiteTheme savedTheme = ChromiteTheme.getSaved();
        if (savedTheme != ChromiteTheme.getCurrent()) {
            // Only the shared root frame of parentless dialogs exists yet
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
        frame.setVisible(true);
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
     * Copies stdout/stderr (where {@link Log} writes debug messages) to {@code .metadata/dbeaver-debug.log},
     * as the SWT application does.
     */
    private void initDebugWriter() {
        // LogOutputStream reads its rotation limits from the main bundle preferences. The SWT product sets these
        // defaults in DesktopPreferencesInitializer (org.jkiss.dbeaver.core), which the Swing product does not have;
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
