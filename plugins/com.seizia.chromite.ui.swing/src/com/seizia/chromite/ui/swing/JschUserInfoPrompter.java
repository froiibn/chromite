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

import com.jcraft.jsch.UIKeyboardInteractive;
import com.jcraft.jsch.UserInfo;
import com.seizia.chromite.ui.swing.internal.ConnectionMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.net.ssh.config.SSHAuthConfiguration;
import com.seizia.chromite.model.net.ssh.config.SSHHostConfiguration;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * JSch의 사용자 질문(호스트 키 확인, 비밀번호, 개인 키 암호, 키보드 대화형 입력)을 Swing 대화상자로 묻는다.
 *
 * 설계: JSch는 연결 중에 {@link UserInfo}·{@link UIKeyboardInteractive}로 질문한다. 모델 기본값({@code JSCHSessionController.JschUserInfo})은
 * 화면이 없을 때를 위한 것이라 호스트 키 질문에 늘 "아니요", 키보드 대화형 질문마다 저장된 비밀번호를 답한다. SWT는 Eclipse
 * {@code UserInfoPrompter}를 상속한 {@code JSCHUIPromptProvider}로 이를 대신했고, 이 클래스가 같은 일을 Swing으로 한다.
 * <ul>
 *     <li>저장된 값은 처음 한 번만 쓰고, 다시 물으면(값이 틀렸거나 없으면) 사용자에게 묻는다. Eclipse {@code UserInfoPrompter}가
 *     키보드 대화형 "Password:" 질문에 쓰는 규칙을 비밀번호·개인 키 암호에도 적용했다. SWT는 비밀번호·암호 질문에 저장된 값만 돌려줘
 *     값이 없거나 틀리면 JSch가 같은 값으로 다시 시도하다 실패했다.</li>
 *     <li>저장된 값은 인증 방식에 맞는 질문에만 쓴다: 비밀번호 방식이면 로그인 비밀번호, 키 방식이면 개인 키 암호.
 *     SWT와 모델 기본값은 같은 값을 두 질문에 모두 답해, 키 방식에서 개인 키 암호가 서버에 비밀번호로 갈 수 있었다.</li>
 *     <li>안전 쪽으로 실패한다: 화면이 없거나(headless) 대화상자가 실패하거나 사용자가 취소하면 거절(false/null)한다.
 *     호스트 키 질문은 "아니요"가 기본 단추다.</li>
 * </ul>
 * 연결 Job 스레드에서 불리므로 대화상자는 EDT에서 띄우고 기다린다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성: JSch 호스트 키 확인·OTP 입력 (docs/chromite/swing-auth-prompts.md)
 */
final class JschUserInfoPrompter implements UserInfo, UIKeyboardInteractive {

    private static final Log log = Log.getLog(JschUserInfoPrompter.class);

    /**
     * The keyboard-interactive prompt that only asks for the login password (Eclipse UserInfoPrompter uses the same check).
     */
    private static final String PASSWORD_PROMPT = "password:"; //$NON-NLS-1$

    @NotNull
    private final SSHHostConfiguration configuration;
    @Nullable
    private String savedPassword;
    @Nullable
    private String savedPassphrase;
    @Nullable
    private String password;
    @Nullable
    private String passphrase;

    JschUserInfoPrompter(@NotNull SSHHostConfiguration configuration) {
        this.configuration = configuration;
        if (configuration.auth() instanceof SSHAuthConfiguration.Password auth) {
            savedPassword = CommonUtils.nullIfEmpty(auth.password());
        } else if (configuration.auth() instanceof SSHAuthConfiguration.WithPassword auth) {
            savedPassphrase = CommonUtils.nullIfEmpty(auth.password());
        }
    }

    @Nullable
    @Override
    public String getPassword() {
        return password;
    }

    @Nullable
    @Override
    public String getPassphrase() {
        return passphrase;
    }

    @Override
    public boolean promptPassword(String message) {
        if (savedPassword != null) {
            password = savedPassword;
            savedPassword = null;
            return true;
        }
        password = promptSecret(NLS.bind(ConnectionMessages.ssh_prompt_password, configuration.toDisplayString()),
            ConnectionMessages.settings_password);
        return password != null;
    }

    @Override
    public boolean promptPassphrase(String message) {
        if (savedPassphrase != null) {
            passphrase = savedPassphrase;
            savedPassphrase = null;
            return true;
        }
        passphrase = promptSecret(NLS.bind(ConnectionMessages.ssh_prompt_passphrase, configuration.toDisplayString()),
            ConnectionMessages.ssh_passphrase);
        return passphrase != null;
    }

    @Override
    public boolean promptYesNo(String message) {
        String yes = UIManager.getString("OptionPane.yesButtonText"); //$NON-NLS-1$
        String no = UIManager.getString("OptionPane.noButtonText"); //$NON-NLS-1$
        Integer result = runOnEdt(() -> JOptionPane.showOptionDialog(
            SwingPlatformUI.getDialogParent(),
            SwingPlatformUI.createMessageComponent(message),
            ConnectionMessages.ssh_prompt_host_key_title,
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE,
            null,
            new Object[]{yes, no},
            // Accepting an unknown or changed host key is the risky answer
            no));
        boolean accepted = result != null && result == 0;
        log.debug("SSH question for " + configuration + (accepted ? " accepted" : " refused"));
        return accepted;
    }

    @Override
    public void showMessage(String message) {
        // Server banners and notices: SWT also only logged them
        log.debug("SSH server message: " + message);
    }

    @Nullable
    @Override
    public String[] promptKeyboardInteractive(String destination, String name, String instruction, String[] prompt, boolean[] echo) {
        if (prompt.length == 0) {
            return new String[0];
        }
        if (savedPassword != null && prompt.length == 1 && prompt[0].trim().equalsIgnoreCase(PASSWORD_PROMPT)) {
            String value = savedPassword;
            savedPassword = null;
            return new String[]{value};
        }
        return runOnEdt(() -> {
            JPanel form = new JPanel(new GridBagLayout());
            GridBagConstraints constraints = new GridBagConstraints();
            constraints.insets = new Insets(3, 3, 3, 3);
            constraints.anchor = GridBagConstraints.WEST;
            constraints.gridy = 0;
            String description = joinLines(name, instruction);
            if (!description.isEmpty()) {
                constraints.gridx = 0;
                constraints.gridwidth = 2;
                form.add(SwingPlatformUI.createMessageComponent(description), constraints);
                constraints.gridwidth = 1;
                constraints.gridy++;
            }
            JTextField[] fields = new JTextField[prompt.length];
            for (int i = 0; i < prompt.length; i++) {
                // echo off: one-time codes and passwords are not shown
                boolean visible = i < echo.length && echo[i];
                fields[i] = visible ? new JTextField(24) : new JPasswordField(24);
                constraints.gridx = 0;
                constraints.fill = GridBagConstraints.NONE;
                constraints.weightx = 0;
                form.add(new JLabel(prompt[i].trim()), constraints);
                constraints.gridx = 1;
                constraints.fill = GridBagConstraints.HORIZONTAL;
                constraints.weightx = 1;
                form.add(fields[i], constraints);
                constraints.gridy++;
            }
            SwingPlatformUI.focusWhenShown(fields[0]);
            int result = JOptionPane.showConfirmDialog(
                SwingPlatformUI.getDialogParent(),
                form,
                NLS.bind(ConnectionMessages.ssh_prompt_title, configuration.toDisplayString()),
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
            if (result != JOptionPane.OK_OPTION) {
                return null;
            }
            String[] answers = new String[fields.length];
            for (int i = 0; i < fields.length; i++) {
                answers[i] = fields[i] instanceof JPasswordField passwordField
                    ? new String(passwordField.getPassword()) : fields[i].getText();
            }
            return answers;
        });
    }

    /**
     * Asks for one secret; {@code null} when canceled or when it can't be asked.
     */
    @Nullable
    private String promptSecret(@NotNull String description, @NotNull String label) {
        return runOnEdt(() -> {
            JPanel form = new JPanel(new BorderLayout(0, 6));
            form.add(SwingPlatformUI.createMessageComponent(description), BorderLayout.NORTH);
            JPanel row = new JPanel(new BorderLayout(6, 0));
            row.add(new JLabel(label.endsWith(":") ? label : label + ":"), BorderLayout.WEST); //$NON-NLS-1$ //$NON-NLS-2$
            JPasswordField secretField = new JPasswordField(24);
            row.add(secretField, BorderLayout.CENTER);
            form.add(row, BorderLayout.CENTER);
            SwingPlatformUI.focusWhenShown(secretField);
            int result = JOptionPane.showConfirmDialog(
                SwingPlatformUI.getDialogParent(),
                form,
                NLS.bind(ConnectionMessages.ssh_prompt_title, configuration.toDisplayString()),
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
            return result == JOptionPane.OK_OPTION ? new String(secretField.getPassword()) : null;
        });
    }

    /**
     * Runs a dialog on the EDT and waits. Fails closed: {@code null} without a screen, on an error or an interrupt.
     */
    @Nullable
    private static <T> T runOnEdt(@NotNull Supplier<T> task) {
        if (GraphicsEnvironment.isHeadless()) {
            log.debug("No screen to ask the SSH question: refused");
            return null;
        }
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                return task.get();
            }
            AtomicReference<T> result = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> result.set(task.get()));
            return result.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Interrupted while asking the SSH question: refused");
            return null;
        } catch (InvocationTargetException e) {
            log.error("Error asking the SSH question: refused", e.getTargetException());
            return null;
        } catch (RuntimeException e) {
            log.error("Error asking the SSH question: refused", e);
            return null;
        }
    }

    @NotNull
    private static String joinLines(@Nullable String first, @Nullable String second) {
        String a = CommonUtils.notEmpty(first).trim();
        String b = CommonUtils.notEmpty(second).trim();
        if (a.isEmpty()) {
            return b;
        }
        return b.isEmpty() ? a : a + "\n" + b; //$NON-NLS-1$
    }
}
