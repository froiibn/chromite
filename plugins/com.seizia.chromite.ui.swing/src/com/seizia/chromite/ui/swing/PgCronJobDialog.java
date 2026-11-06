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
import com.seizia.chromite.ext.postgresql.model.cron.PostgreCronJob;
import com.seizia.chromite.ext.postgresql.model.cron.PostgreCronJobDefinition;
import com.seizia.chromite.ext.postgresql.model.cron.PostgreCronManager;
import com.seizia.chromite.ext.postgresql.model.cron.PostgreCronScheduler;
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
import java.util.ArrayList;
import java.util.List;

/**
 * New Job / Job Properties of pg_cron: name, schedule (cron syntax, with the schedule helper), database, active, command.
 *
 * 설계: 문은 모델({@link PostgreCronJobDefinition})이 만들고, 확인·미리 보기·실행은 {@link SchedulerScriptDialog}가 한다. 창을 열기 전에
 * 진행 대화상자에서 데이터베이스 목록과 작업 목록(이름이 겹치는지 보려고, pg_cron은 같은 이름의 작업을 덮어쓴다)을 읽는다.
 * 설치된 pg_cron이 지원하지 않는 칸(다른 데이터베이스: {@code schedule_in_database}, 사용 여부: {@code active} 열, 기존 작업 고치기:
 * {@code alter_job})은 끄고 이유를 적는다.
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-job-schedulers.md)
 */
final class PgCronJobDialog extends SchedulerScriptDialog {

    private final PostgreCronScheduler scheduler;
    private final PostgreCronJobDefinition definition;
    private final List<String> existingNames;
    private final JTextField nameField = new JTextField();
    private final JTextField scheduleField = new JTextField();
    private final JComboBox<String> databaseCombo = new JComboBox<>();
    private final JCheckBox activeCheck = new JCheckBox(SchedulerMessages.scheduler_enabled_check);
    private final SqlEditorPanel.SqlTextArea commandText = new SqlEditorPanel.SqlTextArea();

    private PgCronJobDialog(
        @NotNull Component owner,
        @NotNull PostgreCronScheduler scheduler,
        @NotNull PostgreCronJobDefinition definition,
        @NotNull List<String> databases,
        @NotNull List<String> existingNames,
        @Nullable DBNDatabaseNode refreshNode,
        @NotNull ObjectExplorerPanel.QueryWindows queryWindows
    ) {
        super(owner, getTitle(scheduler, definition), SchedulerCommands.pgCron(scheduler), refreshNode, queryWindows);
        this.scheduler = scheduler;
        this.definition = definition;
        this.existingNames = existingNames;
        boolean editable = definition.isNew() || scheduler.hasAlterJob();

        nameField.setText(definition.getName());
        nameField.setEditable(definition.isNew() && scheduler.hasJobName());
        nameField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, SchedulerMessages.cron_name_placeholder);
        scheduleField.setText(definition.getSchedule());
        scheduleField.setEditable(editable);
        scheduleField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, SchedulerMessages.cron_schedule_placeholder);
        JButton buildButton = new JButton(SchedulerMessages.scheduler_build);
        buildButton.setToolTipText(SchedulerMessages.scheduler_build_tip);
        buildButton.setEnabled(editable);
        buildButton.addActionListener(e -> {
            String built = ScheduleBuilderDialog.show(this, ScheduleBuilderDialog.Syntax.CRON);
            if (built != null) {
                scheduleField.setText(built);
            }
        });
        JPanel schedulePanel = new JPanel(new BorderLayout(6, 0));
        schedulePanel.setOpaque(false);
        schedulePanel.add(scheduleField, BorderLayout.CENTER);
        schedulePanel.add(buildButton, BorderLayout.EAST);
        for (String database : databases) {
            databaseCombo.addItem(database);
        }
        if (!databases.contains(definition.getDatabase())) {
            databaseCombo.addItem(definition.getDatabase());
        }
        databaseCombo.setSelectedItem(definition.getDatabase());
        databaseCombo.setEnabled(definition.isNew() ? scheduler.hasScheduleInDatabase() : scheduler.hasAlterJob());
        activeCheck.setSelected(definition.isActive());
        // New inactive jobs and changes both go through cron.alter_job
        activeCheck.setEnabled(scheduler.hasActive() && scheduler.hasAlterJob());
        commandText.setText(definition.getCommand());
        commandText.setCaretPosition(0);
        commandText.discardAllEdits();
        commandText.setEditable(editable);
        RTextScrollPane commandScroll = new RTextScrollPane(commandText, true);
        commandText.applyTheme();

        DatabaseTaskDialog.FormPanel general = new DatabaseTaskDialog.FormPanel();
        if (!editable) {
            general.addFull(DatabaseTaskDialog.FormPanel.note(SchedulerMessages.cron_note_no_alter));
        }
        general.addRow(SchedulerMessages.scheduler_name, nameField);
        general.addRow(SchedulerMessages.cron_schedule, schedulePanel);
        general.addFull(DatabaseTaskDialog.FormPanel.note(SchedulerMessages.cron_schedule_help));
        general.addRow(SchedulerMessages.cron_database, databaseCombo);
        general.addFull(activeCheck);
        if (scheduler.isLauncherStopped()) {
            general.addFull(new JLabel(SchedulerMessages.cron_note_launcher_stopped, UiIcons.get("warning"), SwingConstants.LEADING)); //$NON-NLS-1$
        }
        general.addFull(new JLabel(SchedulerMessages.cron_command));
        general.addFiller(commandScroll);
        initDialog(List.of(new Page(SchedulerMessages.scheduler_page_general, general)));
    }

    @NotNull
    private static String getTitle(@NotNull PostgreCronScheduler scheduler, @NotNull PostgreCronJobDefinition definition) {
        return definition.isNew() ? NLS.bind(SchedulerMessages.cron_job_new_title, scheduler.getDatabase().getName())
            : NLS.bind(SchedulerMessages.cron_job_title, CommonUtils.isEmpty(definition.getName()) ? "" : definition.getName()); //$NON-NLS-1$
    }

    /**
     * Reads the databases and the existing job names in a progress dialog, then shows the dialog.
     *
     * @param job the job to edit, or null for a new job
     */
    static void open(
        @NotNull Component owner,
        @NotNull PostgreCronScheduler scheduler,
        @Nullable PostgreCronJob job,
        @Nullable DBNDatabaseNode refreshNode,
        @NotNull ObjectExplorerPanel.QueryWindows queryWindows
    ) {
        PostgreCronJobDefinition definition = new PostgreCronJobDefinition(scheduler, job);
        String title = getTitle(scheduler, definition);
        List<String> databases;
        List<String> names = new ArrayList<>();
        try {
            databases = ProgressDialog.runTask(title, monitor -> {
                for (PostgreCronJob existing : scheduler.getJobs(monitor)) {
                    if (existing.getJobName() != null) {
                        names.add(existing.getJobName());
                    }
                }
                return PostgreCronManager.readDatabases(monitor, scheduler);
            });
        } catch (DBInterruptedException e) {
            return;
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(title, SchedulerMessages.scheduler_error_read, e);
            return;
        }
        new PgCronJobDialog(owner, scheduler, definition, databases, names, refreshNode, queryWindows).open();
    }

    @Nullable
    @Override
    protected String validateInput() {
        String name = nameField.getText().trim();
        if (definition.isNew() && !name.isEmpty() && existingNames.contains(name)) {
            return NLS.bind(SchedulerMessages.cron_error_name_exists, name);
        }
        if (definition.isNew() && name.isEmpty() && !scheduler.getDatabase().getName().equals(databaseCombo.getSelectedItem())) {
            // schedule_in_database needs a job name
            return SchedulerMessages.cron_error_name_needed;
        }
        if (CommonUtils.isEmptyTrimmed(scheduleField.getText())) {
            return SchedulerMessages.cron_error_no_schedule;
        }
        if (CommonUtils.isEmptyTrimmed(commandText.getText())) {
            return SchedulerMessages.cron_error_no_command;
        }
        return null;
    }

    @Nullable
    @Override
    protected String getDeniedReason() {
        return switch (scheduler.getState()) {
            case INSTALLED -> null;
            case NOT_INSTALLED -> SchedulerMessages.scheduler_tip_cron_not_installed;
            case NO_ACCESS -> SchedulerMessages.scheduler_tip_cron_no_access;
            case UNKNOWN -> SchedulerMessages.scheduler_tip_cron_unknown;
        };
    }

    @NotNull
    @Override
    protected List<String> buildStatements() {
        if (definition.isNew()) {
            definition.setName(nameField.getText());
        }
        definition.setSchedule(scheduleField.getText());
        definition.setCommand(commandText.getText());
        Object database = databaseCombo.getSelectedItem();
        if (database != null) {
            definition.setDatabase(database.toString());
        }
        definition.setActive(activeCheck.isSelected());
        return definition.generateStatements();
    }
}
