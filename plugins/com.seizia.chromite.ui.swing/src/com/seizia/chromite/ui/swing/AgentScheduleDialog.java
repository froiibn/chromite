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
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgentSchedule;
import com.seizia.chromite.ext.mssql.model.agent.SQLServerAgentSql;
import com.seizia.chromite.ui.swing.internal.AgentMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * New Job Schedule / Job Schedule Properties: when a job runs (recurring daily, weekly or monthly, once, when the agent
 * starts, when the CPUs become idle), how often during the day, and from when to when.
 *
 * 설계: SSMS 작업 일정 창의 배치(일정 유형, 한 번 발생, 빈도, 일별 빈도, 기간, 요약)를 따른다. 칸 값은 msdb의 {@code freq_*}·{@code active_*}
 * 숫자로 바로 옮긴다({@link SQLServerAgentSchedule}). 요약은 서버의 {@code schedule_description}(영어) 대신 화면 언어로 만든다
 * ({@link #describe}): 아직 저장하지 않은 일정에도 같은 문구를 보여 주기 위해서다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-sqlserver-agent.md)
 */
final class AgentScheduleDialog extends JDialog {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss"); //$NON-NLS-1$
    /** Schedule type combo order */
    private static final int TYPE_RECURRING = 0;
    private static final int TYPE_ONCE = 1;
    private static final int TYPE_AGENT_START = 2;
    private static final int TYPE_IDLE = 3;
    /** Occurs combo order */
    private static final int OCCURS_DAILY = 0;
    private static final int OCCURS_WEEKLY = 1;
    private static final int OCCURS_MONTHLY = 2;
    /** freq_interval bits of the days of a weekly schedule, Monday first as the check boxes */
    private static final int[] WEEK_BITS = {2, 4, 8, 16, 32, 64, 1};
    private static final int[] RELATIVE_VALUES = {1, 2, 4, 8, 16};

    private final SQLServerAgentSchedule schedule;
    private boolean accepted;

    private final JTextField nameField = new JTextField();
    private final JCheckBox enabledCheck = new JCheckBox(AgentMessages.agent_enabled);
    private final JComboBox<String> typeCombo = new JComboBox<>(new String[] {
        AgentMessages.agent_schedule_type_recurring, AgentMessages.agent_schedule_type_once,
        AgentMessages.agent_schedule_type_agent_start, AgentMessages.agent_schedule_type_idle});
    private final JSpinner onceDate = createDateSpinner();
    private final JSpinner onceTime = createTimeSpinner();
    private final JComboBox<String> occursCombo = new JComboBox<>(new String[] {
        AgentMessages.agent_schedule_daily, AgentMessages.agent_schedule_weekly, AgentMessages.agent_schedule_monthly});
    private final JSpinner recursSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 100, 1));
    private final JLabel recursUnit = new JLabel();
    private final JCheckBox[] dayChecks = new JCheckBox[7];
    private final JRadioButton monthDayRadio = new JRadioButton(AgentMessages.agent_schedule_month_day);
    private final JSpinner monthDaySpinner = new JSpinner(new SpinnerNumberModel(1, 1, 31, 1));
    private final JRadioButton monthRelativeRadio = new JRadioButton(AgentMessages.agent_schedule_month_relative);
    private final JComboBox<String> relativeCombo = new JComboBox<>(new String[] {
        AgentMessages.agent_schedule_first, AgentMessages.agent_schedule_second, AgentMessages.agent_schedule_third,
        AgentMessages.agent_schedule_fourth, AgentMessages.agent_schedule_last});
    private final JComboBox<String> relativeDayCombo = new JComboBox<>(getRelativeDayNames());
    private final JRadioButton dailyOnceRadio = new JRadioButton(AgentMessages.agent_schedule_occurs_once_at);
    private final JSpinner dailyOnceTime = createTimeSpinner();
    private final JRadioButton dailyEveryRadio = new JRadioButton(AgentMessages.agent_schedule_occurs_every);
    private final JSpinner everySpinner = new JSpinner(new SpinnerNumberModel(1, 1, 100, 1));
    private final JComboBox<String> everyUnitCombo = new JComboBox<>(new String[] {
        AgentMessages.agent_schedule_unit_hours, AgentMessages.agent_schedule_unit_minutes, AgentMessages.agent_schedule_unit_seconds});
    private final JSpinner startTime = createTimeSpinner();
    private final JSpinner endTime = createTimeSpinner();
    private final JSpinner startDate = createDateSpinner();
    private final JRadioButton endDateRadio = new JRadioButton(AgentMessages.agent_schedule_end_date);
    private final JSpinner endDate = createDateSpinner();
    private final JRadioButton noEndRadio = new JRadioButton(AgentMessages.agent_schedule_no_end_date);
    private final JTextArea summary = DatabaseTaskDialog.FormPanel.note(""); //$NON-NLS-1$
    private final JPanel oncePanel;
    private final JPanel frequencyPanel;
    private final JPanel weekPanel;
    private final JPanel monthPanel;
    private final JPanel dailyPanel;
    private final JPanel durationPanel;

    private AgentScheduleDialog(@NotNull Component owner, @NotNull SQLServerAgentSchedule schedule, boolean isNew) {
        super(SwingUtilities.getWindowAncestor(owner), isNew ? AgentMessages.agent_schedule_new_title
            : NLS.bind(AgentMessages.agent_schedule_title, schedule.getName()), ModalityType.APPLICATION_MODAL);
        this.schedule = schedule;
        setIconImages(ChromiteIcons.getWindowIcons());
        String[] dayNames = getDayNames();
        for (int i = 0; i < 7; i++) {
            dayChecks[i] = new JCheckBox(dayNames[i]);
        }
        ButtonGroup monthGroup = new ButtonGroup();
        monthGroup.add(monthDayRadio);
        monthGroup.add(monthRelativeRadio);
        ButtonGroup dailyGroup = new ButtonGroup();
        dailyGroup.add(dailyOnceRadio);
        dailyGroup.add(dailyEveryRadio);
        ButtonGroup endGroup = new ButtonGroup();
        endGroup.add(endDateRadio);
        endGroup.add(noEndRadio);

        // Name and type
        Grid head = new Grid();
        head.row(AgentMessages.agent_name, nameField, enabledCheck);
        head.row(AgentMessages.agent_schedule_type, typeCombo, null);

        oncePanel = section(AgentMessages.agent_schedule_section_once);
        Grid once = new Grid();
        once.row(AgentMessages.agent_schedule_date, onceDate, labeled(AgentMessages.agent_schedule_time, onceTime));
        oncePanel.add(once.panel);

        frequencyPanel = section(AgentMessages.agent_schedule_section_frequency);
        Grid frequency = new Grid();
        frequency.row(AgentMessages.agent_schedule_occurs, occursCombo, null);
        frequency.row(AgentMessages.agent_schedule_recurs_every, recursSpinner, recursUnit);
        frequencyPanel.add(frequency.panel);
        weekPanel = new JPanel(new GridLayout(2, 4, 8, 2));
        for (JCheckBox check : dayChecks) {
            weekPanel.add(check);
        }
        weekPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        frequencyPanel.add(weekPanel);
        monthPanel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(2, 0, 2, 6);
        c.gridy = 0;
        monthPanel.add(monthDayRadio, c);
        monthPanel.add(monthDaySpinner, c);
        c.gridy = 1;
        monthPanel.add(monthRelativeRadio, c);
        monthPanel.add(relativeCombo, c);
        monthPanel.add(relativeDayCombo, c);
        monthPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        frequencyPanel.add(monthPanel);

        dailyPanel = section(AgentMessages.agent_schedule_section_daily);
        Grid daily = new Grid();
        daily.row(dailyOnceRadio, dailyOnceTime, null);
        JPanel every = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        every.add(everySpinner);
        every.add(everyUnitCombo);
        daily.row(dailyEveryRadio, every, null);
        daily.row(AgentMessages.agent_schedule_starting_at, startTime, null);
        daily.row(AgentMessages.agent_schedule_ending_at, endTime, null);
        dailyPanel.add(daily.panel);

        durationPanel = section(AgentMessages.agent_schedule_section_duration);
        Grid duration = new Grid();
        duration.row(AgentMessages.agent_schedule_start_date, startDate, null);
        duration.row(endDateRadio, endDate, null);
        duration.row(noEndRadio, new JLabel(), null);
        durationPanel.add(duration.panel);

        JPanel summaryPanel = section(AgentMessages.agent_schedule_section_summary);
        summary.setAlignmentX(Component.LEFT_ALIGNMENT);
        summaryPanel.add(summary);

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(12, 14, 6, 14));
        head.panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(head.panel);
        for (JPanel panel : List.of(oncePanel, frequencyPanel, dailyPanel, durationPanel, summaryPanel)) {
            body.add(panel);
        }
        body.add(Box.createVerticalGlue());

        JButton okButton = new JButton(AgentMessages.agent_ok);
        okButton.addActionListener(e -> accept());
        JButton cancelButton = new JButton(AgentMessages.agent_cancel);
        cancelButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setBorder(BorderFactory.createEmptyBorder(8, 12, 10, 12));
        buttons.add(okButton);
        buttons.add(cancelButton);

        JPanel content = new JPanel(new BorderLayout());
        JScrollPane scroll = new JScrollPane(body, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        content.add(scroll, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        south.add(new JSeparator(), BorderLayout.NORTH);
        south.add(buttons, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(okButton);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        load();
        // Every control change updates the visible sections and the summary
        Runnable changed = this::updateControls;
        for (JComboBox<?> combo : List.of(typeCombo, occursCombo, relativeCombo, relativeDayCombo, everyUnitCombo)) {
            combo.addActionListener(e -> changed.run());
        }
        for (AbstractButton button : List.of(monthDayRadio, monthRelativeRadio, dailyOnceRadio, dailyEveryRadio, endDateRadio, noEndRadio, enabledCheck)) {
            button.addActionListener(e -> changed.run());
        }
        for (JCheckBox check : dayChecks) {
            check.addActionListener(e -> changed.run());
        }
        for (JSpinner spinner : List.of(onceDate, onceTime, recursSpinner, monthDaySpinner, dailyOnceTime, everySpinner, startTime, endTime, startDate, endDate)) {
            spinner.addChangeListener(e -> changed.run());
        }
        updateControls();
        setSize(640, 720);
        setMinimumSize(new Dimension(560, 520));
        setLocationRelativeTo(getOwner());
    }

    /**
     * Edits a schedule in place.
     *
     * @return true if the user accepted the changes
     */
    static boolean edit(@NotNull Component owner, @NotNull SQLServerAgentSchedule schedule, boolean isNew) {
        AgentScheduleDialog dialog = new AgentScheduleDialog(owner, schedule, isNew);
        dialog.setVisible(true);
        return dialog.accepted;
    }

    //////////////////////////////////////////////////////////
    // Values <-> controls

    private void load() {
        nameField.setText(schedule.getName());
        enabledCheck.setSelected(schedule.isEnabled());
        int freqType = schedule.getFreqType();
        typeCombo.setSelectedIndex(switch (freqType) {
            case SQLServerAgentSchedule.FREQ_ONCE -> TYPE_ONCE;
            case SQLServerAgentSchedule.FREQ_AGENT_START -> TYPE_AGENT_START;
            case SQLServerAgentSchedule.FREQ_IDLE -> TYPE_IDLE;
            default -> TYPE_RECURRING;
        });
        LocalDate start = Objects.requireNonNullElse(SQLServerAgentSql.toLocalDate(schedule.getActiveStartDate()), LocalDate.now());
        onceDate.setValue(toDate(start, LocalTime.MIDNIGHT));
        onceTime.setValue(toDate(LocalDate.now(), SQLServerAgentSql.toLocalTime(schedule.getActiveStartTime())));
        occursCombo.setSelectedIndex(switch (freqType) {
            case SQLServerAgentSchedule.FREQ_WEEKLY -> OCCURS_WEEKLY;
            case SQLServerAgentSchedule.FREQ_MONTHLY, SQLServerAgentSchedule.FREQ_MONTHLY_RELATIVE -> OCCURS_MONTHLY;
            default -> OCCURS_DAILY;
        });
        int recurs = freqType == SQLServerAgentSchedule.FREQ_DAILY ? schedule.getFreqInterval() : schedule.getFreqRecurrenceFactor();
        recursSpinner.setValue(Math.max(1, Math.min(100, recurs)));
        for (int i = 0; i < 7; i++) {
            dayChecks[i].setSelected(freqType == SQLServerAgentSchedule.FREQ_WEEKLY ? (schedule.getFreqInterval() & WEEK_BITS[i]) != 0 : i == 0);
        }
        if (freqType == SQLServerAgentSchedule.FREQ_MONTHLY_RELATIVE) {
            monthRelativeRadio.setSelected(true);
            for (int i = 0; i < RELATIVE_VALUES.length; i++) {
                if (RELATIVE_VALUES[i] == schedule.getFreqRelativeInterval()) {
                    relativeCombo.setSelectedIndex(i);
                }
            }
            relativeDayCombo.setSelectedIndex(Math.max(0, Math.min(9, schedule.getFreqInterval() - 1)));
        } else {
            monthDayRadio.setSelected(true);
            monthDaySpinner.setValue(freqType == SQLServerAgentSchedule.FREQ_MONTHLY ? Math.max(1, Math.min(31, schedule.getFreqInterval())) : 1);
        }
        LocalTime activeStart = SQLServerAgentSql.toLocalTime(schedule.getActiveStartTime());
        if (schedule.getFreqSubdayType() == SQLServerAgentSchedule.SUBDAY_ONCE || schedule.getFreqSubdayType() == 0) {
            dailyOnceRadio.setSelected(true);
        } else {
            dailyEveryRadio.setSelected(true);
            everySpinner.setValue(Math.max(1, Math.min(100, schedule.getFreqSubdayInterval())));
            everyUnitCombo.setSelectedIndex(switch (schedule.getFreqSubdayType()) {
                case SQLServerAgentSchedule.SUBDAY_MINUTES -> 1;
                case SQLServerAgentSchedule.SUBDAY_SECONDS -> 2;
                default -> 0;
            });
        }
        dailyOnceTime.setValue(toDate(LocalDate.now(), activeStart));
        startTime.setValue(toDate(LocalDate.now(), activeStart));
        endTime.setValue(toDate(LocalDate.now(), SQLServerAgentSql.toLocalTime(schedule.getActiveEndTime())));
        startDate.setValue(toDate(start, LocalTime.MIDNIGHT));
        LocalDate end = schedule.getActiveEndDate() >= SQLServerAgentSchedule.NO_END_DATE ? null : SQLServerAgentSql.toLocalDate(schedule.getActiveEndDate());
        if (end == null) {
            noEndRadio.setSelected(true);
            endDate.setValue(toDate(start, LocalTime.MIDNIGHT));
        } else {
            endDateRadio.setSelected(true);
            endDate.setValue(toDate(end, LocalTime.MIDNIGHT));
        }
    }

    /**
     * Writes the controls into a schedule.
     */
    private void store(@NotNull SQLServerAgentSchedule target) {
        target.setName(nameField.getText().trim());
        target.setEnabled(enabledCheck.isSelected());
        target.setFreqInterval(0);
        target.setFreqSubdayType(0);
        target.setFreqSubdayInterval(0);
        target.setFreqRelativeInterval(0);
        target.setFreqRecurrenceFactor(0);
        target.setActiveStartDate(SQLServerAgentSql.toDateInt(LocalDate.now()));
        target.setActiveEndDate(SQLServerAgentSchedule.NO_END_DATE);
        target.setActiveStartTime(0);
        target.setActiveEndTime(SQLServerAgentSchedule.END_OF_DAY);
        switch (typeCombo.getSelectedIndex()) {
            case TYPE_ONCE -> {
                target.setFreqType(SQLServerAgentSchedule.FREQ_ONCE);
                target.setActiveStartDate(SQLServerAgentSql.toDateInt(toLocalDate(onceDate)));
                target.setActiveStartTime(SQLServerAgentSql.toTimeInt(toLocalTime(onceTime)));
            }
            case TYPE_AGENT_START -> target.setFreqType(SQLServerAgentSchedule.FREQ_AGENT_START);
            case TYPE_IDLE -> target.setFreqType(SQLServerAgentSchedule.FREQ_IDLE);
            default -> {
                int recurs = ((Number) recursSpinner.getValue()).intValue();
                switch (occursCombo.getSelectedIndex()) {
                    case OCCURS_WEEKLY -> {
                        target.setFreqType(SQLServerAgentSchedule.FREQ_WEEKLY);
                        int days = 0;
                        for (int i = 0; i < 7; i++) {
                            if (dayChecks[i].isSelected()) {
                                days |= WEEK_BITS[i];
                            }
                        }
                        target.setFreqInterval(days);
                        target.setFreqRecurrenceFactor(recurs);
                    }
                    case OCCURS_MONTHLY -> {
                        if (monthRelativeRadio.isSelected()) {
                            target.setFreqType(SQLServerAgentSchedule.FREQ_MONTHLY_RELATIVE);
                            target.setFreqInterval(relativeDayCombo.getSelectedIndex() + 1);
                            target.setFreqRelativeInterval(RELATIVE_VALUES[relativeCombo.getSelectedIndex()]);
                        } else {
                            target.setFreqType(SQLServerAgentSchedule.FREQ_MONTHLY);
                            target.setFreqInterval(((Number) monthDaySpinner.getValue()).intValue());
                        }
                        target.setFreqRecurrenceFactor(recurs);
                    }
                    default -> {
                        target.setFreqType(SQLServerAgentSchedule.FREQ_DAILY);
                        target.setFreqInterval(recurs);
                    }
                }
                if (dailyOnceRadio.isSelected()) {
                    target.setFreqSubdayType(SQLServerAgentSchedule.SUBDAY_ONCE);
                    target.setActiveStartTime(SQLServerAgentSql.toTimeInt(toLocalTime(dailyOnceTime)));
                } else {
                    target.setFreqSubdayType(switch (everyUnitCombo.getSelectedIndex()) {
                        case 1 -> SQLServerAgentSchedule.SUBDAY_MINUTES;
                        case 2 -> SQLServerAgentSchedule.SUBDAY_SECONDS;
                        default -> SQLServerAgentSchedule.SUBDAY_HOURS;
                    });
                    target.setFreqSubdayInterval(((Number) everySpinner.getValue()).intValue());
                    target.setActiveStartTime(SQLServerAgentSql.toTimeInt(toLocalTime(startTime)));
                    target.setActiveEndTime(SQLServerAgentSql.toTimeInt(toLocalTime(endTime)));
                }
                target.setActiveStartDate(SQLServerAgentSql.toDateInt(toLocalDate(startDate)));
                if (endDateRadio.isSelected()) {
                    target.setActiveEndDate(SQLServerAgentSql.toDateInt(toLocalDate(endDate)));
                }
            }
        }
    }

    private void updateControls() {
        int type = typeCombo.getSelectedIndex();
        boolean recurring = type == TYPE_RECURRING;
        oncePanel.setVisible(type == TYPE_ONCE);
        frequencyPanel.setVisible(recurring);
        dailyPanel.setVisible(recurring);
        durationPanel.setVisible(recurring);
        int occurs = occursCombo.getSelectedIndex();
        weekPanel.setVisible(occurs == OCCURS_WEEKLY);
        monthPanel.setVisible(occurs == OCCURS_MONTHLY);
        recursUnit.setText(switch (occurs) {
            case OCCURS_WEEKLY -> AgentMessages.agent_schedule_unit_weeks;
            case OCCURS_MONTHLY -> AgentMessages.agent_schedule_unit_months;
            default -> AgentMessages.agent_schedule_unit_days;
        });
        monthDaySpinner.setEnabled(monthDayRadio.isSelected());
        relativeCombo.setEnabled(monthRelativeRadio.isSelected());
        relativeDayCombo.setEnabled(monthRelativeRadio.isSelected());
        dailyOnceTime.setEnabled(dailyOnceRadio.isSelected());
        everySpinner.setEnabled(dailyEveryRadio.isSelected());
        everyUnitCombo.setEnabled(dailyEveryRadio.isSelected());
        startTime.setEnabled(dailyEveryRadio.isSelected());
        endTime.setEnabled(dailyEveryRadio.isSelected());
        endDate.setEnabled(endDateRadio.isSelected());
        SQLServerAgentSchedule preview = schedule.copy();
        store(preview);
        summary.setText(describe(preview));
        revalidate();
        repaint();
    }

    private void accept() {
        String problem = null;
        if (CommonUtils.isEmptyTrimmed(nameField.getText())) {
            problem = AgentMessages.agent_error_no_name;
        } else if (typeCombo.getSelectedIndex() == TYPE_RECURRING && occursCombo.getSelectedIndex() == OCCURS_WEEKLY) {
            boolean anyDay = false;
            for (JCheckBox check : dayChecks) {
                anyDay |= check.isSelected();
            }
            if (!anyDay) {
                problem = AgentMessages.agent_schedule_error_no_day;
            }
        }
        if (problem == null && typeCombo.getSelectedIndex() == TYPE_RECURRING && endDateRadio.isSelected()
            && toLocalDate(endDate).isBefore(toLocalDate(startDate))) {
            problem = AgentMessages.agent_schedule_error_end_date;
        }
        if (problem != null) {
            JOptionPane.showMessageDialog(this, problem, getTitle(), JOptionPane.WARNING_MESSAGE);
            return;
        }
        store(schedule);
        accepted = true;
        dispose();
    }

    //////////////////////////////////////////////////////////
    // Summary

    /**
     * A sentence that says when the schedule runs, in the user's language.
     */
    @NotNull
    static String describe(@NotNull SQLServerAgentSchedule schedule) {
        LocalDate start = SQLServerAgentSql.toLocalDate(schedule.getActiveStartDate());
        String startText = start == null ? "" : DATE_FORMAT.format(start); //$NON-NLS-1$
        String startTimeText = TIME_FORMAT.format(SQLServerAgentSql.toLocalTime(schedule.getActiveStartTime()));
        String text = switch (schedule.getFreqType()) {
            case SQLServerAgentSchedule.FREQ_ONCE -> NLS.bind(AgentMessages.agent_schedule_desc_once, startText, startTimeText);
            case SQLServerAgentSchedule.FREQ_AGENT_START -> AgentMessages.agent_schedule_desc_agent_start;
            case SQLServerAgentSchedule.FREQ_IDLE -> AgentMessages.agent_schedule_desc_idle;
            default -> describeRecurring(schedule, startText, startTimeText);
        };
        return schedule.isEnabled() ? text : NLS.bind(AgentMessages.agent_schedule_desc_disabled, text);
    }

    @NotNull
    private static String describeRecurring(@NotNull SQLServerAgentSchedule schedule, @NotNull String startText, @NotNull String startTimeText) {
        String days = switch (schedule.getFreqType()) {
            case SQLServerAgentSchedule.FREQ_WEEKLY -> {
                String[] names = getDayNames();
                List<String> selected = new ArrayList<>();
                for (int i = 0; i < 7; i++) {
                    if ((schedule.getFreqInterval() & WEEK_BITS[i]) != 0) {
                        selected.add(names[i]);
                    }
                }
                yield NLS.bind(AgentMessages.agent_schedule_desc_weekly, schedule.getFreqRecurrenceFactor(), String.join(", ", selected)); //$NON-NLS-1$
            }
            case SQLServerAgentSchedule.FREQ_MONTHLY ->
                NLS.bind(AgentMessages.agent_schedule_desc_monthly, schedule.getFreqRecurrenceFactor(), schedule.getFreqInterval());
            case SQLServerAgentSchedule.FREQ_MONTHLY_RELATIVE -> {
                String relative = AgentMessages.agent_schedule_first;
                String[] relativeNames = {AgentMessages.agent_schedule_first, AgentMessages.agent_schedule_second,
                    AgentMessages.agent_schedule_third, AgentMessages.agent_schedule_fourth, AgentMessages.agent_schedule_last};
                for (int i = 0; i < RELATIVE_VALUES.length; i++) {
                    if (RELATIVE_VALUES[i] == schedule.getFreqRelativeInterval()) {
                        relative = relativeNames[i];
                    }
                }
                String[] dayNames = getRelativeDayNames();
                String day = dayNames[Math.max(0, Math.min(dayNames.length - 1, schedule.getFreqInterval() - 1))];
                yield NLS.bind(AgentMessages.agent_schedule_desc_monthly_relative, new Object[] {schedule.getFreqRecurrenceFactor(), relative, day});
            }
            default -> NLS.bind(AgentMessages.agent_schedule_desc_daily, schedule.getFreqInterval());
        };
        String time;
        if (schedule.getFreqSubdayType() == SQLServerAgentSchedule.SUBDAY_ONCE || schedule.getFreqSubdayType() == 0) {
            time = NLS.bind(AgentMessages.agent_schedule_desc_at, days, startTimeText);
        } else {
            String unit = switch (schedule.getFreqSubdayType()) {
                case SQLServerAgentSchedule.SUBDAY_MINUTES -> AgentMessages.agent_schedule_unit_minutes;
                case SQLServerAgentSchedule.SUBDAY_SECONDS -> AgentMessages.agent_schedule_unit_seconds;
                default -> AgentMessages.agent_schedule_unit_hours;
            };
            time = NLS.bind(AgentMessages.agent_schedule_desc_every, new Object[] {days, schedule.getFreqSubdayInterval(), unit, startTimeText,
                TIME_FORMAT.format(SQLServerAgentSql.toLocalTime(schedule.getActiveEndTime()))});
        }
        LocalDate end = schedule.getActiveEndDate() >= SQLServerAgentSchedule.NO_END_DATE ? null : SQLServerAgentSql.toLocalDate(schedule.getActiveEndDate());
        return end == null
            ? NLS.bind(AgentMessages.agent_schedule_desc_from, time, startText)
            : NLS.bind(AgentMessages.agent_schedule_desc_range, new Object[] {time, startText, DATE_FORMAT.format(end)});
    }

    /**
     * Day names, Monday first.
     */
    @NotNull
    private static String[] getDayNames() {
        return new String[] {AgentMessages.agent_day_monday, AgentMessages.agent_day_tuesday, AgentMessages.agent_day_wednesday,
            AgentMessages.agent_day_thursday, AgentMessages.agent_day_friday, AgentMessages.agent_day_saturday, AgentMessages.agent_day_sunday};
    }

    /**
     * Days of a monthly relative schedule, in {@code freq_interval} order (1 Sunday … 7 Saturday, 8 day, 9 weekday, 10 weekend day).
     */
    @NotNull
    private static String[] getRelativeDayNames() {
        return new String[] {AgentMessages.agent_day_sunday, AgentMessages.agent_day_monday, AgentMessages.agent_day_tuesday,
            AgentMessages.agent_day_wednesday, AgentMessages.agent_day_thursday, AgentMessages.agent_day_friday, AgentMessages.agent_day_saturday,
            AgentMessages.agent_day_day, AgentMessages.agent_day_weekday, AgentMessages.agent_day_weekend_day};
    }

    //////////////////////////////////////////////////////////
    // Layout and spinner helpers

    @NotNull
    private static JPanel section(@NotNull String title) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel label = new JLabel(title);
        label.putClientProperty(FlatClientProperties.STYLE_CLASS, "semibold"); //$NON-NLS-1$
        label.setBorder(BorderFactory.createEmptyBorder(12, 0, 6, 0));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(label);
        return panel;
    }

    @NotNull
    private static JPanel labeled(@NotNull String label, @NotNull JComponent control) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        panel.add(new JLabel(label));
        panel.add(control);
        return panel;
    }

    /**
     * Rows of label (or radio button) | control | extra, left aligned.
     */
    private static final class Grid {
        final JPanel panel = new JPanel(new GridBagLayout());
        private int row;

        Grid() {
            panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        }

        void row(@NotNull Object label, @NotNull JComponent control, @Nullable JComponent extra) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridy = row++;
            c.anchor = GridBagConstraints.WEST;
            c.insets = new Insets(2, 0, 4, 8);
            panel.add(label instanceof JComponent component ? component : new JLabel(label.toString()), c);
            c.fill = control instanceof JTextField || control instanceof JComboBox ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
            c.weightx = control instanceof JTextField ? 1 : 0;
            panel.add(control, c);
            c.fill = GridBagConstraints.NONE;
            c.weightx = extra == null ? 1 : 0;
            panel.add(extra != null ? extra : Box.createHorizontalGlue(), c);
        }
    }

    @NotNull
    private static JSpinner createDateSpinner() {
        JSpinner spinner = new JSpinner(new SpinnerDateModel());
        spinner.setEditor(new JSpinner.DateEditor(spinner, "yyyy-MM-dd")); //$NON-NLS-1$
        return spinner;
    }

    @NotNull
    private static JSpinner createTimeSpinner() {
        JSpinner spinner = new JSpinner(new SpinnerDateModel());
        spinner.setEditor(new JSpinner.DateEditor(spinner, "HH:mm:ss")); //$NON-NLS-1$
        return spinner;
    }

    @NotNull
    private static Date toDate(@NotNull LocalDate date, @NotNull LocalTime time) {
        return Date.from(LocalDateTime.of(date, time).atZone(ZoneId.systemDefault()).toInstant());
    }

    @NotNull
    private static LocalDate toLocalDate(@NotNull JSpinner spinner) {
        return ((Date) spinner.getValue()).toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    @NotNull
    private static LocalTime toLocalTime(@NotNull JSpinner spinner) {
        return ((Date) spinner.getValue()).toInstant().atZone(ZoneId.systemDefault()).toLocalTime().withNano(0);
    }
}
