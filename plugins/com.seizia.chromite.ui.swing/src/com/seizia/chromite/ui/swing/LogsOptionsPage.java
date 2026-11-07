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

import com.seizia.chromite.LogOutputStream;
import com.seizia.chromite.ModelPreferences;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.qm.QMConstants;
import com.seizia.chromite.model.qm.QMObjectType;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.internal.SettingsMessages;
import com.seizia.chromite.utils.PrefUtils;
import org.jkiss.code.NotNull;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Tools &gt; Options &gt; Logs: the SQL log file of the query manager and the size of the debug log (SWT {@code PrefPageQueryManager},
 * {@code PrefPageErrorLogs}).
 *
 * 설계:
 * <ul>
 *     <li>SQL 로그 파일: {@code QMLogFileWriter}(Swing 플랫폼이 {@code QMUtils.initPlatform(true)}로 켬)가 플랫폼 저장소의 {@code qm.*} 키를 읽는다.
 *     {@code qm.}으로 시작하는 키가 바뀌면 그 저장소의 변경 알림으로 파일과 거르기를 다시 만든다. 그래서 OK 뒤 바로 적용된다. 보관 일수가 지난 파일은
 *     다시 만들 때 지운다. 기록할 것(연결·트랜잭션·쿼리)과 쿼리 종류는 {@code DefaultEventFilter}가 거른다.</li>
 *     <li>쿼리 창의 쿼리 기록(General 탭 Query History, {@link QueryHistory})과는 다르다. 이 파일은 메타데이터 읽기 같은 내부 쿼리까지 담는 글자 로그다.</li>
 *     <li>디버그 로그: {@code LogOutputStream}이 모델 번들 저장소({@link ModelPreferences#getPreferences})에 변경 알림을 걸어 크기와 파일 수를 바로 바꾼다.
 *     플랫폼 저장소와 같은 설정 노드지만 알림은 저장소 객체마다 따로라서 이 두 키는 그 저장소 객체로 쓴다.</li>
 *     <li>모두 전역 설정이라 연결 대화상자에는 넣지 않는다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-options.md "남은 키 (2026-11-08 오후)")
 */
final class LogsOptionsPage extends SettingsPage {

    private static final long MEGABYTE = 1024L * 1024L;

    private final JCheckBox storeLogsBox = new JCheckBox(SettingsMessages.logs_store);
    private final JSpinner historyDaysSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 3650, 1));
    private final JTextField logFolderField = new JTextField(30);
    private final JButton browseButton = new JButton(SettingsMessages.drivers_browse);
    private final Map<QMObjectType, JCheckBox> objectTypeBoxes = new EnumMap<>(QMObjectType.class);
    private final Map<DBCExecutionPurpose, JCheckBox> queryTypeBoxes = new EnumMap<>(DBCExecutionPurpose.class);
    private final JSpinner debugSizeSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 1024, 1));
    private final JSpinner debugCountSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 100, 1));
    private long loadedDebugSizeMb;

    LogsOptionsPage() {
        storeLogsBox.setToolTipText(SettingsMessages.logs_store_tip);
        storeLogsBox.addActionListener(e -> updateEnablement());
        browseButton.addActionListener(e -> chooseFolder());
        objectTypeBoxes.put(QMObjectType.session, new JCheckBox(SettingsMessages.logs_object_session));
        objectTypeBoxes.put(QMObjectType.txn, new JCheckBox(SettingsMessages.logs_object_txn));
        objectTypeBoxes.put(QMObjectType.query, new JCheckBox(SettingsMessages.logs_object_query));
        queryTypeBoxes.put(DBCExecutionPurpose.USER, new JCheckBox(SettingsMessages.logs_purpose_user));
        queryTypeBoxes.put(DBCExecutionPurpose.USER_FILTERED, new JCheckBox(SettingsMessages.logs_purpose_user_filtered));
        queryTypeBoxes.put(DBCExecutionPurpose.USER_SCRIPT, new JCheckBox(SettingsMessages.logs_purpose_user_script));
        queryTypeBoxes.put(DBCExecutionPurpose.UTIL, new JCheckBox(SettingsMessages.logs_purpose_util));
        queryTypeBoxes.put(DBCExecutionPurpose.META, new JCheckBox(SettingsMessages.logs_purpose_meta));
        queryTypeBoxes.put(DBCExecutionPurpose.META_DDL, new JCheckBox(SettingsMessages.logs_purpose_meta_ddl));
        // Query kinds only filter query entries
        objectTypeBoxes.get(QMObjectType.query).addActionListener(e -> updateEnablement());

        JPanel folderRow = new JPanel(new BorderLayout(6, 0));
        folderRow.add(logFolderField, BorderLayout.CENTER);
        folderRow.add(browseButton, BorderLayout.EAST);
        JPanel objectsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        objectTypeBoxes.values().forEach(objectsRow::add);
        JPanel queryTypesRow = new JPanel(new GridLayout(0, 3, 12, 2));
        queryTypeBoxes.values().forEach(queryTypesRow::add);

        JPanel form = new JPanel(new GridBagLayout());
        int row = OptionsDialog.addSection(form, 0, SettingsMessages.logs_section_sql);
        row = OptionsDialog.addRow(form, row, null, storeLogsBox, null);
        row = OptionsDialog.addRow(form, row, SettingsMessages.logs_days, historyDaysSpinner, null);
        row = OptionsDialog.addRow(form, row, SettingsMessages.logs_folder, folderRow, SettingsMessages.logs_folder_hint);
        row = OptionsDialog.addRow(form, row, SettingsMessages.logs_objects, objectsRow, null);
        row = OptionsDialog.addRow(form, row, SettingsMessages.logs_query_types, queryTypesRow, null);
        row = OptionsDialog.addSection(form, row, SettingsMessages.logs_section_debug);
        row = OptionsDialog.addRow(form, row, SettingsMessages.logs_debug_size, debugSizeSpinner, SettingsMessages.logs_debug_hint);
        OptionsDialog.addRow(form, row, SettingsMessages.logs_debug_count, debugCountSpinner, null);
        add(OptionsDialog.wrapPage(form), BorderLayout.CENTER);
        load(DBWorkbench.getPlatform().getPreferenceStore());
    }

    @NotNull
    @Override
    String getTitle() {
        return SettingsMessages.logs_tab;
    }

    @NotNull
    @Override
    Collection<String> getKeys() {
        return List.of(QMConstants.PROP_STORE_LOG_FILE, QMConstants.PROP_HISTORY_DAYS, QMConstants.PROP_LOG_DIRECTORY,
            QMConstants.PROP_OBJECT_TYPES, QMConstants.PROP_QUERY_TYPES, LogOutputStream.LOGS_MAX_FILE_SIZE,
            LogOutputStream.LOGS_MAX_FILES_COUNT);
    }

    @Override
    void load(@NotNull DBPPreferenceStore store) {
        storeLogsBox.setSelected(store.getBoolean(QMConstants.PROP_STORE_LOG_FILE));
        historyDaysSpinner.setValue(Math.clamp(store.getInt(QMConstants.PROP_HISTORY_DAYS), 1, 3650));
        logFolderField.setText(store.getString(QMConstants.PROP_LOG_DIRECTORY));
        Collection<QMObjectType> objectTypes = QMObjectType.fromString(store.getString(QMConstants.PROP_OBJECT_TYPES));
        objectTypeBoxes.forEach((type, box) -> box.setSelected(objectTypes.contains(type)));
        List<String> queryTypes = CommonUtils.splitString(store.getString(QMConstants.PROP_QUERY_TYPES), ',');
        queryTypeBoxes.forEach((purpose, box) -> box.setSelected(queryTypes.contains(purpose.name())));
        // The debug log limits are in the model bundle's store (the same settings node)
        DBPPreferenceStore modelStore = ModelPreferences.getPreferences();
        loadedDebugSizeMb = Math.clamp(modelStore.getLong(LogOutputStream.LOGS_MAX_FILE_SIZE) / MEGABYTE, 1, 1024);
        debugSizeSpinner.setValue((int) loadedDebugSizeMb);
        debugCountSpinner.setValue(Math.clamp(modelStore.getInt(LogOutputStream.LOGS_MAX_FILES_COUNT), 1, 100));
        updateEnablement();
    }

    @Override
    void save(@NotNull Writer writer) {
        writer.setBoolean(QMConstants.PROP_STORE_LOG_FILE, storeLogsBox.isSelected());
        writer.setInt(QMConstants.PROP_HISTORY_DAYS, ((Number) historyDaysSpinner.getValue()).intValue());
        writer.setString(QMConstants.PROP_LOG_DIRECTORY, logFolderField.getText().trim());
        List<QMObjectType> objectTypes = new ArrayList<>();
        objectTypeBoxes.forEach((type, box) -> {
            if (box.isSelected()) {
                objectTypes.add(type);
            }
        });
        writer.setString(QMConstants.PROP_OBJECT_TYPES, QMObjectType.toString(objectTypes));
        List<String> queryTypes = new ArrayList<>();
        queryTypeBoxes.forEach((purpose, box) -> {
            if (box.isSelected()) {
                queryTypes.add(purpose.name());
            }
        });
        // Same format as the default (ModelPreferences): names separated by ','
        writer.setString(QMConstants.PROP_QUERY_TYPES, String.join(",", queryTypes)); //$NON-NLS-1$
    }

    @Override
    void apply() {
        super.apply();
        // Written to the store LogOutputStream listens to, so the running log takes the new limits at once
        DBPPreferenceStore modelStore = ModelPreferences.getPreferences();
        Writer writer = new Writer(modelStore, false);
        long sizeMb = ((Number) debugSizeSpinner.getValue()).longValue();
        if (sizeMb != loadedDebugSizeMb) {
            // A size saved in bytes that is not a whole number of megabytes stays unless the field is changed
            writer.setLong(LogOutputStream.LOGS_MAX_FILE_SIZE, sizeMb * MEGABYTE);
        }
        writer.setInt(LogOutputStream.LOGS_MAX_FILES_COUNT, ((Number) debugCountSpinner.getValue()).intValue());
        if (writer.isChanged()) {
            PrefUtils.savePreferenceStore(modelStore);
        }
    }

    @Override
    void updateEnablement() {
        boolean enabled = storeLogsBox.isEnabled() && storeLogsBox.isSelected();
        historyDaysSpinner.setEnabled(enabled);
        logFolderField.setEnabled(enabled);
        browseButton.setEnabled(enabled);
        objectTypeBoxes.values().forEach(box -> box.setEnabled(enabled));
        boolean queries = enabled && objectTypeBoxes.get(QMObjectType.query).isSelected();
        queryTypeBoxes.values().forEach(box -> box.setEnabled(queries));
    }

    private void chooseFolder() {
        JFileChooser chooser = new JFileChooser(logFolderField.getText().trim());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File folder = chooser.getSelectedFile();
            logFolderField.setText(folder.getAbsolutePath());
        }
    }
}
