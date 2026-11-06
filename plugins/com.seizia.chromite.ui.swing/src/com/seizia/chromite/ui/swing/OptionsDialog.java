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
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ModelPreferences;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.utils.PrefUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;

/**
 * Tools &gt; Options (SSMS): 이미 있는 설정만 한곳에서 바꾸는 대화상자.
 *
 * 설계: 새 설정을 만들지 않고, 지금 있는 설정의 저장 위치와 적용 방법을 그대로 쓴다.
 * <ul>
 *     <li>Theme, Icon style: {@link ChromiteTheme#apply()}, {@link UiIconStyle#apply()}(View &gt; Theme 메뉴와 같음, 실행 중 바로 바뀌고 저장).</li>
 *     <li>Auto-complete while typing, Completion delay: {@link SqlCompletionPopup}의 설정(Query &gt; Auto-Complete While Typing과 같은 키).</li>
 *     <li>Maximum rows: 실행 엔진이 읽는 {@link ModelPreferences#RESULT_SET_MAX_ROWS}. 연결의 설정 저장소는 플랫폼 설정 저장소를 부모로 가지므로
 *     ({@code DataSourcePreferenceStore}), 플랫폼 저장소에 쓰면 연결별 값을 따로 두지 않은 모든 연결에 적용된다. 0은 제한 없음.</li>
 * </ul>
 * OK를 누를 때 바뀐 값만 적용한다. 읽기는 열 때 한 번 한다.
 *
 * 변경 이력:
 * - 2026-11-06 P1: 최초 작성 (docs/chromite/swing-frontend.md "P1 디자인 적용")
 */
final class OptionsDialog extends JDialog {

    private static final int MAX_DELAY = 5000;

    private final JComboBox<ChromiteTheme> themeCombo = new JComboBox<>(ChromiteTheme.values());
    private final JComboBox<UiIconStyle> iconStyleCombo = new JComboBox<>(UiIconStyle.values());
    private final JCheckBox autoCompleteBox = new JCheckBox("Show completion proposals while typing");
    private final JSpinner delaySpinner;
    private final JSpinner maxRowsSpinner;

    private OptionsDialog(@NotNull Frame owner) {
        super(owner, "Options", true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        themeCombo.setSelectedItem(ChromiteTheme.getCurrent());
        themeCombo.setRenderer(new LabelRenderer());
        iconStyleCombo.setSelectedItem(UiIconStyle.getCurrent());
        iconStyleCombo.setRenderer(new LabelRenderer());
        autoCompleteBox.setSelected(SqlCompletionPopup.isAutoActivateOnLetters());
        delaySpinner = new JSpinner(new SpinnerNumberModel(
            Math.clamp(SqlCompletionPopup.getAutoActivationDelay(), 0, MAX_DELAY), 0, MAX_DELAY, 50));
        maxRowsSpinner = new JSpinner(new SpinnerNumberModel(
            Math.max(0, getPlatformPreferences().getInt(ModelPreferences.RESULT_SET_MAX_ROWS)), 0, Integer.MAX_VALUE, 100));

        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;
        row = addSection(form, row, "Environment");
        row = addRow(form, row, "Theme", themeCombo, null);
        row = addRow(form, row, "Icon style", iconStyleCombo, null);
        row = addSection(form, row, "Query Editor");
        row = addRow(form, row, null, autoCompleteBox, null);
        row = addRow(form, row, "Completion delay", delaySpinner, "ms after the last keystroke");
        row = addSection(form, row, "Query Results");
        addRow(form, row, "Maximum rows", maxRowsSpinner, "per result set, 0 = no limit");

        JButton okButton = new JButton("OK");
        okButton.addActionListener(e -> {
            apply();
            dispose();
        });
        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(okButton);
        buttons.add(cancelButton);

        JPanel content = new JPanel(new BorderLayout(0, 20));
        // Dialog insets 10/12, groups 20 apart (design-spec space tokens)
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        content.add(form, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);

        getRootPane().setDefaultButton(okButton);
        getRootPane().registerKeyboardAction(e -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setMinimumSize(getSize());
        setLocationRelativeTo(owner);
    }

    static void open(@NotNull Frame owner) {
        new OptionsDialog(owner).setVisible(true);
    }

    /**
     * Section title: font.ui.semibold with a border.subtle line, 20 above (except the first) and 6 below.
     */
    private static int addSection(@NotNull JPanel form, int row, @NotNull String title) {
        JLabel label = new JLabel(title);
        label.putClientProperty(FlatClientProperties.STYLE_CLASS, "semibold"); //$NON-NLS-1$
        JPanel section = new JPanel(new BorderLayout(8, 0));
        section.add(label, BorderLayout.WEST);
        section.add(new JSeparator(), BorderLayout.CENTER);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 3;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(row == 0 ? 0 : 20, 0, 6, 0);
        form.add(section, c);
        return row + 1;
    }

    /**
     * Label (no colon, SSMS), control, and an optional hint in text.secondary; space.6 between them and between rows.
     */
    private static int addRow(@NotNull JPanel form, int row, @Nullable String label, @NotNull JComponent control, @Nullable String hint) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, 6, 6);
        if (label != null) {
            c.gridx = 0;
            form.add(new JLabel(label), c);
        }
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        if (control instanceof JSpinner spinner) {
            ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setColumns(8);
            c.fill = GridBagConstraints.NONE;
        }
        form.add(control, c);
        if (hint != null) {
            JLabel hintLabel = new JLabel(hint);
            hintLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
            c.gridx = 2;
            c.fill = GridBagConstraints.NONE;
            c.weightx = 1;
            form.add(hintLabel, c);
        }
        return row + 1;
    }

    private void apply() {
        if (autoCompleteBox.isSelected() != SqlCompletionPopup.isAutoActivateOnLetters()) {
            SqlCompletionPopup.setAutoActivateOnLetters(autoCompleteBox.isSelected());
        }
        int delay = ((Number) delaySpinner.getValue()).intValue();
        if (delay != SqlCompletionPopup.getAutoActivationDelay()) {
            SqlCompletionPopup.setAutoActivationDelay(delay);
        }
        DBPPreferenceStore platformPreferences = getPlatformPreferences();
        int maxRows = ((Number) maxRowsSpinner.getValue()).intValue();
        if (maxRows != platformPreferences.getInt(ModelPreferences.RESULT_SET_MAX_ROWS)) {
            platformPreferences.setValue(ModelPreferences.RESULT_SET_MAX_ROWS, maxRows);
            PrefUtils.savePreferenceStore(platformPreferences);
        }
        // Theme last: it updates every window, this dialog is about to close
        if (iconStyleCombo.getSelectedItem() instanceof UiIconStyle iconStyle && iconStyle != UiIconStyle.getCurrent()) {
            iconStyle.apply();
        }
        if (themeCombo.getSelectedItem() instanceof ChromiteTheme theme && theme != ChromiteTheme.getCurrent()) {
            theme.apply();
        }
    }

    @NotNull
    private static DBPPreferenceStore getPlatformPreferences() {
        return DBWorkbench.getPlatform().getPreferenceStore();
    }

    /**
     * Theme and icon style items show their labels.
     */
    private static final class LabelRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof ChromiteTheme theme) {
                setText(theme.getLabel());
            } else if (value instanceof UiIconStyle iconStyle) {
                setText(iconStyle.getLabel());
            }
            return this;
        }
    }
}
