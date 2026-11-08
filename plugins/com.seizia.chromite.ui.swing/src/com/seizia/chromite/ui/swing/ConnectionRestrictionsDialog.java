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

import com.seizia.chromite.model.DBPDataSourcePermission;
import com.seizia.chromite.model.connection.DBPConnectionType;
import com.seizia.chromite.ui.swing.internal.ConnectionMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 연결마다 정하는 수정 제한(데이터 편집, 구조 편집, 스크립트 실행, 데이터 가져오기) 편집 대화상자.
 *
 * 설계: SWT {@code EditConnectionPermissionsDialog}와 같은 값({@code DBPDataSourcePermissionOwner#setModifyPermissions})을 고친다.
 * 모델은 연결의 제한 목록이 비어 있으면 연결 유형의 제한을 쓰므로({@code DataSourceDescriptor.hasModifyPermission}) SWT의 확인란만으로는
 * "유형을 따름"과 "이 연결은 제한 없음"을 구별할 수 없었다. 그래서 "연결 유형의 제한 사용"과 "이 연결의 제한을 직접 정함"을 고르게 하고,
 * 유형을 따를 때는 확인란에 유형의 제한을 흐리게 보여 준다. 직접 정하면서 아무것도 고르지 않으면 모델에서는 유형을 따르는 것과 같아
 * 그 뜻을 알리는 줄을 보인다. 값은 연결 설정 화면이 저장한다(Save, 잠긴 연결이면 잠금 비밀번호를 먼저 묻는다).
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: 연결별 제한 (docs/chromite/swing-conn-advanced.md "연결별 제한과 연결 잠금")
 */
final class ConnectionRestrictionsDialog {

    private ConnectionRestrictionsDialog() {
    }

    /**
     * Shows the dialog.
     *
     * @param restrictions the connection's own restrictions; empty means the connection type's restrictions apply
     * @param type         the connection type selected in the connection settings
     * @return the connection's new own restrictions (empty: the connection type's), or {@code null} when canceled
     */
    @Nullable
    static List<DBPDataSourcePermission> edit(
        @NotNull Component parent,
        @NotNull List<DBPDataSourcePermission> restrictions,
        @NotNull DBPConnectionType type
    ) {
        JRadioButton useTypeRadio = new JRadioButton(NLS.bind(ConnectionMessages.restrictions_use_type, type.getName()));
        JRadioButton ownRadio = new JRadioButton(ConnectionMessages.restrictions_own);
        ButtonGroup group = new ButtonGroup();
        group.add(useTypeRadio);
        group.add(ownRadio);
        JLabel noneNote = new JLabel(ConnectionMessages.restrictions_none_note);
        noneNote.setForeground(ConnectionDialog.getThemeColor("Chromite.secondaryForeground", "Label.disabledForeground", Color.GRAY)); //$NON-NLS-1$ //$NON-NLS-2$

        Map<DBPDataSourcePermission, JCheckBox> boxes = new EnumMap<>(DBPDataSourcePermission.class);
        // The checks of "own" survive switching to the type's restrictions and back
        List<DBPDataSourcePermission> own = new ArrayList<>(restrictions.isEmpty() ? typeRestrictions(type) : restrictions);
        Runnable update = () -> {
            boolean useType = useTypeRadio.isSelected();
            for (Map.Entry<DBPDataSourcePermission, JCheckBox> entry : boxes.entrySet()) {
                entry.getValue().setSelected(useType ? !type.hasModifyPermission(entry.getKey()) : own.contains(entry.getKey()));
                entry.getValue().setEnabled(!useType);
            }
            noneNote.setVisible(!useType && own.isEmpty());
        };

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.gridy = 0;
        gc.anchor = GridBagConstraints.WEST;
        gc.insets = new Insets(0, 0, 4, 0);
        panel.add(useTypeRadio, gc);
        gc.gridy++;
        panel.add(ownRadio, gc);
        gc.insets = new Insets(0, 24, 2, 0);
        for (DBPDataSourcePermission permission : DBPDataSourcePermission.values()) {
            JCheckBox box = new JCheckBox(permission.getLabel());
            box.setToolTipText(permission.getDescription());
            box.addActionListener(e -> {
                own.remove(permission);
                if (box.isSelected()) {
                    own.add(permission);
                }
                update.run();
            });
            boxes.put(permission, box);
            gc.gridy++;
            panel.add(box, gc);
        }
        gc.gridy++;
        gc.insets = new Insets(6, 24, 0, 0);
        panel.add(noneNote, gc);

        (restrictions.isEmpty() ? useTypeRadio : ownRadio).setSelected(true);
        useTypeRadio.addActionListener(e -> update.run());
        ownRadio.addActionListener(e -> update.run());
        update.run();

        int result = JOptionPane.showConfirmDialog(parent, panel, ConnectionMessages.restrictions_title,
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return null;
        }
        if (useTypeRadio.isSelected()) {
            return List.of();
        }
        // In the declaration order, as the connection type page and SWT list them
        List<DBPDataSourcePermission> chosen = new ArrayList<>();
        for (DBPDataSourcePermission permission : DBPDataSourcePermission.values()) {
            if (own.contains(permission)) {
                chosen.add(permission);
            }
        }
        return chosen;
    }

    /**
     * The restrictions of a connection type, in declaration order.
     */
    @NotNull
    static List<DBPDataSourcePermission> typeRestrictions(@NotNull DBPConnectionType type) {
        List<DBPDataSourcePermission> restrictions = new ArrayList<>();
        for (DBPDataSourcePermission permission : DBPDataSourcePermission.values()) {
            if (!type.hasModifyPermission(permission)) {
                restrictions.add(permission);
            }
        }
        return restrictions;
    }
}
