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
import com.seizia.chromite.ui.swing.internal.ToolsMessages;
import org.jkiss.code.NotNull;

import javax.swing.*;
import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;

/**
 * The "When" part of a schedule dialog: every N minutes/hours, daily at hh:mm, weekdays at hh:mm. The same component in the
 * query's Schedule… ({@link QueryScheduleDialog}) and the saved task's Schedule… ({@link TaskScheduleDialog}).
 *
 * 설계: 두 대화상자가 같은 칸을 두 벌 두지 않게 떼어 냈다. 값은 {@link ScheduleTiming}에서 읽고 쓴다(사용 여부는 대화상자의 칸).
 *
 * 변경 이력:
 * - 2026-11-12 최초 작성: {@link QueryScheduleDialog}의 When 칸을 떼어 작업 예약 대화상자와 함께 씀(앱 변경 #104,
 *   docs/chromite/task-scheduling.md "화면")
 */
final class ScheduleWhenPanel extends JPanel {

    private final JRadioButton intervalButton = new JRadioButton(ToolsMessages.schedule_every);
    private final JRadioButton dailyButton = new JRadioButton(ToolsMessages.schedule_daily);
    private final JRadioButton weekdaysButton = new JRadioButton(ToolsMessages.schedule_weekdays);
    private final JSpinner intervalSpinner = new JSpinner(new SpinnerNumberModel(5, 1, 10000, 1));
    private final JComboBox<String> unitCombo = new JComboBox<>(new String[]{ToolsMessages.schedule_unit_minutes, ToolsMessages.schedule_unit_hours});
    private final JTextField dailyField = new JTextField(5);
    private final JTextField weekdaysField = new JTextField(5);

    ScheduleWhenPanel(@NotNull ScheduleTiming timing) {
        super(new GridBagLayout());
        ButtonGroup group = new ButtonGroup();
        group.add(intervalButton);
        group.add(dailyButton);
        group.add(weekdaysButton);
        switch (timing.getKind()) {
            case INTERVAL -> intervalButton.setSelected(true);
            case DAILY -> dailyButton.setSelected(true);
            case WEEKDAYS -> weekdaysButton.setSelected(true);
        }
        boolean hours = timing.getIntervalMinutes() % 60 == 0;
        intervalSpinner.setValue(hours ? timing.getIntervalMinutes() / 60 : timing.getIntervalMinutes());
        unitCombo.setSelectedIndex(hours ? 1 : 0);
        dailyField.setText(timing.getTime().toString());
        weekdaysField.setText(timing.getTime().toString());
        dailyField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "hh:mm"); //$NON-NLS-1$
        weekdaysField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "hh:mm"); //$NON-NLS-1$

        addCell(intervalButton, 0, 0);
        addCell(intervalSpinner, 1, 0);
        addCell(unitCombo, 2, 0);
        addCell(dailyButton, 0, 1);
        addCell(dailyField, 1, 1);
        addCell(weekdaysButton, 0, 2);
        addCell(weekdaysField, 1, 2);
    }

    /**
     * Writes the chosen time into the schedule.
     *
     * @return false when the hh:mm text is not a time (the schedule is then unchanged in its time)
     */
    boolean apply(@NotNull ScheduleTiming timing) {
        try {
            if (intervalButton.isSelected()) {
                timing.setKind(ScheduleTiming.Kind.INTERVAL);
                int amount = ((Number) intervalSpinner.getValue()).intValue();
                timing.setIntervalMinutes(unitCombo.getSelectedIndex() == 1 ? amount * 60 : amount);
            } else if (dailyButton.isSelected()) {
                timing.setTime(LocalTime.parse(dailyField.getText().trim()));
                timing.setKind(ScheduleTiming.Kind.DAILY);
            } else {
                timing.setTime(LocalTime.parse(weekdaysField.getText().trim()));
                timing.setKind(ScheduleTiming.Kind.WEEKDAYS);
            }
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private void addCell(@NotNull JComponent component, int x, int y) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x;
        c.gridy = y;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, 4, 6);
        add(component, c);
    }
}
