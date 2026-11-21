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

import com.seizia.chromite.ext.altibase.model.AltibaseJob;
import com.seizia.chromite.ext.altibase.model.AltibaseJobDDL;
import com.seizia.chromite.model.impl.DBObjectNameCaseTransformer;
import com.seizia.chromite.ui.swing.internal.CreateObjectMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * The dialogs of Altibase-family jobs (Altibase and ShardSphere share the model): New Job and Job Settings.
 *
 * 설계: 다른 "New …"·설정 대화상자({@link AltibaseTablespaceDialogs}, {@link AltibaseTableTtlDialog})와 같은 틀이다({@link DesignerDialogs#showDialog}의
 * OK/취소, 칸 이름 + 값, 오류는 단추 줄의 한 줄). 대화상자는 값만 받고 문장은 모델({@link AltibaseJobDDL})이 만든다. 설명 문구는 두지 않는다.
 * <ul>
 *     <li>칸: 이름(새 작업만), 프로시저 호출(사용자가 쓴 글 그대로, {@code SYS.PROC}·{@code PROC(1)}), 시작, 끝(비우면 끝 없음), 주기(수와 단위,
 *     단위 "없음"은 한 번 실행), 사용(새 작업만: 켜고 끄기는 설정이 아니라 메뉴의 사용 / 사용 안 함), 주석.</li>
 *     <li>시각은 {@code yyyy-MM-dd HH:mm:ss} 글로 받는다(새 작업의 시작은 지금 시각으로 채움). 다른 모양, 시작보다 이른 끝은 대화상자가 막는다
 *     (서버는 끝이 시작보다 일러도 받는다). 시작은 늘 있어야 한다: Altibase가 START 없는 작업을 받지 않는다.</li>
 *     <li>주기 수는 숫자만 받는 칸({@link NumberInputFilter})이고 1 이상이어야 한다. 단위는 두 서버가 받는 다섯 개다.</li>
 *     <li>설정 대화상자는 지금 값으로 채우고 바뀐 칸만 문장이 된다(절마다 한 문장). 주기가 있는 작업에서 주기를 없애는 것은 두지 않았다:
 *     서버는 {@code INTERVAL 0}을 받지만 스케줄러가 그 작업을 어떻게 돌리는지 확인할 수 없었다(두 시험 서버 모두 스케줄러가 꺼져 있음).
 *     대화상자에 없는 단위(ShardSphere의 초)로 도는 작업은 주기 칸을 바꿀 수 없다.</li>
 * </ul>
 * EDT에서 부른다.
 *
 * 변경 이력:
 * - 2026-11-21 최초 작성(앱 변경 #154, docs/chromite/altibase.md "작업 만들기·바꾸기·삭제")
 */
final class AltibaseJobDialogs {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"); //$NON-NLS-1$

    /**
     * A unit in the unit combo; a null unit is "none" (the job runs once).
     */
    private record UnitChoice(@Nullable AltibaseJobDDL.Unit unit, @NotNull String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * A time field that does not hold a time in the dialog's format.
     */
    private static final class InvalidValue extends Exception {
        private final String message;

        InvalidValue(@NotNull String message) {
            this.message = message;
        }
    }

    /**
     * The fields both dialogs have.
     */
    private static final class Fields {
        final JTextField execField = new JTextField(28);
        final JTextField startField = new JTextField(28);
        final JTextField endField = new JTextField(28);
        final JTextField amountField = new JTextField(6);
        final JComboBox<UnitChoice> unitCombo = new JComboBox<>();
        final JTextField commentField = new JTextField(28);

        Fields(boolean withNone) {
            NumberInputFilter.install(amountField, false);
            if (withNone) {
                unitCombo.addItem(new UnitChoice(null, CreateObjectMessages.create_unit_none));
            }
            for (AltibaseJobDDL.Unit unit : AltibaseJobDDL.Unit.values()) {
                unitCombo.addItem(new UnitChoice(unit, unitLabel(unit)));
            }
            unitCombo.addActionListener(e -> syncAmount());
            syncAmount();
        }

        void syncAmount() {
            amountField.setEnabled(unitCombo.isEnabled() && selectedUnit() != null);
        }

        @Nullable
        AltibaseJobDDL.Unit selectedUnit() {
            return ((UnitChoice) Objects.requireNonNull(unitCombo.getSelectedItem())).unit();
        }

        void selectUnit(@Nullable AltibaseJobDDL.Unit unit) {
            for (int i = 0; i < unitCombo.getItemCount(); i++) {
                if (unitCombo.getItemAt(i).unit() == unit) {
                    unitCombo.setSelectedIndex(i);
                }
            }
            syncAmount();
        }

        int addTo(@NotNull JPanel form, int row) {
            DesignerDialogs.addRow(form, row++, CreateObjectMessages.create_label_procedure, execField);
            DesignerDialogs.addRow(form, row++, CreateObjectMessages.create_label_start, startField);
            DesignerDialogs.addRow(form, row++, CreateObjectMessages.create_label_end, endField);
            JPanel interval = new JPanel(new BorderLayout(6, 0));
            interval.add(amountField, BorderLayout.WEST);
            interval.add(unitCombo, BorderLayout.CENTER);
            DesignerDialogs.addRow(form, row++, CreateObjectMessages.create_label_interval, interval);
            return row;
        }

        @NotNull
        String exec() throws InvalidValue {
            String exec = execField.getText().trim();
            // The call goes into the statement as written: one call, no statement separator
            if (exec.isEmpty() || exec.indexOf(';') >= 0 || exec.indexOf('\n') >= 0) {
                throw new InvalidValue(CreateObjectMessages.create_error_procedure);
            }
            return exec;
        }

        @Nullable
        LocalDateTime start() throws InvalidValue {
            return parseTime(startField);
        }

        @Nullable
        LocalDateTime end(@Nullable LocalDateTime start) throws InvalidValue {
            LocalDateTime end = parseTime(endField);
            if (end != null && start != null && end.isBefore(start)) {
                throw new InvalidValue(CreateObjectMessages.create_error_end);
            }
            return end;
        }

        @Nullable
        AltibaseJobDDL.Interval interval() throws InvalidValue {
            AltibaseJobDDL.Unit unit = selectedUnit();
            if (unit == null) {
                return null;
            }
            int amount;
            try {
                amount = Integer.parseInt(amountField.getText().trim());
            } catch (NumberFormatException e) {
                amount = 0;
            }
            if (amount < 1) {
                throw new InvalidValue(CreateObjectMessages.create_error_interval);
            }
            return new AltibaseJobDDL.Interval(amount, unit);
        }
    }

    private AltibaseJobDialogs() {
    }

    //////////////////////////////////////////////////////////
    // New Job

    /**
     * New Job: name, procedure call, start, end, interval, enabled and comment.
     */
    @Nullable
    static NewObjectDialogs.Configurer askJob(@NotNull Component owner, @NotNull String title) {
        JTextField nameField = new JTextField(28);
        Fields fields = new Fields(true);
        // The next whole minute of this computer's clock; the filled field also shows the format
        fields.startField.setText(TIME_FORMAT.format(LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)));
        JCheckBox enabledBox = new JCheckBox(CreateObjectMessages.create_label_enabled);

        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;
        DesignerDialogs.addRow(form, row++, CreateObjectMessages.create_label_name, nameField);
        row = fields.addTo(form, row);
        DesignerDialogs.addRow(form, row++, "", enabledBox); //$NON-NLS-1$
        DesignerDialogs.addRow(form, row, CreateObjectMessages.create_label_comment, fields.commentField);

        record Values(String name, AltibaseJobDDL.Definition definition) {
        }
        Values values = DesignerDialogs.showDialog(owner, title, form, () -> {
            String name = nameField.getText().trim();
            if (name.isEmpty()) {
                return DesignerDialogs.error(CreateObjectMessages.create_error_name);
            }
            try {
                String exec = fields.exec();
                LocalDateTime start = fields.start();
                if (start == null) {
                    return DesignerDialogs.error(CreateObjectMessages.create_error_time);
                }
                return new Values(name, new AltibaseJobDDL.Definition(exec, start, fields.end(start), fields.interval(),
                    enabledBox.isSelected(), CommonUtils.nullIfEmpty(fields.commentField.getText().trim())));
            } catch (InvalidValue e) {
                return DesignerDialogs.error(e.message);
            }
        });
        if (values == null) {
            return null;
        }
        return (monitor, object, context) -> {
            AltibaseJob job = (AltibaseJob) object;
            job.setName(DBObjectNameCaseTransformer.transformName(job.getDataSource(), values.name()));
            job.setInitialDefinition(values.definition());
        };
    }

    //////////////////////////////////////////////////////////
    // Job Settings

    /**
     * Job Settings: the job's procedure call, start, end, interval and comment, filled with its current values.
     *
     * @return the change, {@link AltibaseTablespaceDialogs#UNCHANGED} if the values are the current ones, or null if canceled
     */
    @Nullable
    static Object askJobSettings(@NotNull Component owner, @NotNull String title, @NotNull AltibaseJob job) {
        AltibaseJobDDL.Interval currentInterval = job.getIntervalSetting();
        LocalDateTime currentStart = toTime(job.getStartTime());
        LocalDateTime currentEnd = toTime(job.getEndTime());
        String currentExec = CommonUtils.notEmpty(job.getExecQuery()).trim();
        String currentComment = CommonUtils.notEmpty(job.getComment());

        JTextField nameField = new JTextField(job.getName(), 28);
        nameField.setEditable(false);
        // Shown as the other dialogs show a read-only value, but never focused: the first focus goes to the procedure
        nameField.setFocusable(false);
        // "None" only for a job that runs once now (an interval is not removed, see the class comment)
        Fields fields = new Fields(currentInterval == null && !job.hasOtherIntervalUnit());
        fields.execField.setText(currentExec);
        fields.startField.setText(currentStart == null ? "" : TIME_FORMAT.format(currentStart)); //$NON-NLS-1$
        fields.endField.setText(currentEnd == null ? "" : TIME_FORMAT.format(currentEnd)); //$NON-NLS-1$
        if (currentInterval != null) {
            NumberInputFilter.setText(fields.amountField, String.valueOf(currentInterval.amount()));
            fields.selectUnit(currentInterval.unit());
        } else if (job.hasOtherIntervalUnit()) {
            // A unit the dialog does not offer: shown, not changed here
            fields.unitCombo.setModel(new DefaultComboBoxModel<>(new UnitChoice[]{new UnitChoice(null, job.getinterval())}));
            fields.unitCombo.setEnabled(false);
            fields.syncAmount();
        } else {
            fields.selectUnit(null);
        }
        fields.commentField.setText(currentComment);

        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;
        DesignerDialogs.addRow(form, row++, CreateObjectMessages.create_label_name, nameField);
        row = fields.addTo(form, row);
        DesignerDialogs.addRow(form, row, CreateObjectMessages.create_label_comment, fields.commentField);

        return DesignerDialogs.showDialog(owner, title, form, () -> {
            try {
                String exec = fields.exec();
                LocalDateTime start = fields.start();
                // A start is kept as it is when the job has none (the servers that allow that keep it so)
                if (start == null && currentStart != null) {
                    return DesignerDialogs.error(CreateObjectMessages.create_error_time);
                }
                LocalDateTime end = fields.end(start != null ? start : currentStart);
                AltibaseJobDDL.Interval interval = fields.unitCombo.isEnabled() ? fields.interval() : currentInterval;
                String comment = fields.commentField.getText().trim();
                AltibaseJobDDL.Change change = new AltibaseJobDDL.Change(
                    exec.equals(currentExec) ? null : exec,
                    Objects.equals(start, currentStart) ? null : start,
                    end == null || end.equals(currentEnd) ? null : end,
                    end == null && currentEnd != null,
                    Objects.equals(interval, currentInterval) ? null : interval,
                    comment.equals(currentComment) ? null : comment);
                return change.isEmpty() ? AltibaseTablespaceDialogs.UNCHANGED : change;
            } catch (InvalidValue e) {
                return DesignerDialogs.error(e.message);
            }
        });
    }

    //////////////////////////////////////////////////////////
    // Helpers

    @NotNull
    private static String unitLabel(@NotNull AltibaseJobDDL.Unit unit) {
        return switch (unit) {
            case YEAR -> CreateObjectMessages.create_unit_year;
            case MONTH -> CreateObjectMessages.create_unit_month;
            case DAY -> CreateObjectMessages.create_unit_day;
            case HOUR -> CreateObjectMessages.create_unit_hour;
            case MINUTE -> CreateObjectMessages.create_unit_minute;
        };
    }

    /**
     * The field's time; null if empty.
     *
     * @throws InvalidValue if it is not a time in the dialog's format
     */
    @Nullable
    private static LocalDateTime parseTime(@NotNull JTextField field) throws InvalidValue {
        String text = field.getText().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text, TIME_FORMAT);
        } catch (DateTimeParseException e) {
            throw new InvalidValue(CreateObjectMessages.create_error_time);
        }
    }

    /**
     * The catalog's time to the second, as the dialog shows it.
     */
    @Nullable
    private static LocalDateTime toTime(@Nullable Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime().truncatedTo(ChronoUnit.SECONDS);
    }
}
