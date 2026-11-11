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

import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.internal.SettingsMessages;
import org.jkiss.code.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.Collection;
import java.util.List;

/**
 * Tools &gt; Options &gt; Notifications: the long task notice of SWT's Notifications page ({@code PrefPageNotifications}).
 *
 * 설계: 네이티브 도구 작업(백업, 복원 등, {@code AbstractNativeToolHandler})이 끝나면 걸린 시간이 기준을 넘을 때 {@link SwingSystemAgent}가
 * 알림 창을 띄운다. 키와 기본값(켬, 30초)은 그 클래스의 것을 쓴다. SWT 같은 페이지의 알림 창 닫힘 시간·소리 키는 Swing 알림 창이 읽지 않아 두지 않는다.
 * 작업이 끝날 때 읽으므로 바로 적용된다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-options.md "남은 키 (2026-11-08 오후)")
 * - 2026-11-12 저장은 읽는 방식(설정 없음 = 켬, 30초)과 비교: 새 작업 공간에서 끄기가 저장되지 않고 기본값이 늘 다시 쓰이던 것
 *   (앱 변경 #59, docs/chromite/swing-options.md "옵션 화면 모양과 저장 (#59)")
 */
final class NotificationsOptionsPage extends SettingsPage {

    private final JCheckBox notifyBox = new JCheckBox(SettingsMessages.notify_long);
    private final JSpinner timeoutSpinner = new JSpinner(new SpinnerNumberModel(1, 0, 86400, 5));

    NotificationsOptionsPage() {
        notifyBox.setToolTipText(SettingsMessages.notify_long_tip);
        notifyBox.addActionListener(e -> updateEnablement());
        JPanel form = new JPanel(new GridBagLayout());
        int row = OptionsDialog.addSection(form, 0, SettingsMessages.notify_tab);
        row = OptionsDialog.addRow(form, row, null, notifyBox, null);
        OptionsDialog.addRow(form, row, SettingsMessages.notify_timeout, timeoutSpinner, null);
        add(OptionsDialog.wrapPage(form), BorderLayout.CENTER);
        load(DBWorkbench.getPlatform().getPreferenceStore());
    }

    @NotNull
    @Override
    String getTitle() {
        return SettingsMessages.notify_tab;
    }

    @NotNull
    @Override
    Collection<String> getKeys() {
        return List.of(SwingSystemAgent.PREF_LONG_OPERATION_NOTIFY, SwingSystemAgent.PREF_LONG_OPERATION_TIMEOUT);
    }

    @Override
    void load(@NotNull DBPPreferenceStore store) {
        notifyBox.setSelected(SwingSystemAgent.isLongOperationNotify(store));
        timeoutSpinner.setValue((int) Math.clamp(SwingSystemAgent.getLongOperationTimeout(store), 0, 86400));
        updateEnablement();
    }

    @Override
    void save(@NotNull Writer writer) {
        // Compared with the values as they are read (no default is registered for the keys: an unset key reads as on and 30 s),
        // not with getBoolean/getLong of an unset key (false, 0): otherwise "off" on a new workspace was never written and the
        // unchanged defaults were written on every save
        DBPPreferenceStore store = writer.getStore();
        boolean notify = notifyBox.isSelected();
        if (writer.isOverride() || notify != SwingSystemAgent.isLongOperationNotify(store)) {
            store.setValue(SwingSystemAgent.PREF_LONG_OPERATION_NOTIFY, notify);
            writer.markChanged();
        }
        long timeout = ((Number) timeoutSpinner.getValue()).longValue();
        if (writer.isOverride() || timeout != SwingSystemAgent.getLongOperationTimeout(store)) {
            store.setValue(SwingSystemAgent.PREF_LONG_OPERATION_TIMEOUT, timeout);
            writer.markChanged();
        }
    }

    @Override
    void updateEnablement() {
        timeoutSpinner.setEnabled(notifyBox.isSelected());
    }
}
