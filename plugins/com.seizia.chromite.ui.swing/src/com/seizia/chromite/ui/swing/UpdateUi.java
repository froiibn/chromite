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

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.UIScale;
import com.seizia.chromite.model.update.UpdateService;
import com.seizia.chromite.model.update.UpdateStep;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.internal.UpdateMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * The updater's place in the main window: one button at the right of the title bar (the menu bar, which FlatLaf
 * embeds into the title bar), and the download progress at the right of the status bar.
 *
 * 설계: Turbrix 데스크톱의 화면(#579, #583, #585 사용자 결정)을 따른다. 상태는 모델({@link UpdateService})이 정하고
 * 이 클래스는 보이기만 한다.
 * <ul>
 *     <li>받는 동안: 상태 표시줄 오른쪽에 "새 버전 X 받는 중 (n%)"과 막대, 제목 표시줄에 단추 "새 버전 X". 누르면 메뉴 대신
 *     업데이트 진행 창({@link UpdateProgress})이 받기를 보인다(Turbrix #747).</li>
 *     <li>받아서 확인함: 단추 "새 버전 X · 다시 시작". 누르면 메뉴 [지금 다시 시작] [업데이트 내용].</li>
 *     <li>알리기만(설치 프로그램으로 설치하지 않은 복사본): 단추 "새 버전 X". 메뉴 [받는 곳 열기] [업데이트 내용].</li>
 *     <li>확인 실패: 경고 단추 "업데이트 파일 확인 실패". 설명(회사 보안 장비가 내려받기를 막을 수 있음)은 단추의 툴팁과
 *     메뉴 첫 줄이고, 메뉴는 [다시 시도] [알림 끄기]다. 끈 실패는 보이지 않는다.</li>
 * </ul>
 * [지금 다시 시작]은 창 닫기와 같은 정상 종료 경로를 탄다({@code WINDOW_CLOSING}을 보내 {@code MainFrame.requestExit}가
 * 저장 안 한 편집기와 커밋하지 않은 트랜잭션을 묻게 한다). 사용자가 취소하면 창이 남고 다시 시작 요청도 지운다.
 * 설치는 앱이 끝난 뒤 도우미가 한다({@code ChromiteSwingApplication}이 {@link UpdateService#applyAtExit}를 부른다).
 *
 * 진행 글자는 보조 글자 색이고, 상태 표시줄이 활성 쿼리 창 연결 색으로 칠해져 그 색 위에서 읽히지 않으면 쿼리 창 칸과 같은 대비 색이다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/auto-update.md)
 * - 2026-11-08 받는 동안 제목 표시줄 단추가 업데이트 진행 창을 엶, 열린 창이 상태를 따라감 (docs/chromite/auto-update.md "업데이트 진행 창")
 * - 2026-11-08 진행 글자가 연결 색으로 칠한 상태 표시줄 위의 대비 색을 따름(앱 변경 #2) (docs/chromite/swing-frontend.md "상태 표시줄의 쿼리 창 칸")
 */
final class UpdateUi {

    private static volatile boolean restartRequested;

    private final JFrame frame;
    private final UpdateService service;
    private final JButton titleButton = new JButton();
    private final JLabel progressLabel;
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JPanel progressPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    private final UpdateService.Listener listener;
    @NotNull
    private UpdateService.State shown;

    private UpdateUi(@NotNull JFrame frame, @NotNull JPanel statusBar) {
        this.frame = frame;
        this.service = UpdateService.getInstance();
        this.shown = service.getState();

        titleButton.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        titleButton.setFocusable(false);
        titleButton.setVisible(false);
        titleButton.addActionListener(e -> showMenu());
        JMenuBar menuBar = frame.getJMenuBar();
        if (menuBar != null) {
            // Components after a glue sit at the right end of the menu bar, in the title bar when FlatLaf embeds it there
            menuBar.add(Box.createHorizontalGlue());
            menuBar.add(titleButton);
        }

        progressLabel = new JLabel() {
            /**
             * text.secondary, or the contrast color while the status bar is painted in a query window connection's color
             * the theme's text is unreadable on ({@link MainFrame#STATUS_BAR_FOREGROUND_KEY}). Read when painting: a FlatLaf
             * style cannot set the color of this class (see {@link QueryStatusLine}).
             */
            @Override
            public Color getForeground() {
                Color band = statusBar == null ? null : (Color) statusBar.getClientProperty(MainFrame.STATUS_BAR_FOREGROUND_KEY);
                Color color = band != null ? band : UIManager.getColor("Chromite.secondaryForeground"); //$NON-NLS-1$
                return color != null ? color : super.getForeground();
            }
        };
        progressBar.setPreferredSize(new Dimension(UIScale.scale(120), UIScale.scale(4)));
        progressPanel.setOpaque(false);
        progressPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, UIScale.scale(12)));
        progressPanel.add(progressLabel);
        progressPanel.add(progressBar);
        progressPanel.setVisible(false);
        JPanel progressHolder = new JPanel(new GridBagLayout());
        progressHolder.setOpaque(false);
        progressHolder.add(progressPanel);
        statusBar.add(progressHolder, BorderLayout.EAST);

        listener = state -> SwingUtilities.invokeLater(() -> show(state));
        service.addListener(listener);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                service.removeListener(listener);
            }
        });
        show(service.getState());
    }

    /**
     * Puts the update button into the main window's menu bar and the progress into its status bar, and starts the
     * updater (its first check comes 10 s later).
     */
    static void install(@NotNull JFrame frame, @NotNull JPanel statusBar) {
        new UpdateUi(frame, statusBar);
        UpdateService.getInstance().start();
    }

    /**
     * Whether the main window closed because of [Restart now].
     */
    static boolean isRestartRequested() {
        return restartRequested;
    }

    /**
     * The words of a failed check.
     */
    @NotNull
    static String describeStep(@Nullable UpdateStep step) {
        if (step == null) {
            return UpdateMessages.update_step_checker;
        }
        return switch (step) {
            case SIZE -> UpdateMessages.update_step_size;
            case SHA256 -> UpdateMessages.update_step_sha256;
            case CHECKER -> UpdateMessages.update_step_checker;
            case SIGNATURE -> UpdateMessages.update_step_signature;
            case PUBLISHER -> UpdateMessages.update_step_publisher;
            case TIMESTAMP -> UpdateMessages.update_step_timestamp;
            case PRODUCT -> UpdateMessages.update_step_product;
            case VERSION -> UpdateMessages.update_step_version;
            case EXTRACT -> UpdateMessages.update_step_extract;
            case NOTARIZATION -> UpdateMessages.update_step_notarization;
        };
    }

    private void show(@NotNull UpdateService.State state) {
        shown = state;
        UpdateService.InstallFailure installFailure = service.getInstallFailure();
        if (installFailure != null && frame.isShowing()) {
            // The update applied at the last end did not install: told once (the first check, 10 s after the start,
            // brings a state change if the window was not shown yet)
            service.acknowledgeInstallFailure();
            NotificationToast.show(frame, UpdateMessages.update_install_failed_title,
                NLS.bind(UpdateMessages.update_install_failed_message, installFailure.version(), installFailure.code()),
                NotificationToast.Kind.WARNING, null);
        }
        String version = state.version() != null ? state.version() : ""; //$NON-NLS-1$
        boolean downloading = state.phase() == UpdateService.Phase.DOWNLOADING;
        progressPanel.setVisible(downloading);
        if (downloading) {
            progressLabel.setText(NLS.bind(UpdateMessages.update_downloading, version, state.progress()));
            progressBar.setValue(state.progress());
        }
        switch (state.phase()) {
            case DOWNLOADING -> showButton(NLS.bind(UpdateMessages.update_button_available, version),
                UpdateMessages.update_window_head_downloading, "foreground: $Component.linkColor"); //$NON-NLS-1$
            case READY -> showButton(NLS.bind(UpdateMessages.update_button_ready, version),
                NLS.bind(UpdateMessages.update_button_ready_tip, version), "foreground: $Component.linkColor"); //$NON-NLS-1$
            case AVAILABLE -> showButton(NLS.bind(UpdateMessages.update_button_available, version),
                NLS.bind(UpdateMessages.update_button_available_tip, version), "foreground: $Component.linkColor"); //$NON-NLS-1$
            case FAILED -> {
                if (state.dismissed()) {
                    titleButton.setVisible(false);
                } else {
                    showButton(UpdateMessages.update_button_failed, failureMessage(state), "foreground: $Chromite.status.error"); //$NON-NLS-1$
                }
            }
            default -> titleButton.setVisible(false);
        }
        progressPanel.revalidate();
        JMenuBar menuBar = frame.getJMenuBar();
        if (menuBar != null) {
            menuBar.revalidate();
            menuBar.repaint();
        }
        // An update window opened from the button follows the state
        UpdateProgress.updaterChanged();
    }

    private void showButton(@NotNull String text, @NotNull String tip, @NotNull String style) {
        titleButton.setText(text);
        titleButton.setToolTipText(tip);
        titleButton.putClientProperty(FlatClientProperties.STYLE, style);
        titleButton.setVisible(true);
    }

    @NotNull
    private static String failureMessage(@NotNull UpdateService.State state) {
        return NLS.bind(UpdateMessages.update_failed_message, state.version(), describeStep(state.step()));
    }

    private void showMenu() {
        UpdateService.State state = shown;
        JPopupMenu menu = new JPopupMenu();
        switch (state.phase()) {
            case DOWNLOADING -> {
                // No menu: the update window shows the download
                UpdateProgress.openFromButton(frame, this::restart);
                return;
            }
            case READY -> {
                menu.add(item(UpdateMessages.update_restart_now, this::restart));
                addNotes(menu, state);
            }
            case AVAILABLE -> {
                menu.add(item(UpdateMessages.update_open_download_page,
                    () -> openUrl(service.getOrigin().toString() + "/"))); //$NON-NLS-1$
                addNotes(menu, state);
            }
            case FAILED -> {
                // The explanation is the first line of the menu (not clickable)
                JLabel message = new JLabel("<html><div style='width:" + UIScale.scale(280) + "px'>" //$NON-NLS-1$ //$NON-NLS-2$
                    + escapeHtml(failureMessage(state)) + "</div></html>"); //$NON-NLS-1$
                message.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
                menu.add(message);
                menu.addSeparator();
                menu.add(item(UpdateMessages.update_try_again, service::checkNow));
                menu.add(item(UpdateMessages.update_dismiss, service::dismiss));
            }
            default -> {
                return;
            }
        }
        menu.show(titleButton, 0, titleButton.getHeight());
    }

    private void addNotes(@NotNull JPopupMenu menu, @NotNull UpdateService.State state) {
        String notes = state.notesUrl();
        if (notes != null) {
            menu.add(item(UpdateMessages.update_whats_new, () -> openUrl(notes)));
        }
    }

    @NotNull
    private static JMenuItem item(@NotNull String text, @NotNull Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(e -> action.run());
        return item;
    }

    /**
     * [Restart now]: the window's normal close, which asks about unsaved editors and open transactions first.
     */
    private void restart() {
        restartRequested = true;
        frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
        if (frame.isDisplayable()) {
            // The user cancelled the close
            restartRequested = false;
        }
    }

    private static void openUrl(@NotNull String url) {
        DBWorkbench.getPlatformUI().executeShellProgram(url);
    }

    @NotNull
    private static String escapeHtml(@NotNull String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
    }
}
