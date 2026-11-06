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
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.SchedulerMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.time.DayOfWeek;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A small helper that builds a repeat expression from a few choices: an Oracle Scheduler calendar string
 * ({@code FREQ=DAILY;BYHOUR=2;BYMINUTE=0}) or a cron expression ({@code 0 2 * * *}, pg_cron).
 *
 * 설계: 두 문법은 쓰는 사람이 외우기 어렵지만 자주 쓰는 모양은 몇 가지뿐이라(N분·N시간마다, 매일·매주(요일)·매월(날짜) 정해진 시각)
 * 그것만 고르게 하고 결과 글을 바로 보여 준다. 그 밖의 식은 창의 입력 칸에 직접 쓴다(이 창은 칸을 채우기만 한다). 두 문법이 같은
 * 선택지를 쓰므로 한 창이 {@link Syntax}에 따라 글만 다르게 만든다. 요일 이름은 Java의 지역화된 이름을 쓴다.
 * MySQL 이벤트는 {@code EVERY n 단위}라 이 도우미가 필요 없다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-job-schedulers.md)
 */
final class ScheduleBuilderDialog extends JDialog {

    enum Syntax {
        ORACLE_CALENDAR,
        CRON
    }

    private enum Frequency {
        MINUTES,
        HOURS,
        DAILY,
        WEEKLY,
        MONTHLY;

        @Override
        public String toString() {
            return switch (this) {
                case MINUTES -> SchedulerMessages.builder_every_minutes;
                case HOURS -> SchedulerMessages.builder_every_hours;
                case DAILY -> SchedulerMessages.builder_daily;
                case WEEKLY -> SchedulerMessages.builder_weekly;
                case MONTHLY -> SchedulerMessages.builder_monthly;
            };
        }
    }

    private final Syntax syntax;
    private final JComboBox<Frequency> frequencyCombo = new JComboBox<>(Frequency.values());
    private final JSpinner intervalSpinner = new JSpinner(new SpinnerNumberModel(5, 1, 59, 1));
    private final JSpinner hourSpinner = new JSpinner(new SpinnerNumberModel(2, 0, 23, 1));
    private final JSpinner minuteSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 59, 1));
    private final JSpinner dayOfMonthSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 31, 1));
    private final List<JCheckBox> dayChecks = new ArrayList<>();
    private final JTextField resultField = new JTextField(40);
    private final JLabel intervalLabel = new JLabel(SchedulerMessages.builder_interval);
    private final JLabel timeLabel = new JLabel(SchedulerMessages.builder_time);
    private final JLabel daysLabel = new JLabel(SchedulerMessages.builder_days);
    private final JLabel dayOfMonthLabel = new JLabel(SchedulerMessages.builder_day_of_month);
    private final JPanel timePanel = new JPanel(new FlowLayout(FlowLayout.LEADING, UIScale.scale(4), 0));
    private final JPanel daysPanel = new JPanel(new FlowLayout(FlowLayout.LEADING, UIScale.scale(4), 0));
    @Nullable
    private String result;

    private ScheduleBuilderDialog(@NotNull Component owner, @NotNull Syntax syntax) {
        super(SwingUtilities.getWindowAncestor(owner), SchedulerMessages.builder_title, ModalityType.APPLICATION_MODAL);
        this.syntax = syntax;
        setIconImages(ChromiteIcons.getWindowIcons());
        frequencyCombo.setSelectedItem(Frequency.DAILY);
        for (DayOfWeek day : DayOfWeek.values()) {
            JCheckBox check = new JCheckBox(day.getDisplayName(TextStyle.SHORT, Locale.getDefault()));
            check.setSelected(day == DayOfWeek.MONDAY);
            check.addActionListener(e -> update());
            dayChecks.add(check);
            daysPanel.add(check);
        }
        timePanel.setOpaque(false);
        daysPanel.setOpaque(false);
        timePanel.add(hourSpinner);
        timePanel.add(new JLabel(":")); //$NON-NLS-1$
        timePanel.add(minuteSpinner);
        resultField.setEditable(false);
        frequencyCombo.addActionListener(e -> update());
        intervalSpinner.addChangeListener(e -> update());
        hourSpinner.addChangeListener(e -> update());
        minuteSpinner.addChangeListener(e -> update());
        dayOfMonthSpinner.addChangeListener(e -> update());

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(12), UIScale.scale(12), UIScale.scale(8), UIScale.scale(12)));
        int row = 0;
        addRow(form, row++, new JLabel(SchedulerMessages.builder_frequency), frequencyCombo);
        addRow(form, row++, intervalLabel, intervalSpinner);
        addRow(form, row++, timeLabel, timePanel);
        addRow(form, row++, daysLabel, daysPanel);
        addRow(form, row++, dayOfMonthLabel, dayOfMonthSpinner);
        addRow(form, row, new JLabel(SchedulerMessages.builder_result), resultField);

        JButton okButton = new JButton(CoreMessages.common_ok);
        JButton cancelButton = new JButton(CoreMessages.common_cancel);
        okButton.addActionListener(e -> {
            result = resultField.getText();
            dispose();
        });
        cancelButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING));
        buttons.add(okButton);
        buttons.add(cancelButton);
        getRootPane().setDefaultButton(okButton);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(form, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        update();
        pack();
        setLocationRelativeTo(getOwner());
    }

    private static void addRow(@NotNull JPanel form, int row, @NotNull JComponent label, @NotNull JComponent control) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.anchor = GridBagConstraints.LINE_START;
        c.insets = new Insets(UIScale.scale(3), 0, UIScale.scale(3), UIScale.scale(10));
        form.add(label, c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(UIScale.scale(3), 0, UIScale.scale(3), 0);
        form.add(control, c);
    }

    /**
     * Shows the helper.
     *
     * @return the built expression, or null if canceled
     */
    @Nullable
    static String show(@NotNull Component owner, @NotNull Syntax syntax) {
        ScheduleBuilderDialog dialog = new ScheduleBuilderDialog(owner, syntax);
        dialog.setVisible(true);
        return dialog.result;
    }

    private void update() {
        Frequency frequency = (Frequency) frequencyCombo.getSelectedItem();
        if (frequency == null) {
            return;
        }
        boolean interval = frequency == Frequency.MINUTES || frequency == Frequency.HOURS;
        intervalLabel.setVisible(interval);
        intervalSpinner.setVisible(interval);
        timeLabel.setVisible(!interval);
        timePanel.setVisible(!interval);
        daysLabel.setVisible(frequency == Frequency.WEEKLY);
        daysPanel.setVisible(frequency == Frequency.WEEKLY);
        dayOfMonthLabel.setVisible(frequency == Frequency.MONTHLY);
        dayOfMonthSpinner.setVisible(frequency == Frequency.MONTHLY);
        ((SpinnerNumberModel) intervalSpinner.getModel()).setMaximum(frequency == Frequency.HOURS ? 23 : 59);
        if ((Integer) intervalSpinner.getValue() > (frequency == Frequency.HOURS ? 23 : 59)) {
            intervalSpinner.setValue(1);
        }
        resultField.setText(build(frequency));
        // From the start: the frequency part matters most and a long expression would show only its end
        resultField.setCaretPosition(0);
        if (isShowing()) {
            pack();
        }
    }

    @NotNull
    private String build(@NotNull Frequency frequency) {
        int every = (Integer) intervalSpinner.getValue();
        int hour = (Integer) hourSpinner.getValue();
        int minute = (Integer) minuteSpinner.getValue();
        int dayOfMonth = (Integer) dayOfMonthSpinner.getValue();
        List<DayOfWeek> days = new ArrayList<>();
        for (int i = 0; i < dayChecks.size(); i++) {
            if (dayChecks.get(i).isSelected()) {
                days.add(DayOfWeek.values()[i]);
            }
        }
        if (syntax == Syntax.ORACLE_CALENDAR) {
            String time = ";BYHOUR=" + hour + ";BYMINUTE=" + minute + ";BYSECOND=0"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return switch (frequency) {
                case MINUTES -> "FREQ=MINUTELY;INTERVAL=" + every; //$NON-NLS-1$
                case HOURS -> "FREQ=HOURLY;INTERVAL=" + every; //$NON-NLS-1$
                case DAILY -> "FREQ=DAILY" + time; //$NON-NLS-1$
                case WEEKLY -> "FREQ=WEEKLY" + (days.isEmpty() ? "" : ";BYDAY=" + String.join(",", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    days.stream().map(day -> day.name().substring(0, 3)).toList())) + time;
                case MONTHLY -> "FREQ=MONTHLY;BYMONTHDAY=" + dayOfMonth + time; //$NON-NLS-1$
            };
        }
        return switch (frequency) {
            case MINUTES -> "*/" + every + " * * * *"; //$NON-NLS-1$ //$NON-NLS-2$
            case HOURS -> "0 */" + every + " * * *"; //$NON-NLS-1$ //$NON-NLS-2$
            case DAILY -> minute + " " + hour + " * * *"; //$NON-NLS-1$ //$NON-NLS-2$
            // cron numbers the days 0 (Sunday) to 6
            case WEEKLY -> minute + " " + hour + " * * " + (days.isEmpty() ? "*" : String.join(",", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                days.stream().map(day -> String.valueOf(day.getValue() % 7)).toList()));
            case MONTHLY -> minute + " " + hour + " " + dayOfMonth + " * *"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        };
    }
}
