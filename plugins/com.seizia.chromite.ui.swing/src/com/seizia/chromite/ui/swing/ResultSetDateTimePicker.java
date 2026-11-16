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
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.GridEditMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPDataKind;
import com.seizia.chromite.model.data.DBDAttributeBinding;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.WeekFields;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * 결과 표 날짜·시각 칸의 달력 선택기(SWT {@code DateTimeInlineEditor}의 Calendar 방식). 칸 편집기 오른쪽 단추나 Alt+↓로 연다.
 *
 * 설계:
 * <ul>
 *     <li>모달 대화상자로 했다. 표의 셀 편집기는 포커스가 같은 창의 다른 곳으로 가면 편집을 끝내는데({@code terminateEditOnFocusLost}),
 *     다른 창(대화상자)으로 가면 끝내지 않는다. 같은 창의 팝업으로 하면 날짜를 고르기 전에 편집이 끝난다.</li>
 *     <li>고른 값은 {@link java.util.Date}({@code java.sql.Date}, {@code Time}, {@code Timestamp})로 돌려주고 표가 열의 값 처리기로 값을 만든다.
 *     SWT도 달력 방식에서 {@code Date}를 값 처리기에 넘긴다. 그래서 값 처리기가 {@code Date}를 다루는 열이고 지금 값이 NULL이거나 {@code Date}일 때만
 *     연다({@link #supports}). 시간대가 있는 형식을 따로 다루는 처리기(예: SQL Server {@code datetimeoffset})나 원래 형식 그대로 보이기 설정의
 *     글자 값은 글자로 고친다.</li>
 *     <li>열 형식으로 보이는 칸을 정한다: {@code DATE}는 날짜만, {@code TIME}은 시각만, 그 밖에는 둘 다. 초 단위까지 고르며, 시각을 바꾸지 않으면
 *     원래 값의 소수 초를 남긴다(편집해도 정밀도가 줄지 않게).</li>
 *     <li>주의 첫 요일과 요일·달 이름은 OS 지역 설정을 따른다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: 날짜·시각 선택기 (docs/chromite/swing-grid-editing.md "형식별 편집기")
 */
final class ResultSetDateTimePicker extends JDialog {

    enum Kind {
        DATE,
        TIME,
        TIMESTAMP
    }

    private final Kind kind;
    @Nullable
    private final Date initial;
    private final JLabel monthLabel = new JLabel("", SwingConstants.CENTER);
    private final JPanel daysPanel = new JPanel(new GridLayout(0, 7, 2, 2));
    private final JSpinner timeSpinner;
    private YearMonth shownMonth;
    private LocalDate selectedDate;
    @Nullable
    private Date result;

    private ResultSetDateTimePicker(@NotNull Window owner, @NotNull Kind kind, @Nullable Date initial) {
        super(owner, GridEditMessages.gridedit_pick_title, ModalityType.APPLICATION_MODAL);
        this.kind = kind;
        this.initial = initial;
        LocalDateTime start = initial != null ? toLocalDateTime(initial) : LocalDateTime.now().withNano(0);
        selectedDate = start.toLocalDate();
        shownMonth = YearMonth.from(selectedDate);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        if (kind != Kind.TIME) {
            content.add(createCalendar(), BorderLayout.CENTER);
        }
        SpinnerDateModel timeModel = new SpinnerDateModel(Date.from(start.atZone(ZoneId.systemDefault()).toInstant()), null, null, Calendar.SECOND);
        timeSpinner = new JSpinner(timeModel);
        timeSpinner.setEditor(new JSpinner.DateEditor(timeSpinner, "HH:mm:ss")); //$NON-NLS-1$
        if (kind != Kind.DATE) {
            JPanel timeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            timeRow.add(new JLabel(GridEditMessages.gridedit_pick_time));
            timeRow.add(timeSpinner);
            content.add(timeRow, BorderLayout.SOUTH);
        }

        JButton nowButton = new JButton(kind == Kind.DATE ? GridEditMessages.gridedit_pick_today : GridEditMessages.gridedit_pick_now);
        nowButton.addActionListener(e -> {
            LocalDateTime now = LocalDateTime.now().withNano(0);
            selectedDate = now.toLocalDate();
            shownMonth = YearMonth.from(selectedDate);
            timeSpinner.setValue(Date.from(now.atZone(ZoneId.systemDefault()).toInstant()));
            fillDays();
        });
        JButton okButton = new JButton(CoreMessages.common_ok);
        okButton.addActionListener(e -> {
            result = makeValue();
            dispose();
        });
        JButton cancelButton = new JButton(CoreMessages.common_cancel);
        cancelButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(nowButton);
        buttons.add(okButton);
        buttons.add(cancelButton);

        JPanel root = new JPanel(new BorderLayout());
        root.add(content, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        south.setBorder(BorderFactory.createEmptyBorder(0, 12, 10, 12));
        south.add(buttons, BorderLayout.EAST);
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);
        // Picking a value is not a change of the database: OK is the default, Esc cancels
        getRootPane().setDefaultButton(okButton);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        fillDays();
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    /**
     * Whether a cell's value can be picked: a date/time column whose handler makes values from {@link Date}, holding
     * NULL or a {@link Date}.
     */
    static boolean supports(@NotNull DBDAttributeBinding binding, @Nullable Object value) {
        if (binding.getDataKind() != DBPDataKind.DATETIME || !(value == null || value instanceof Date)) {
            return false;
        }
        try {
            return Date.class.isAssignableFrom(binding.getValueHandler().getValueObjectType(binding));
        } catch (Exception e) {
            return false;
        }
    }

    @NotNull
    static Kind getKind(@NotNull DBDAttributeBinding binding) {
        return switch (binding.getTypeID()) {
            case Types.DATE -> Kind.DATE;
            case Types.TIME, Types.TIME_WITH_TIMEZONE -> Kind.TIME;
            default -> Kind.TIMESTAMP;
        };
    }

    /**
     * Shows the picker and waits.
     *
     * @param initial the cell's value, or null for the current date and time
     * @return the picked value, or null if canceled
     */
    @Nullable
    static Date pick(@NotNull Component owner, @NotNull Kind kind, @Nullable Date initial) {
        Window window = owner instanceof Window w ? w : SwingUtilities.getWindowAncestor(owner);
        ResultSetDateTimePicker picker = new ResultSetDateTimePicker(window, kind, initial);
        picker.setVisible(true);
        return picker.result;
    }

    @NotNull
    private JComponent createCalendar() {
        JButton previous = new JButton("‹"); //$NON-NLS-1$
        JButton next = new JButton("›"); //$NON-NLS-1$
        for (JButton button : new JButton[]{previous, next}) {
            button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
            button.setFocusable(false);
        }
        previous.setToolTipText(GridEditMessages.gridedit_pick_previous_month);
        next.setToolTipText(GridEditMessages.gridedit_pick_next_month);
        previous.addActionListener(e -> {
            shownMonth = shownMonth.minusMonths(1);
            fillDays();
        });
        next.addActionListener(e -> {
            shownMonth = shownMonth.plusMonths(1);
            fillDays();
        });
        monthLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold"); //$NON-NLS-1$
        JPanel header = new JPanel(new BorderLayout());
        header.add(previous, BorderLayout.WEST);
        header.add(monthLabel, BorderLayout.CENTER);
        header.add(next, BorderLayout.EAST);
        JPanel calendar = new JPanel(new BorderLayout(0, 4));
        calendar.add(header, BorderLayout.NORTH);
        calendar.add(daysPanel, BorderLayout.CENTER);
        return calendar;
    }

    /**
     * Day names and the days of the shown month, starting on the locale's first day of the week.
     */
    private void fillDays() {
        if (kind == Kind.TIME) {
            return;
        }
        Locale locale = Locale.getDefault();
        monthLabel.setText(shownMonth.format(DateTimeFormatter.ofLocalizedPattern("yMMMM").withLocale(locale))); //$NON-NLS-1$
        daysPanel.removeAll();
        DayOfWeek firstDay = WeekFields.of(locale).getFirstDayOfWeek();
        for (int i = 0; i < 7; i++) {
            JLabel name = new JLabel(firstDay.plus(i).getDisplayName(TextStyle.SHORT, locale), SwingConstants.CENTER);
            name.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
            daysPanel.add(name);
        }
        int offset = (shownMonth.atDay(1).getDayOfWeek().getValue() - firstDay.getValue() + 7) % 7;
        for (int i = 0; i < offset; i++) {
            daysPanel.add(new JLabel());
        }
        ButtonGroup group = new ButtonGroup();
        for (int day = 1; day <= shownMonth.lengthOfMonth(); day++) {
            LocalDate date = shownMonth.atDay(day);
            JToggleButton button = new JToggleButton(String.valueOf(day));
            button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
            button.setMargin(new Insets(2, 4, 2, 4));
            button.setSelected(date.equals(selectedDate));
            button.addActionListener(e -> selectedDate = date);
            // A double click picks the day at once
            button.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                        selectedDate = date;
                        result = makeValue();
                        dispose();
                    }
                }
            });
            group.add(button);
            daysPanel.add(button);
        }
        // Six weeks always, so the dialog keeps its size between months
        int cells = 7 + offset + shownMonth.lengthOfMonth();
        for (int i = cells; i < 7 * 7; i++) {
            daysPanel.add(new JLabel());
        }
        daysPanel.revalidate();
        daysPanel.repaint();
    }

    @NotNull
    private Date makeValue() {
        LocalTime time = LocalDateTime.ofInstant(((Date) timeSpinner.getValue()).toInstant(), ZoneId.systemDefault()).toLocalTime().withNano(0);
        return switch (kind) {
            case DATE -> java.sql.Date.valueOf(selectedDate);
            case TIME -> Time.valueOf(time);
            case TIMESTAMP -> {
                LocalDateTime picked = LocalDateTime.of(selectedDate, time);
                if (initial != null && toLocalDateTime(initial).toLocalTime().withNano(0).equals(time)) {
                    // The time was not changed: keep the fraction of a second
                    picked = picked.withNano(toLocalDateTime(initial).getNano());
                }
                yield Timestamp.valueOf(picked);
            }
        };
    }

    @NotNull
    private static LocalDateTime toLocalDateTime(@NotNull Date date) {
        if (date instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (date instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate().atStartOfDay();
        }
        if (date instanceof Time sqlTime) {
            return LocalDate.now().atTime(sqlTime.toLocalTime());
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(date.getTime()), ZoneId.systemDefault());
    }
}
