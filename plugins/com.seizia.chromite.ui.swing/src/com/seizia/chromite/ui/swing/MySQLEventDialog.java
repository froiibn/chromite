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
import com.seizia.chromite.ext.mysql.model.MySQLCatalog;
import com.seizia.chromite.ext.mysql.model.MySQLEvent;
import com.seizia.chromite.ext.mysql.model.scheduler.MySQLEventDefinition;
import com.seizia.chromite.ext.mysql.model.scheduler.MySQLEventScheduler;
import com.seizia.chromite.model.navigator.DBNDatabaseNode;
import com.seizia.chromite.runtime.DBInterruptedException;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.internal.SchedulerMessages;
import org.eclipse.osgi.util.NLS;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * New Event / Event Properties of MySQL and MariaDB: name, status, on completion, comment, body; schedule (one time
 * {@code AT} or recurring {@code EVERY n unit} with starts and ends).
 *
 * 설계: 문은 모델({@link MySQLEventDefinition})이 만들고, 확인·미리 보기·실행은 {@link SchedulerScriptDialog}가 한다. 창을 열기 전에 진행
 * 대화상자에서 이벤트의 현재 값과 {@code event_scheduler} 상태·EVENT 권한을 읽는다. 스케줄러가 꺼져 있으면 창 위에 경고와 켜는 명령을
 * 보인다(실행은 하지 않는다, 서버 설정이다). MySQL은 실행 기록을 남기지 않으므로 마지막 실행 시각만 보이고 그렇다고 적는다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-job-schedulers.md)
 */
final class MySQLEventDialog extends SchedulerScriptDialog {

    private record StatusChoice(@NotNull String status, @NotNull String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private final MySQLCatalog catalog;
    private final MySQLEventDefinition definition;
    @Nullable
    private final MySQLEventScheduler.Access access;
    private final JTextField nameField = new JTextField();
    private final JComboBox<StatusChoice> statusCombo = new JComboBox<>();
    private final JCheckBox preserveCheck = new JCheckBox(SchedulerMessages.mysql_preserve);
    private final JTextField commentField = new JTextField();
    private final SqlEditorPanel.SqlTextArea bodyText = new SqlEditorPanel.SqlTextArea();
    private final JRadioButton recurringRadio = new JRadioButton(SchedulerMessages.mysql_recurring);
    private final JRadioButton onceRadio = new JRadioButton(SchedulerMessages.mysql_once);
    private final JTextField intervalField = new JTextField(6);
    private final JComboBox<String> unitCombo = new JComboBox<>(MySQLEventDefinition.INTERVAL_UNITS.toArray(new String[0]));
    private final JTextField startsField = new JTextField();
    private final JTextField endsField = new JTextField();
    private final JTextField atField = new JTextField();

    private MySQLEventDialog(
        @NotNull Component owner,
        @NotNull MySQLCatalog catalog,
        @NotNull MySQLEventDefinition definition,
        @Nullable MySQLEventScheduler.Access access,
        @Nullable DBNDatabaseNode refreshNode,
        @NotNull ObjectExplorerPanel.QueryWindows queryWindows
    ) {
        super(owner, getTitle(catalog, definition), SchedulerCommands.mysql(catalog), refreshNode, queryWindows);
        this.catalog = catalog;
        this.definition = definition;
        this.access = access;

        // General
        nameField.setText(definition.getName());
        statusCombo.addItem(new StatusChoice(MySQLEventDefinition.STATUS_ENABLED, SchedulerMessages.mysql_status_enabled));
        statusCombo.addItem(new StatusChoice(MySQLEventDefinition.STATUS_DISABLED, SchedulerMessages.mysql_status_disabled));
        statusCombo.addItem(new StatusChoice(MySQLEventDefinition.STATUS_REPLICA_DISABLED, SchedulerMessages.mysql_status_replica_disabled));
        for (int i = 0; i < statusCombo.getItemCount(); i++) {
            if (statusCombo.getItemAt(i).status().equals(definition.getStatus())) {
                statusCombo.setSelectedIndex(i);
            }
        }
        preserveCheck.setSelected(definition.isPreserve());
        commentField.setText(CommonUtils.notEmpty(definition.getComment()));
        bodyText.setText(definition.getBody());
        bodyText.setCaretPosition(0);
        bodyText.discardAllEdits();
        RTextScrollPane bodyScroll = new RTextScrollPane(bodyText, true);
        bodyText.applyTheme();

        DatabaseTaskDialog.FormPanel general = new DatabaseTaskDialog.FormPanel();
        JComponent warning = createSchedulerWarning();
        if (warning != null) {
            general.addFull(warning);
        }
        general.addRow(SchedulerMessages.scheduler_name, nameField);
        general.addRow(SchedulerMessages.mysql_database, new JLabel(catalog.getName()));
        general.addRow(SchedulerMessages.mysql_status, statusCombo);
        general.addRow(SchedulerMessages.scheduler_comments, commentField);
        general.addFull(preserveCheck);
        if (!definition.isNew()) {
            general.addRow(SchedulerMessages.mysql_last_executed, new JLabel(definition.getLastExecuted() == null
                ? SchedulerMessages.mysql_never_executed : definition.getLastExecuted()));
        }
        general.addFull(DatabaseTaskDialog.FormPanel.note(SchedulerMessages.mysql_no_history));
        general.addFull(new JLabel(SchedulerMessages.mysql_body));
        general.addFiller(bodyScroll);

        // Schedule
        ButtonGroup group = new ButtonGroup();
        group.add(recurringRadio);
        group.add(onceRadio);
        recurringRadio.setSelected(definition.isRecurring());
        onceRadio.setSelected(!definition.isRecurring());
        intervalField.setText(definition.getIntervalValue());
        if (!MySQLEventDefinition.INTERVAL_UNITS.contains(definition.getIntervalField())) {
            unitCombo.addItem(definition.getIntervalField());
        }
        unitCombo.setSelectedItem(definition.getIntervalField());
        startsField.setText(CommonUtils.notEmpty(definition.getStarts()));
        endsField.setText(CommonUtils.notEmpty(definition.getEnds()));
        atField.setText(CommonUtils.notEmpty(definition.getExecuteAt()));
        for (JTextField field : List.of(startsField, endsField, atField)) {
            field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, SchedulerMessages.mysql_date_placeholder);
        }
        JPanel everyPanel = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
        everyPanel.setOpaque(false);
        everyPanel.add(intervalField);
        everyPanel.add(unitCombo);
        recurringRadio.addActionListener(e -> updateScheduleFields());
        onceRadio.addActionListener(e -> updateScheduleFields());
        updateScheduleFields();

        DatabaseTaskDialog.FormPanel schedulePage = new DatabaseTaskDialog.FormPanel();
        schedulePage.addFull(recurringRadio);
        schedulePage.addRow(SchedulerMessages.mysql_every, everyPanel);
        schedulePage.addRow(SchedulerMessages.mysql_starts, startsField);
        schedulePage.addRow(SchedulerMessages.mysql_ends, endsField);
        schedulePage.addFull(onceRadio);
        schedulePage.addRow(SchedulerMessages.mysql_at, atField);
        schedulePage.addFull(DatabaseTaskDialog.FormPanel.note(definition.getTimeZone() == null
            ? SchedulerMessages.mysql_time_zone_new
            : NLS.bind(SchedulerMessages.mysql_time_zone, definition.getTimeZone())));
        schedulePage.finish();

        initDialog(List.of(new Page(SchedulerMessages.scheduler_page_general, general), new Page(SchedulerMessages.scheduler_page_schedule, schedulePage)));
    }

    @NotNull
    private static String getTitle(@NotNull MySQLCatalog catalog, @NotNull MySQLEventDefinition definition) {
        return definition.isNew() ? NLS.bind(SchedulerMessages.mysql_event_new_title, catalog.getName())
            : NLS.bind(SchedulerMessages.mysql_event_title, definition.getName());
    }

    /**
     * Reads the event and the event scheduler state in a progress dialog, then shows the dialog.
     *
     * @param event the event to edit, or null for a new event
     */
    static void open(
        @NotNull Component owner,
        @NotNull MySQLCatalog catalog,
        @Nullable MySQLEvent event,
        @Nullable DBNDatabaseNode refreshNode,
        @NotNull ObjectExplorerPanel.QueryWindows queryWindows
    ) {
        String title = event == null ? NLS.bind(SchedulerMessages.mysql_event_new_title, catalog.getName()) : NLS.bind(SchedulerMessages.mysql_event_title, event.getName());
        Object[] loaded;
        try {
            loaded = ProgressDialog.runTask(title, monitor -> new Object[] {
                event == null ? new MySQLEventDefinition(catalog) : MySQLEventDefinition.load(monitor, event),
                MySQLEventScheduler.readAccess(monitor, catalog)});
        } catch (DBInterruptedException e) {
            return;
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(title, SchedulerMessages.scheduler_error_read, e);
            return;
        }
        new MySQLEventDialog(owner, catalog, (MySQLEventDefinition) loaded[0], (MySQLEventScheduler.Access) loaded[1], refreshNode, queryWindows).open();
    }

    /**
     * The warning that events do not run while the event scheduler is OFF or DISABLED, with the command that turns it on.
     */
    @Nullable
    private JComponent createSchedulerWarning() {
        if (access == null || access.state() == MySQLEventScheduler.State.ON || access.state() == MySQLEventScheduler.State.UNKNOWN) {
            return null;
        }
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setOpaque(false);
        String text = access.state() == MySQLEventScheduler.State.OFF ? SchedulerMessages.mysql_warning_off : SchedulerMessages.mysql_warning_disabled;
        panel.add(new JLabel(text, UiIcons.get("warning"), SwingConstants.LEADING), BorderLayout.NORTH); //$NON-NLS-1$
        if (access.state() == MySQLEventScheduler.State.OFF) {
            JTextField command = new JTextField(MySQLEventScheduler.ENABLE_SCHEDULER_COMMAND);
            command.setEditable(false);
            command.setToolTipText(SchedulerMessages.mysql_command_tip);
            panel.add(command, BorderLayout.CENTER);
        }
        return panel;
    }

    private void updateScheduleFields() {
        boolean recurring = recurringRadio.isSelected();
        intervalField.setEnabled(recurring);
        unitCombo.setEnabled(recurring);
        startsField.setEnabled(recurring);
        endsField.setEnabled(recurring);
        atField.setEnabled(!recurring);
    }

    @Nullable
    @Override
    protected String validateInput() {
        if (CommonUtils.isEmptyTrimmed(nameField.getText())) {
            return SchedulerMessages.scheduler_error_no_name;
        }
        if (recurringRadio.isSelected() && CommonUtils.isEmptyTrimmed(intervalField.getText())) {
            return SchedulerMessages.mysql_error_no_interval;
        }
        if (CommonUtils.isEmptyTrimmed(bodyText.getText())) {
            return SchedulerMessages.mysql_error_no_body;
        }
        return null;
    }

    @Nullable
    @Override
    protected String getDeniedReason() {
        MySQLEventScheduler.Access current = SchedulerCommands.getMySQLAccess(catalog);
        MySQLEventScheduler.Access known = current != null ? current : access;
        return known != null && known.isDenied() ? NLS.bind(SchedulerMessages.scheduler_tip_mysql_event, catalog.getName()) : null;
    }

    @NotNull
    @Override
    protected List<String> buildStatements() {
        definition.setName(nameField.getText());
        StatusChoice status = (StatusChoice) statusCombo.getSelectedItem();
        if (status != null) {
            definition.setStatus(status.status());
        }
        definition.setPreserve(preserveCheck.isSelected());
        definition.setComment(commentField.getText());
        definition.setBody(bodyText.getText());
        definition.setRecurring(recurringRadio.isSelected());
        definition.setIntervalValue(intervalField.getText());
        Object unit = unitCombo.getSelectedItem();
        if (unit != null) {
            definition.setIntervalField(unit.toString());
        }
        definition.setStarts(startsField.getText());
        definition.setEnds(endsField.getText());
        definition.setExecuteAt(atField.getText());
        return definition.generateStatements();
    }
}
