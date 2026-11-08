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
import com.seizia.chromite.model.update.UpdatePlace;
import com.seizia.chromite.model.update.UpdateService;
import com.seizia.chromite.ui.swing.internal.UpdateMessages;
import com.seizia.chromite.utils.GeneralUtils;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Tools &gt; Options &gt; Updates: the automatic check switch, the current version and [Check now].
 *
 * 설계: 설정은 업데이트 모델({@link UpdateService#setEnabled})이 업데이트 폴더의 {@code update.json}에 저장한다(작업 공간이 아니라
 * 설치 단위). 이 탭은 다른 탭처럼 OK를 누를 때 바뀐 값만 적용한다. [지금 확인]은 바로 돌고(자동 확인이 꺼져 있어도),
 * 결과는 모델의 상태를 받아 아래 줄에 보인다. 확인이 보내는 것(고정된 요청 하나)을 탭에 적어 둔다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/auto-update.md)
 * - 2026-11-09 모든 사용자 설치의 "준비됨" 문구: [지금 다시 시작]과 관리자 확인으로 설치 (docs/chromite/auto-update.md "관리자 권한으로 설치")
 */
final class UpdateOptionsPage extends JPanel {

    private final UpdateService service = UpdateService.getInstance();
    private final JCheckBox autoBox = new JCheckBox(UpdateMessages.update_options_auto);
    private final JButton checkButton = new JButton(UpdateMessages.update_options_check_now);
    private final JLabel statusLabel = new JLabel(" "); //$NON-NLS-1$
    private final JLabel placeLabel = new JLabel();
    private final boolean initiallyEnabled;
    private final UpdateService.Listener listener = state -> SwingUtilities.invokeLater(() -> show(state));
    private boolean checkRequested;

    UpdateOptionsPage() {
        super(new BorderLayout());
        UpdateService.State state = service.getState();
        initiallyEnabled = state.enabled();
        autoBox.setSelected(initiallyEnabled);
        checkButton.addActionListener(e -> {
            checkRequested = true;
            statusLabel.setText(UpdateMessages.update_options_checking);
            service.checkNow();
        });

        JLabel privacyLabel = new JLabel(NLS.bind(UpdateMessages.update_options_privacy, service.getOrigin().getHost()));
        privacyLabel.putClientProperty(FlatClientProperties.STYLE_CLASS, "small"); //$NON-NLS-1$
        privacyLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        placeLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, 8, 0);
        c.gridy = 0;
        form.add(autoBox, c);
        c.gridy++;
        c.gridwidth = 1;
        form.add(new JLabel(UpdateMessages.update_options_version), c);
        c.gridx = 1;
        c.insets = new Insets(0, 8, 8, 0);
        form.add(new JLabel(GeneralUtils.getPlainVersion()), c);
        c.gridx = 0;
        c.gridy++;
        c.insets = new Insets(0, 0, 8, 0);
        form.add(checkButton, c);
        c.gridx = 1;
        c.insets = new Insets(0, 8, 8, 0);
        form.add(statusLabel, c);
        c.gridx = 0;
        c.gridy++;
        c.gridwidth = 2;
        c.insets = new Insets(4, 0, 4, 0);
        form.add(placeLabel, c);
        c.gridy++;
        form.add(privacyLabel, c);
        // Takes the free width, so the rows start at the left edge of the tab
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridx = 2;
        filler.gridy = 0;
        filler.weightx = 1;
        form.add(Box.createHorizontalGlue(), filler);

        setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        add(form, BorderLayout.NORTH);
        show(state);
    }

    @Override
    public void addNotify() {
        super.addNotify();
        service.addListener(listener);
    }

    @Override
    public void removeNotify() {
        service.removeListener(listener);
        super.removeNotify();
    }

    /**
     * OK: the switch, when it changed.
     */
    void apply() {
        if (autoBox.isSelected() != initiallyEnabled) {
            service.setEnabled(autoBox.isSelected());
        }
    }

    private void show(@NotNull UpdateService.State state) {
        boolean off = state.mode() == UpdatePlace.Mode.OFF;
        checkButton.setEnabled(!off && !state.checking());
        autoBox.setEnabled(!off);
        placeLabel.setText(off ? UpdateMessages.update_options_off
            : state.mode() == UpdatePlace.Mode.NOTIFY ? UpdateMessages.update_options_notify : " "); //$NON-NLS-1$
        String text = describe(state);
        if (text != null) {
            statusLabel.setText(text);
        }
    }

    /**
     * The line next to [Check now]: what the updater holds, else (after a check asked here) what the check found.
     */
    @Nullable
    private String describe(@NotNull UpdateService.State state) {
        String version = state.version();
        switch (state.phase()) {
            case DOWNLOADING:
                return NLS.bind(UpdateMessages.update_options_downloading, version, state.progress());
            case READY:
                // An installation for all users installs only with [Restart now] (administrator prompt), not at a close
                return NLS.bind(service.isAllUsers() ? UpdateMessages.update_options_ready_all_users : UpdateMessages.update_options_ready,
                    version);
            case AVAILABLE:
                return NLS.bind(UpdateMessages.update_options_available, version);
            case FAILED:
                return NLS.bind(UpdateMessages.update_options_failed, version, UpdateUi.describeStep(state.step()));
            default:
                break;
        }
        if (state.checking()) {
            return UpdateMessages.update_options_checking;
        }
        if (!checkRequested) {
            return null;
        }
        return switch (state.lastCheck()) {
            case UP_TO_DATE -> UpdateMessages.update_options_up_to_date;
            case UNREACHABLE -> UpdateMessages.update_options_unreachable;
            default -> null;
        };
    }
}
