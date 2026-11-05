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

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.access.DBAPasswordChangeInfo;
import org.jkiss.dbeaver.model.connection.DBPAuthInfo;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.model.navigator.fs.DBNPathBase;
import org.jkiss.dbeaver.model.runtime.AbstractJob;
import org.jkiss.dbeaver.model.runtime.DBRProcessDescriptor;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.DBRRunnableWithResult;
import org.jkiss.dbeaver.model.runtime.DBRRunnableWithReturn;
import org.jkiss.dbeaver.model.runtime.LoggingProgressMonitor;
import org.jkiss.dbeaver.model.runtime.load.ILoadService;
import org.jkiss.dbeaver.model.runtime.load.ILoadVisualizer;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.runtime.ui.DBPPlatformUI;
import org.jkiss.dbeaver.utils.GeneralUtils;
import org.jkiss.dbeaver.utils.RuntimeUtils;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 모델 계층의 UI 요청({@link DBPPlatformUI})을 Swing으로 처리한다.
 *
 * 설계: 모델 계층은 오류 표시, 확인 질문, 비밀번호 입력 등을 이 인터페이스로 요청하고, 호출 스레드는 대개
 * 백그라운드 Job이다. 그래서 대화상자는 항상 EDT에서 띄우고, 결과가 필요하면 {@link SwingUtilities#invokeAndWait}로
 * 기다린다(이미 EDT이면 바로 실행). A단계는 {@link JOptionPane} 기반의 단순한 대화상자로 구현했고,
 * B단계에서 진행 표시({@link ProgressDialog})와 비모달 알림({@link NotificationToast})을 더했다.
 * 아직 화면이 없는 기능(객체 선택, 객체 편집기 등)은 {@code ConsoleUserInterface}와 같은 안전한 기본값을
 * 돌려주며 {@code // Phase C:} 주석으로 무엇이 빠졌는지 표시한다.
 * {@code ConsoleUserInterface}를 상속하지 않은 것은, 어떤 메서드가 아직 콘솔 동작인지 이 클래스만 보고 알 수 있게 하기 위해서다.
 *
 * 변경 이력:
 * - 2026-11-06 A단계: 최초 작성 (docs/chromite/swing-frontend.md "구현 현황 — A단계")
 * - 2026-11-06 B단계: 진행 대화상자({@code runWithProgress}, {@code runWithMonitor}, {@code executeWithProgressBlocking}),
 *   비모달 알림({@code showNotification}, {@code showWarningNotification}). 남은 항목은 {@code // Phase C:}로 표시
 *   (docs/chromite/swing-frontend.md "구현 현황 — B단계")
 * - 2026-11-06 D단계: {@code openConnectionEditor}를 범용 연결 대화상자({@link ConnectionDialog})로 구현
 *   (docs/chromite/swing-frontend.md "구현 현황 — D단계")
 */
public class SwingPlatformUI implements DBPPlatformUI {

    private static final Log log = Log.getLog(SwingPlatformUI.class);

    private static final int MESSAGE_WIDTH = 480;
    private static final int MAX_MESSAGE_ROWS = 20;

    /**
     * Called reflectively by {@code AbstractApplication.initializePlatform()}, which looks the method up
     * with {@code getDeclaredMethod}, so it must be declared in this class.
     */
    protected void initialize() {
        // nothing to initialize yet
    }

    //////////////////////////////////////////////////////////
    // Messages

    @Override
    public UserResponse showError(@NotNull String title, @Nullable String message, @NotNull IStatus status) {
        // Phase C: DB-specific handling of DesktopUI (connection lost, driver files missing, details pane)
        showMessage(title, joinText(message, getStatusText(status)), JOptionPane.ERROR_MESSAGE);
        return UserResponse.OK;
    }

    @Override
    public UserResponse showError(@Nullable String title, @Nullable String message, @NotNull Throwable e) {
        // Phase C: DB-specific handling of DesktopUI (connection lost, driver files missing, details pane)
        log.debug(e);
        showMessage(CommonUtils.isEmpty(title) ? "Error" : title,
            joinText(message, CommonUtils.getAllExceptionMessages(e)), JOptionPane.ERROR_MESSAGE);
        return UserResponse.OK;
    }

    @Override
    public UserResponse showError(@NotNull String title, @Nullable String message) {
        showMessage(title, message, JOptionPane.ERROR_MESSAGE);
        return UserResponse.OK;
    }

    @Override
    public void showNotification(@NotNull String title, @Nullable String message, boolean error, @Nullable Runnable feedback) {
        if (error) {
            log.error(title + (message == null ? "" : ": " + message));
        } else {
            log.info(title + (message == null ? "" : ": " + message));
        }
        showToast(title, message, error ? NotificationToast.Kind.ERROR : NotificationToast.Kind.INFO, feedback);
    }

    @Override
    public void showWarningNotification(@NotNull String title, @Nullable String message) {
        log.warn(title + (message == null ? "" : ": " + message));
        showToast(title, message, NotificationToast.Kind.WARNING, null);
    }

    /**
     * Non-modal toast in the main window. Never waits for the EDT, because notifications come from background jobs
     * (a modal dialog would block them). Without a main window (startup, shutdown) the notification is only logged.
     */
    private static void showToast(
        @NotNull String title,
        @Nullable String message,
        @NotNull NotificationToast.Kind kind,
        @Nullable Runnable feedback
    ) {
        SwingUtilities.invokeLater(() -> {
            for (Frame frame : Frame.getFrames()) {
                if (frame instanceof MainFrame mainFrame && mainFrame.isDisplayable()) {
                    mainFrame.showNotification(title, message, kind, feedback);
                    return;
                }
            }
        });
    }

    @Override
    public void showMessageBox(@NotNull String title, @Nullable String message, boolean error) {
        showMessage(title, message, error ? JOptionPane.ERROR_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
    }

    @Override
    public void showWarningMessageBox(@NotNull String title, @Nullable String message) {
        showMessage(title, message, JOptionPane.WARNING_MESSAGE);
    }

    //////////////////////////////////////////////////////////
    // Confirmations and choices

    @Override
    public boolean confirmAction(String title, String message) {
        return confirmAction(title, message, false);
    }

    @Override
    public boolean confirmAction(String title, String message, boolean isWarning) {
        Integer result = runOnEdt(() -> JOptionPane.showConfirmDialog(
            getDialogParent(),
            createMessageComponent(message),
            title,
            JOptionPane.YES_NO_OPTION,
            isWarning ? JOptionPane.WARNING_MESSAGE : JOptionPane.QUESTION_MESSAGE));
        return result != null && result == JOptionPane.YES_OPTION;
    }

    @Override
    public boolean confirmAction(@NotNull String title, @NotNull String message, @NotNull String buttonLabel, boolean isWarning) {
        String cancelLabel = UIManager.getString("OptionPane.cancelButtonText");
        Object[] options = {buttonLabel, cancelLabel};
        Integer result = runOnEdt(() -> JOptionPane.showOptionDialog(
            getDialogParent(),
            createMessageComponent(message),
            title,
            JOptionPane.DEFAULT_OPTION,
            isWarning ? JOptionPane.WARNING_MESSAGE : JOptionPane.QUESTION_MESSAGE,
            null,
            options,
            // Do not make a potentially destructive action the default button
            isWarning ? cancelLabel : buttonLabel));
        return result != null && result == 0;
    }

    @NotNull
    @Override
    public UserChoiceResponse showUserChoice(
        @NotNull String title,
        @Nullable String message,
        @NotNull List<String> labels,
        @NotNull List<String> forAllLabels,
        @Nullable Integer previousChoice,
        int defaultChoice
    ) {
        // Empty labels are placeholders: they get no button, but the returned index refers to the original list
        List<String> options = new ArrayList<>();
        List<Integer> optionIndexes = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            if (!CommonUtils.isEmpty(labels.get(i))) {
                options.add(labels.get(i));
                optionIndexes.add(i);
            }
        }
        UserChoiceResponse response = runOnEdt(() -> {
            JPanel panel = new JPanel(new BorderLayout(0, 8));
            panel.add(createMessageComponent(message), BorderLayout.CENTER);
            // "For all" options are mutually exclusive check boxes, as in DesktopUI
            List<JCheckBox> forAllBoxes = new ArrayList<>();
            if (!forAllLabels.isEmpty()) {
                JPanel boxesPanel = new JPanel(new GridLayout(0, 1));
                for (String forAllLabel : forAllLabels) {
                    JCheckBox box = new JCheckBox(forAllLabel);
                    box.addActionListener(e -> {
                        if (box.isSelected()) {
                            forAllBoxes.stream().filter(b -> b != box).forEach(b -> b.setSelected(false));
                        }
                    });
                    forAllBoxes.add(box);
                    boxesPanel.add(box);
                }
                panel.add(boxesPanel, BorderLayout.SOUTH);
            }
            Object initialOption = previousChoice != null && optionIndexes.contains(previousChoice)
                ? labels.get(previousChoice) : null;
            int selected = JOptionPane.showOptionDialog(
                getDialogParent(),
                panel,
                title,
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null,
                options.toArray(),
                initialOption);
            Integer forAllIndex = null;
            for (int i = 0; i < forAllBoxes.size(); i++) {
                if (forAllBoxes.get(i).isSelected()) {
                    forAllIndex = i;
                }
            }
            int choiceIndex = selected >= 0 && selected < optionIndexes.size() ? optionIndexes.get(selected) : -1;
            return new UserChoiceResponse(choiceIndex, forAllIndex);
        });
        return response == null ? new UserChoiceResponse(-1, null) : response;
    }

    @Override
    public UserResponse showErrorStopRetryIgnore(String task, Throwable error, boolean queue) {
        log.debug(error);
        // Same buttons and mapping as ExecutionQueueErrorDialog / ExecutionQueueErrorJob of the SWT UI
        String[] options = queue
            ? new String[]{"Stop", "Retry", "Skip", "Skip All"}
            : new String[]{"Stop", "Retry"};
        String text = joinText("Error occurred during " + task, CommonUtils.getAllExceptionMessages(error));
        Integer result = runOnEdt(() -> JOptionPane.showOptionDialog(
            getDialogParent(),
            createMessageComponent(text),
            "Execution Error",
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.ERROR_MESSAGE,
            null,
            options,
            options[0]));
        if (result == null) {
            return UserResponse.STOP;
        }
        return switch (result) {
            case 1 -> UserResponse.RETRY;
            case 2 -> UserResponse.IGNORE;
            case 3 -> UserResponse.IGNORE_ALL;
            default -> UserResponse.STOP;
        };
    }

    //////////////////////////////////////////////////////////
    // Prompts

    @Nullable
    @Override
    public DBPAuthInfo promptUserCredentials(
        @Nullable String prompt,
        @Nullable String description,
        @Nullable String userName,
        @Nullable String userPassword,
        boolean passwordOnly,
        boolean showSavePassword
    ) {
        return promptUserCredentials(prompt, description, "User name", userName, "Password", userPassword,
            passwordOnly, showSavePassword);
    }

    @Nullable
    @Override
    public DBPAuthInfo promptUserCredentials(
        @Nullable String prompt,
        @Nullable String description,
        @NotNull String userNameLabel,
        @Nullable String userName,
        @NotNull String passwordLabel,
        @Nullable String userPassword,
        boolean passwordOnly,
        boolean showSavePassword
    ) {
        return runOnEdt(() -> {
            FormPanel form = new FormPanel(description);
            JTextField userField = new JTextField(CommonUtils.notEmpty(userName), 24);
            if (!passwordOnly) {
                form.addRow(userNameLabel, userField);
            }
            JPasswordField passwordField = new JPasswordField(CommonUtils.notEmpty(userPassword), 24);
            form.addRow(passwordLabel, passwordField);
            JCheckBox savePasswordCheck = new JCheckBox("Save password");
            if (showSavePassword) {
                form.addRow(null, savePasswordCheck);
            }
            focusWhenShown(passwordOnly || !CommonUtils.isEmpty(userName) ? passwordField : userField);

            int result = JOptionPane.showConfirmDialog(
                getDialogParent(),
                form,
                CommonUtils.isEmpty(prompt) ? "Authentication" : prompt,
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) {
                return null;
            }
            return new DBPAuthInfo(
                passwordOnly ? userName : userField.getText(),
                new String(passwordField.getPassword()),
                showSavePassword && savePasswordCheck.isSelected());
        });
    }

    @Nullable
    @Override
    public DBAPasswordChangeInfo promptUserPasswordChange(
        String prompt,
        @Nullable String userName,
        @Nullable String oldPassword,
        boolean userEditable,
        boolean oldPasswordVisible
    ) {
        return runOnEdt(() -> {
            FormPanel form = new FormPanel(null);
            JTextField userField = new JTextField(CommonUtils.notEmpty(userName), 24);
            userField.setEditable(userEditable);
            form.addRow("User name", userField);
            JPasswordField oldPasswordField = new JPasswordField(CommonUtils.notEmpty(oldPassword), 24);
            if (oldPasswordVisible) {
                form.addRow("Old password", oldPasswordField);
            }
            JPasswordField newPasswordField = new JPasswordField(24);
            form.addRow("New password", newPasswordField);
            JPasswordField confirmPasswordField = new JPasswordField(24);
            form.addRow("Confirm password", confirmPasswordField);
            focusWhenShown(newPasswordField);

            while (true) {
                int result = JOptionPane.showConfirmDialog(
                    getDialogParent(),
                    form,
                    CommonUtils.isEmpty(prompt) ? "Change password" : prompt,
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE);
                if (result != JOptionPane.OK_OPTION) {
                    return null;
                }
                char[] newPassword = newPasswordField.getPassword();
                char[] confirmPassword = confirmPasswordField.getPassword();
                boolean matches = Arrays.equals(newPassword, confirmPassword);
                Arrays.fill(confirmPassword, '\0');
                if (!matches) {
                    Arrays.fill(newPassword, '\0');
                    JOptionPane.showMessageDialog(getDialogParent(), "Passwords do not match", "Change password",
                        JOptionPane.ERROR_MESSAGE);
                    continue;
                }
                DBAPasswordChangeInfo info = new DBAPasswordChangeInfo(
                    userField.getText(),
                    oldPasswordVisible ? new String(oldPasswordField.getPassword()) : oldPassword);
                info.setNewPassword(new String(newPassword));
                Arrays.fill(newPassword, '\0');
                return info;
            }
        });
    }

    @Nullable
    @Override
    public String promptProperty(@NotNull String prompt, @Nullable String defValue) {
        return promptProperty("Input", prompt, defValue);
    }

    @Nullable
    @Override
    public String promptProperty(@NotNull String title, @NotNull String prompt, @Nullable String defValue) {
        return runOnEdt(() -> (String) JOptionPane.showInputDialog(
            getDialogParent(),
            prompt,
            title,
            JOptionPane.QUESTION_MESSAGE,
            null,
            null,
            defValue));
    }

    @Nullable
    @Override
    public String promptText(@Nullable String title, @Nullable String prompt, @Nullable String defValue) {
        return runOnEdt(() -> {
            JTextArea textArea = new JTextArea(CommonUtils.notEmpty(defValue), 12, 60);
            JPanel panel = new JPanel(new BorderLayout(0, 6));
            if (!CommonUtils.isEmpty(prompt)) {
                panel.add(new JLabel(prompt), BorderLayout.NORTH);
            }
            panel.add(new JScrollPane(textArea), BorderLayout.CENTER);
            focusWhenShown(textArea);
            int result = JOptionPane.showConfirmDialog(
                getDialogParent(),
                panel,
                CommonUtils.isEmpty(title) ? "Input" : title,
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
            return result == JOptionPane.OK_OPTION ? textArea.getText() : null;
        });
    }

    @Override
    public boolean acceptLicense(String message, String licenseText) {
        Integer result = runOnEdt(() -> {
            JTextArea licenseArea = new JTextArea(CommonUtils.notEmpty(licenseText), 20, 70);
            licenseArea.setEditable(false);
            licenseArea.setLineWrap(true);
            licenseArea.setWrapStyleWord(true);
            licenseArea.setCaretPosition(0);
            JPanel panel = new JPanel(new BorderLayout(0, 6));
            panel.add(createMessageComponent(message), BorderLayout.NORTH);
            panel.add(new JScrollPane(licenseArea), BorderLayout.CENTER);
            Object[] options = {"Accept", "Decline"};
            return JOptionPane.showOptionDialog(
                getDialogParent(),
                panel,
                "License agreement",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE,
                null,
                options,
                options[1]);
        });
        return result != null && result == 0;
    }

    //////////////////////////////////////////////////////////
    // Navigator and editors

    @Override
    public DBNNode selectObject(
        @NotNull Object parentShell,
        String title,
        DBNNode rootNode,
        DBNNode selectedNode,
        Class<?>[] allowedTypes,
        Class<?>[] resultTypes,
        Class<?>[] leafTypes
    ) {
        // Phase C: navigator tree selection dialog (can reuse the Object Explorer tree)
        log.debug("Object selection dialog is not implemented yet: " + title);
        return null;
    }

    @Override
    public void openEntityEditor(@NotNull DBSObject object) {
        // Phase C: object editor (properties / DDL tabs)
        log.debug("Entity editor is not implemented yet: " + object.getName());
    }

    @Override
    public void openEntityEditor(@NotNull DBNNode selectedNode, @Nullable String defaultPageId) {
        // Phase C: object editor (properties / DDL tabs)
        log.debug("Entity editor is not implemented yet: " + selectedNode.getNodeDisplayName());
    }

    @Override
    public void openConnectionEditor(@NotNull DBPDataSourceContainer dataSourceContainer) {
        // Generic connection dialog (swing-frontend.md, MVP item 2). Waits until it is closed, like DesktopUI (syncExec):
        // callers may use the changed settings right after this call.
        runOnEdt(() -> {
            ConnectionCommands.edit(getDialogParent(), dataSourceContainer);
            return null;
        });
    }

    //////////////////////////////////////////////////////////
    // Execution

    @Override
    public void executeProcess(@NotNull DBRProcessDescriptor processDescriptor) {
        try {
            processDescriptor.execute();
        } catch (DBException e) {
            showError("Execute process", processDescriptor.getName(), e);
        }
    }

    @Override
    public void executeInMainThread(@NotNull Runnable runnable) {
        // Synchronous, like DesktopUI (UIUtils.syncExec)
        runOnEdt(() -> {
            runnable.run();
            return null;
        });
    }

    @NotNull
    @Override
    public <T> Future<T> executeWithProgressBlocking(
        @NotNull String operationDescription,
        @NotNull DBRRunnableWithResult<Future<T>> runnable
    ) {
        // Same job as DesktopUI. On the EDT the caller waits in a progress dialog, otherwise it just joins the job.
        AbstractJob job = new AbstractJob(operationDescription) {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                monitor.beginTask(operationDescription, IProgressMonitor.UNKNOWN);
                try {
                    runnable.run(monitor);
                    return Status.OK_STATUS;
                } catch (Exception ex) {
                    return GeneralUtils.makeExceptionStatus(ex);
                } finally {
                    monitor.done();
                }
            }

            @Override
            protected void canceling() {
                runnable.cancel();
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            ProgressDialog.waitForJob(operationDescription, job);
        } else {
            job.schedule();
        }
        try {
            job.join();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return CompletableFuture.failedFuture(ex);
        }
        IStatus result = job.getResult();
        if (result == null) {
            return CompletableFuture.failedFuture(new DBException("The result of the job is null. Has it finished?"));
        }
        if (result.isOK()) {
            return runnable.getResult();
        }
        return CompletableFuture.failedFuture(result.getException());
    }

    @Override
    public <T> T runWithMonitor(@NotNull DBRRunnableWithReturn<T> runnable) throws DBException {
        // DesktopUI runs this in the workbench progress service, which shows a dialog for long tasks
        return runWithProgress(runnable);
    }

    @Override
    public <T> T runWithProgress(@NotNull DBRRunnableWithReturn<T> runnable) throws DBException {
        if (SwingUtilities.isEventDispatchThread()) {
            // Keep the UI painting: run in a job and wait in a modal progress dialog with Cancel
            return ProgressDialog.runTask(GeneralUtils.getProductName(), runnable);
        }
        // Already in a background thread: nothing to keep responsive
        return runnable.runTask(new LoggingProgressMonitor(log));
    }

    @NotNull
    @Override
    public <RESULT> Job createLoadingService(
        @NotNull ILoadService<RESULT> loadingService,
        @NotNull ILoadVisualizer<RESULT> visualizer
    ) {
        // Phase C: Swing loading placeholder; the visualizer is completed in the job thread for now
        return new AbstractJob(loadingService.getServiceName()) {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                try {
                    RESULT result = loadingService.evaluate(monitor);
                    visualizer.completeLoading(result);
                    return Status.OK_STATUS;
                } catch (InvocationTargetException e) {
                    return GeneralUtils.makeExceptionStatus(e.getTargetException());
                } catch (InterruptedException e) {
                    return Status.CANCEL_STATUS;
                }
            }
        };
    }

    //////////////////////////////////////////////////////////
    // Desktop integration

    @Override
    public void copyTextToClipboard(@NotNull String text, boolean htmlFormat) {
        if (CommonUtils.isEmpty(text)) {
            return;
        }
        // Phase C: HTML flavor when htmlFormat is true (copied as plain text for now)
        runOnEdt(() -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
            return null;
        });
    }

    @Override
    public void executeShellProgram(@NotNull String shellCommand) {
        // Opens a URL or file with the associated program, like SWT Program.launch() in DesktopUI
        try {
            if (!Desktop.isDesktopSupported()) {
                log.warn("Desktop integration is not supported, can't open '" + shellCommand + "'");
                return;
            }
            Desktop desktop = Desktop.getDesktop();
            File file = new File(shellCommand);
            if (file.exists()) {
                desktop.open(file);
            } else {
                desktop.browse(URI.create(shellCommand));
            }
        } catch (Exception e) {
            log.error("Error opening '" + shellCommand + "'", e);
        }
    }

    @Override
    public void showInSystemExplorer(@NotNull String path) {
        // Same commands as ShellUtils.showInSystemExplorer of the SWT UI, for the supported platforms
        File file = new File(path);
        try {
            if (RuntimeUtils.isWindows()) {
                new ProcessBuilder("explorer.exe", "/select," + file.getAbsolutePath()).start();
            } else if (RuntimeUtils.isMacOS()) {
                new ProcessBuilder("open", "-R", file.getAbsolutePath()).start();
            } else if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(file.isDirectory() ? file : file.getAbsoluteFile().getParentFile());
            }
        } catch (IOException e) {
            log.debug("Error opening file in explorer", e);
        }
    }

    @Override
    public DBNPathBase openFileSystemSelector(
        @NotNull String title,
        boolean folder,
        int style,
        boolean binary,
        String[] filterExt,
        String defaultValue
    ) {
        // Same as DesktopUI of the SWT UI
        showMessageBox("Not supported", "External file system are not supported", true);
        return null;
    }

    @Override
    public boolean readAndDispatchEvents() {
        // Phase C: callers use this to keep the UI responsive while they wait. Swing can't pump events from
        // an arbitrary thread; a java.awt.SecondaryLoop would be needed when called on the EDT.
        return false;
    }

    //////////////////////////////////////////////////////////
    // Helpers

    /**
     * Runs the task on the EDT and waits for its result. Returns {@code null} if the task failed or the
     * waiting thread was interrupted.
     */
    @Nullable
    private static <T> T runOnEdt(@NotNull Supplier<T> task) {
        if (SwingUtilities.isEventDispatchThread()) {
            return task.get();
        }
        AtomicReference<T> result = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> result.set(task.get()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Interrupted while waiting for UI task");
            return null;
        } catch (InvocationTargetException e) {
            log.error("Error in UI task", e.getTargetException());
            return null;
        }
        return result.get();
    }

    private static void showMessage(@NotNull String title, @Nullable String message, int messageType) {
        runOnEdt(() -> {
            JOptionPane.showMessageDialog(getDialogParent(), createMessageComponent(message), title, messageType);
            return null;
        });
    }

    /**
     * The focused window, or else any visible frame, so that dialogs stay on top of the application.
     */
    @Nullable
    private static Component getDialogParent() {
        Window activeWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        if (activeWindow != null) {
            return activeWindow;
        }
        for (Frame frame : Frame.getFrames()) {
            if (frame.isVisible()) {
                return frame;
            }
        }
        return null;
    }

    /**
     * A read-only wrapping text area: JOptionPane shows a plain string on one line, which breaks on long
     * error messages. Long texts get a scroll bar.
     */
    @NotNull
    private static JComponent createMessageComponent(@Nullable String message) {
        JTextArea textArea = new JTextArea(CommonUtils.notEmpty(message));
        textArea.setEditable(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setOpaque(false);
        textArea.setBorder(null);
        textArea.setFont(UIManager.getFont("Label.font"));
        // Fixing the width first lets the text area compute its wrapped height
        textArea.setSize(new Dimension(MESSAGE_WIDTH, Short.MAX_VALUE));
        Dimension preferredSize = textArea.getPreferredSize();
        int maxHeight = textArea.getFontMetrics(textArea.getFont()).getHeight() * MAX_MESSAGE_ROWS;
        if (preferredSize.height <= maxHeight) {
            textArea.setPreferredSize(new Dimension(MESSAGE_WIDTH, preferredSize.height));
            return textArea;
        }
        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setPreferredSize(new Dimension(MESSAGE_WIDTH + 20, maxHeight));
        return scrollPane;
    }

    @NotNull
    private static String joinText(@Nullable String first, @Nullable String second) {
        if (CommonUtils.isEmpty(first)) {
            return CommonUtils.notEmpty(second);
        }
        if (CommonUtils.isEmpty(second) || first.equals(second)) {
            return first;
        }
        return first + "\n\n" + second;
    }

    @NotNull
    private static String getStatusText(@NotNull IStatus status) {
        StringBuilder text = new StringBuilder();
        appendStatusText(text, status);
        return text.toString();
    }

    private static void appendStatusText(@NotNull StringBuilder text, @NotNull IStatus status) {
        String statusMessage = status.getMessage();
        if (!CommonUtils.isEmpty(statusMessage)) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(statusMessage);
        }
        Throwable exception = status.getException();
        if (exception != null) {
            String exceptionMessage = CommonUtils.getAllExceptionMessages(exception);
            if (!CommonUtils.isEmpty(exceptionMessage) && !exceptionMessage.equals(statusMessage)) {
                text.append('\n').append(exceptionMessage);
            }
        }
        for (IStatus child : status.getChildren()) {
            appendStatusText(text, child);
        }
    }

    /**
     * JOptionPane focuses its first button; credential prompts should start in the input field instead.
     */
    private static void focusWhenShown(@NotNull JComponent component) {
        component.addAncestorListener(new AncestorListener() {
            @Override
            public void ancestorAdded(AncestorEvent event) {
                component.removeAncestorListener(this);
                SwingUtilities.invokeLater(component::requestFocusInWindow);
            }

            @Override
            public void ancestorRemoved(AncestorEvent event) {
            }

            @Override
            public void ancestorMoved(AncestorEvent event) {
            }
        });
    }

    /**
     * Label/field rows for the credential prompts.
     */
    private static final class FormPanel extends JPanel {
        private final GridBagConstraints constraints = new GridBagConstraints();

        FormPanel(@Nullable String description) {
            super(new GridBagLayout());
            constraints.insets = new Insets(3, 3, 3, 3);
            constraints.anchor = GridBagConstraints.WEST;
            constraints.gridy = 0;
            if (!CommonUtils.isEmpty(description)) {
                constraints.gridx = 0;
                constraints.gridwidth = 2;
                add(createMessageComponent(description), constraints);
                constraints.gridwidth = 1;
                constraints.gridy++;
            }
        }

        void addRow(@Nullable String label, @NotNull JComponent field) {
            constraints.gridx = 0;
            constraints.fill = GridBagConstraints.NONE;
            constraints.weightx = 0;
            if (label != null) {
                add(new JLabel(label.endsWith(":") ? label : label + ":"), constraints);
            }
            constraints.gridx = 1;
            constraints.fill = GridBagConstraints.HORIZONTAL;
            constraints.weightx = 1;
            add(field, constraints);
            constraints.gridy++;
        }
    }
}
