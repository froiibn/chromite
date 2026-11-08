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

import com.formdev.flatlaf.util.UIScale;
import com.seizia.chromite.registry.SettingsTransfer.Section;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.SettingsTransferMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * File &gt; Export Settings: the parts to put into the settings file.
 *
 * 설계: 옵션·드라이버·Query Library·스크립트는 처음에 켜 두고, 연결은 꺼 둔다(브리프: 연결은 고를 때만). 연결을 켜면 다음 단계에서
 * #108의 연결 내보내기 대화상자({@link ConnectionExportDialog})로 연결과 비밀번호 포함 여부·암호를 고른다. 내보내지 않는 것은 표 아래에 적는다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/settings-transfer.md)
 */
final class SettingsExportDialog extends JDialog {

    private final Map<Section, JCheckBox> boxes = new EnumMap<>(Section.class);
    private boolean accepted;

    private SettingsExportDialog(@Nullable Window owner) {
        super(owner, SettingsTransferMessages.settings_export_title, ModalityType.APPLICATION_MODAL);
        JPanel sections = new JPanel(new GridLayout(0, 1, 0, 4));
        sections.add(new JLabel(SettingsTransferMessages.settings_export_choose));
        for (Section section : Section.values()) {
            JCheckBox box = new JCheckBox(getLabel(section), section != Section.CONNECTIONS);
            boxes.put(section, box);
            sections.add(box);
        }
        JLabel note = new JLabel("<html><body style='width:" + UIScale.scale(460) + "px'>" //$NON-NLS-1$ //$NON-NLS-2$
            + SettingsTransferMessages.settings_export_note + "</body></html>"); //$NON-NLS-1$

        JButton exportButton = new JButton(SettingsTransferMessages.settings_export_button);
        JButton cancelButton = new JButton(CoreMessages.common_cancel);
        exportButton.addActionListener(e -> {
            if (getSections().isEmpty()) {
                JOptionPane.showMessageDialog(this, SettingsTransferMessages.settings_export_nothing,
                    SettingsTransferMessages.settings_export_title, JOptionPane.WARNING_MESSAGE);
                return;
            }
            accepted = true;
            dispose();
        });
        cancelButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(exportButton);
        buttons.add(cancelButton);

        JPanel content = new JPanel(new BorderLayout(0, 12));
        // Dialog insets of docs/chromite/design-spec.md "11. 대화상자" (10, 12)
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        content.add(sections, BorderLayout.NORTH);
        content.add(note, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        // Exporting only writes a file the user names next (an existing file is replaced only after a confirmation)
        getRootPane().setDefaultButton(exportButton);
        getRootPane().registerKeyboardAction(e -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * Shows the dialog; returns the chosen parts, or null when canceled.
     */
    @Nullable
    static Set<Section> open(@NotNull Component parent) {
        Window owner = parent instanceof Window window ? window : SwingUtilities.getWindowAncestor(parent);
        SettingsExportDialog dialog = new SettingsExportDialog(owner);
        dialog.setVisible(true);
        return dialog.accepted ? dialog.getSections() : null;
    }

    @NotNull
    private Set<Section> getSections() {
        Set<Section> result = EnumSet.noneOf(Section.class);
        boxes.forEach((section, box) -> {
            if (box.isSelected()) {
                result.add(section);
            }
        });
        return result;
    }

    @NotNull
    private static String getLabel(@NotNull Section section) {
        return switch (section) {
            case PREFERENCES -> SettingsTransferMessages.settings_section_preferences;
            case DRIVERS -> SettingsTransferMessages.settings_section_drivers;
            case QUERY_LIBRARY -> SettingsTransferMessages.settings_section_query_library;
            case SCRIPTS -> SettingsTransferMessages.settings_section_scripts;
            case CONNECTIONS -> SettingsTransferMessages.settings_section_connections;
        };
    }
}
