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

import com.seizia.chromite.DBDatabaseException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPErrorAssistant;
import com.seizia.chromite.model.connection.DBPDriver;
import com.seizia.chromite.model.exec.DBCDriverLicenseException;
import com.seizia.chromite.model.exec.DBExecUtils;
import com.seizia.chromite.registry.driver.DriverDescriptor;
import com.seizia.chromite.ui.swing.internal.EditorMessages;
import com.seizia.chromite.ui.swing.internal.LifecycleMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * The database errors that get their own dialog instead of the plain error box ({@link SwingPlatformUI#showError}):
 * a lost connection (Reconnect), a driver whose class is not found (Edit Driver), and an inactive driver license.
 *
 * 설계: SWT {@code DesktopUI.showDatabaseError}가 오류 종류({@link DBExecUtils#discoverErrorType})를 보고 연결 끊김이면
 * {@code ConnectionRecoverDialog}(다시 연결), 드라이버 클래스가 없으면 드라이버 설정 안내, 드라이버 라이선스 오류면 따로 된 오류 창을 띄웠다.
 * Swing은 모두 일반 오류 창이었다(docs/chromite/swt-parity/A-platform.md A-C15, A-U3).
 * <ul>
 *     <li>연결 끊김: 연결이 열려 있을 때만(연결 중에 난 오류는 일반 오류). 단추는 다시 연결 / 닫기, 기본은 닫기다(세션을 바꾸는 동작이라
 *     취소 쪽이 기본, 사용자 규칙). 다시 연결은 {@link ConnectionCommands#invalidate}(무효화·다시 연결)로 가서 열린 트랜잭션을 먼저 묻는다.</li>
 *     <li>다시 연결이 실패하면(SWT {@code ConnectionLostDialog}) 끊기 / 다시 시도 / 닫기를 묻는다. 끊기는 {@link ConnectionCommands#disconnect}로
 *     가서 열린 트랜잭션 질문을 거친다. SWT는 끊기가 기본이었고 창을 닫아도 끊었지만, Chromite는 닫기가 기본이고 닫으면 아무것도 하지 않는다.</li>
 *     <li>모든 대화상자에 원래 오류의 접힌 상세와 복사 단추가 있다({@link ErrorDialog#createContent}).</li>
 *     <li>쿼리 창의 실행 오류는 이 경로를 지나지 않는다(Messages 탭에 쓰고, 끊긴 연결은 실행 복구와 그 알림이 맡는다, docs/chromite/transactions.md).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/connection-lifecycle.md "연결 끊김·드라이버 오류 대화상자"). 연결 실패의 드라이버 클래스 없음은
 *   {@link #showConnectError}: 연결 상태(status)에는 데이터 원본을 가진 연결 예외가 남지 않아 SWT처럼 오류만 보고는 알 수 없었다(실행으로 확인)
 */
final class DatabaseErrorDialogs {

    private static final Log log = Log.getLog(DatabaseErrorDialogs.class);

    private DatabaseErrorDialogs() {
    }

    /**
     * Shows the dialog of a database error that has one. Call on the EDT.
     *
     * @param title   the caller's title, used for errors that keep it
     * @param message the caller's message, shown above the error
     * @return true when a dialog was shown; false for other errors (the caller shows the plain error box)
     */
    static boolean show(@Nullable String title, @Nullable String message, @NotNull Throwable error) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = error; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof DBCDriverLicenseException licenseError) {
                showDriverLicense(licenseError);
                return true;
            }
        }
        DBPErrorAssistant.ErrorType errorType = DBExecUtils.discoverErrorType(null, error);
        if (errorType == DBPErrorAssistant.ErrorType.CONNECTION_LOST) {
            DBPDataSource dataSource = findDataSource(error);
            if (dataSource == null || dataSource.getContainer().getDataSource() == null) {
                // An error while connecting: the plain error box
                return false;
            }
            showConnectionLost(dataSource.getContainer(), message, error, false);
            return true;
        }
        if (errorType == DBPErrorAssistant.ErrorType.DRIVER_CLASS_MISSING) {
            DBPDataSource dataSource = findDataSource(error);
            if (dataSource == null) {
                return false;
            }
            showDriverConfiguration(title, message, dataSource.getContainer().getDriver(), error);
            return true;
        }
        return false;
    }

    /**
     * A failed connect of the connection: a driver class that is not found, or an inactive driver license, gets its
     * dialog. The connect status keeps only the causes of the connect exception (the one that names the data source,
     * {@code GeneralUtils.makeExceptionStatus}), so the connection comes from the caller. Waits for the dialog when
     * called outside the event dispatch thread.
     *
     * @return true when a dialog was shown; false for other errors (the caller shows the plain error box)
     */
    static boolean showConnectError(@NotNull DBPDataSourceContainer container, @NotNull String title, @Nullable String message, @NotNull IStatus status) {
        Throwable classMissing = null;
        for (Throwable error : collectExceptions(status, new ArrayList<>())) {
            Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Throwable cause = error; cause != null && visited.add(cause); cause = cause.getCause()) {
                if (cause instanceof DBCDriverLicenseException licenseError) {
                    runOnEdt(() -> showDriverLicense(licenseError));
                    return true;
                }
                if (classMissing == null && cause instanceof ClassNotFoundException) {
                    classMissing = error;
                }
            }
        }
        if (classMissing == null) {
            return false;
        }
        Throwable shown = classMissing;
        runOnEdt(() -> showDriverConfiguration(title, message, container.getDriver(), shown));
        return true;
    }

    @NotNull
    private static List<Throwable> collectExceptions(@NotNull IStatus status, @NotNull List<Throwable> result) {
        if (status.getException() != null) {
            result.add(status.getException());
        }
        for (IStatus child : status.getChildren()) {
            collectExceptions(child, result);
        }
        return result;
    }

    private static void runOnEdt(@NotNull Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException e) {
            log.error("Error showing a database error dialog", e.getTargetException());
        }
    }

    /**
     * The connection was lost (SWT {@code ConnectionRecoverDialog}), or reconnecting it failed (SWT
     * {@code ConnectionLostDialog}). Call on the EDT.
     *
     * @param reconnectFailed true after Invalidate/Reconnect failed: offers Disconnect and Retry; else Reconnect
     */
    static void showConnectionLost(
        @NotNull DBPDataSourceContainer container,
        @Nullable String message,
        @NotNull Throwable error,
        boolean reconnectFailed
    ) {
        String text = reconnectFailed
            ? NLS.bind(LifecycleMessages.lost_reconnect_failed_message, container.getName())
            : NLS.bind(LifecycleMessages.lost_message, container.getName());
        String cleaned = ErrorText.clean(message);
        if (!CommonUtils.isEmpty(cleaned)) {
            text = cleaned + "\n\n" + text; //$NON-NLS-1$
        }
        text += "\n\n" + ErrorText.describe(error); //$NON-NLS-1$
        Object[] options = reconnectFailed
            ? new Object[]{LifecycleMessages.lost_disconnect, LifecycleMessages.lost_retry, EditorMessages.editor_common_close}
            : new Object[]{LifecycleMessages.lost_reconnect, EditorMessages.editor_common_close};
        int answer = JOptionPane.showOptionDialog(SwingPlatformUI.getDialogParent(), ErrorDialog.createContent(text, error),
            NLS.bind(LifecycleMessages.lost_title, container.getName()), JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
            null, options, options[options.length - 1]);
        Component parent = SwingPlatformUI.getDialogParent();
        if (reconnectFailed) {
            if (answer == 0) {
                // Asks about open transactions first, like any disconnect
                ConnectionCommands.disconnect(container);
            } else if (answer == 1) {
                ConnectionCommands.invalidate(parent, container);
            }
        } else if (answer == 0) {
            ConnectionCommands.invalidate(parent, container);
        }
    }

    /**
     * The driver class was not found: wrong class name or missing files (SWT {@code DriverEditHelpers.showBadConfigDialog}).
     */
    private static void showDriverConfiguration(@Nullable String title, @Nullable String message, @NotNull DBPDriver driver, @NotNull Throwable error) {
        String text = NLS.bind(LifecycleMessages.driver_config_message, driver.getName());
        String cleaned = ErrorText.clean(message);
        if (!CommonUtils.isEmpty(cleaned)) {
            text = cleaned + "\n\n" + text; //$NON-NLS-1$
        }
        text += "\n\n" + ErrorText.describe(error); //$NON-NLS-1$
        boolean editable = driver instanceof DriverDescriptor;
        Object[] options = editable
            ? new Object[]{LifecycleMessages.driver_config_edit, EditorMessages.editor_common_close}
            : new Object[]{EditorMessages.editor_common_close};
        Component parent = SwingPlatformUI.getDialogParent();
        int answer = JOptionPane.showOptionDialog(parent, ErrorDialog.createContent(text, error),
            CommonUtils.isEmpty(title) ? LifecycleMessages.driver_config_title : title, JOptionPane.DEFAULT_OPTION,
            JOptionPane.ERROR_MESSAGE, null, options, options[options.length - 1]);
        if (editable && answer == 0) {
            DriverEditorDialog.openEdit(parent instanceof Window window ? window : null, (DriverDescriptor) driver);
        }
    }

    private static void showDriverLicense(@NotNull DBCDriverLicenseException error) {
        ErrorDialog.show(SwingPlatformUI.getDialogParent(), LifecycleMessages.driver_license_title,
            NLS.bind(LifecycleMessages.driver_license_message, error.getContainer().getDriver().getName()), error);
    }

    @Nullable
    private static DBPDataSource findDataSource(@NotNull Throwable error) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = error; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof DBDatabaseException databaseException && databaseException.getDataSource() != null) {
                return databaseException.getDataSource();
            }
        }
        return null;
    }
}
