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

import com.seizia.chromite.ext.mssql.model.SQLServerDatabase;
import com.seizia.chromite.ext.mssql.model.SQLServerDatabaseFile;
import com.seizia.chromite.ext.mssql.model.SQLServerFilegroup;
import com.seizia.chromite.ext.mssql.model.SQLServerStorageDDL;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.ui.swing.internal.CreateObjectMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.math.BigDecimal;

/**
 * Settings dialogs of the SQL Server file commands: New File (a data file of a filegroup), New Log File, File Settings.
 *
 * 설계: 다른 "New …" 대화상자({@link NewObjectDialogs}, {@link AltibaseTablespaceDialogs})와 같은 틀이다({@link DesignerDialogs#showDialog}의
 * OK/취소, 칸 이름 + 값, 오류는 단추 줄의 한 줄). 대화상자는 값만 받고 문장은 모델({@code SQLServerStorageDDL})이 만든다. 크기 칸의 묶음은
 * 이 클래스의 것이다(다른 브랜치와 나누지 않기로 한 공통 지시, Altibase의 칸 묶음은 단위·의미가 다름).
 * <ul>
 *     <li>크기·증가·최대 크기는 정수 MB다(서버가 소수 MB를 거절함). 증가는 한 칸과 단위(MB, %)이다. 자동 증가 확인란을 끄면
 *     {@code FILEGROWTH = 0}이다.</li>
 *     <li>새 파일: 빈 크기·증가·최대 크기는 문장에서 빠져 서버 기본값을 따른다(model 데이터베이스의 크기, 64 MB 증가, 제한 없음).
 *     파일 칸은 서버의 기본 폴더(InstanceDefaultDataPath·LogPath) + 논리 이름 + .ndf/.ldf로 채워지고, 사용자가 파일 칸을 고치기 전까지
 *     이름을 따라간다(SSMS 새 파일과 같은 이름). 서버는 상대 경로를 거절한다(5105).</li>
 *     <li>파일 설정: 지금 값으로 채우고, 글이 바뀐 칸만 문장이 된다(지금 크기가 정수 MB가 아니면 소수로 보이고, 그대로 두면 바뀌지 않음).
 *     최대 크기를 비우면 제한 없음이다. 읽기 전용 값(실제 파일 경로)은 초점을 받지 않는다(#291 데이터 파일 설정과 같음). 바뀐 것이 없으면
 *     {@link AltibaseTablespaceDialogs#UNCHANGED}를 돌려준다: {@link ObjectMaintenanceCommands}가 이미 "실행할 문장 없음"으로 알리는 표지라
 *     공유 클래스를 고치지 않는다.</li>
 *     <li>서버가 거절하는 값(지금보다 작거나 같은 크기 5039, 크기보다 작은 최대 크기 5040, 최대 크기보다 큰 증가 5169)은 여기서 막지 않는다.
 *     서버의 오류가 실패한 변경으로 보인다.</li>
 * </ul>
 * EDT에서 부른다. 새 파일 대화상자는 기본 폴더를 진행 대화상자에서 읽는다.
 *
 * 변경 이력:
 * - 2026-11-15 최초 작성(앱 변경 #128, docs/chromite/mssql-storage.md)
 * - 2026-11-16 크기·증가·최대 크기 칸이 숫자만 받음({@link NumberInputFilter}, 앱 변경 #133). 파일 설정의 지금 크기·증가는 소수가 있어도
 *   그대로 보임({@link NumberInputFilter#setText})
 */
final class SQLServerStorageDialogs {

    private static final String UNIT_MB = "MB"; //$NON-NLS-1$
    private static final String UNIT_PERCENT = "%"; //$NON-NLS-1$
    private static final String DATA_FILE_EXTENSION = ".ndf"; //$NON-NLS-1$
    private static final String LOG_FILE_EXTENSION = ".ldf"; //$NON-NLS-1$

    /**
     * The size, autogrowth and maximum size fields.
     */
    private static final class SpaceFields {
        final JTextField sizeField = new JTextField(12);
        final JCheckBox autogrowthBox = new JCheckBox(CreateObjectMessages.create_label_autogrowth);
        final JTextField growthField = new JTextField(8);
        final JComboBox<String> unitCombo = new JComboBox<>(new String[]{UNIT_MB, UNIT_PERCENT});
        final JTextField maxField = new JTextField(12);

        SpaceFields() {
            NumberInputFilter.install(sizeField, false);
            NumberInputFilter.install(growthField, false);
            NumberInputFilter.install(maxField, false);
            autogrowthBox.setSelected(true);
            autogrowthBox.addItemListener(e -> syncEnabled());
            syncEnabled();
        }

        void syncEnabled() {
            growthField.setEnabled(autogrowthBox.isSelected());
            unitCombo.setEnabled(autogrowthBox.isSelected());
            maxField.setEnabled(autogrowthBox.isSelected());
        }

        void addTo(@NotNull JPanel form, int row) {
            DesignerDialogs.addRow(form, row++, CreateObjectMessages.create_label_size, sizeField);
            DesignerDialogs.addRow(form, row++, "", autogrowthBox); //$NON-NLS-1$
            JPanel growth = new JPanel(new BorderLayout(6, 0));
            growth.add(growthField, BorderLayout.CENTER);
            growth.add(unitCombo, BorderLayout.EAST);
            DesignerDialogs.addRow(form, row++, CreateObjectMessages.create_label_growth, growth);
            DesignerDialogs.addRow(form, row, CreateObjectMessages.create_label_max_size, maxField);
        }

        boolean isPercent() {
            return UNIT_PERCENT.equals(unitCombo.getSelectedItem());
        }

        /**
         * The growth as entered: off, or a value (null when the field is empty).
         */
        @Nullable
        SQLServerStorageDDL.Growth growth() throws InvalidValue {
            if (!autogrowthBox.isSelected()) {
                return SQLServerStorageDDL.GROWTH_OFF;
            }
            Long value = parse(growthField, CreateObjectMessages.create_error_growth);
            return value == null ? null : new SQLServerStorageDDL.Growth(value, isPercent());
        }
    }

    /**
     * A field that is not a positive whole number.
     */
    private static final class InvalidValue extends Exception {
        final String message;

        InvalidValue(@NotNull String message) {
            this.message = message;
        }
    }

    private SQLServerStorageDialogs() {
    }

    //////////////////////////////////////////////////////////
    // New File, New Log File

    /**
     * New File of a filegroup or New Log File of a database: logical name, file, size, autogrowth and maximum size.
     */
    @Nullable
    static NewObjectDialogs.Configurer askFile(@NotNull Component owner, @NotNull String title, @NotNull DBSObject container) {
        boolean log = container instanceof SQLServerDatabase;
        SQLServerDatabase database = container instanceof SQLServerFilegroup filegroup ? filegroup.getDatabase() : (SQLServerDatabase) container;
        String folder = NewObjectDialogs.load(title, monitor -> database.readDefaultFileFolder(monitor, log));
        if (folder == null) {
            return null;
        }
        String extension = log ? LOG_FILE_EXTENSION : DATA_FILE_EXTENSION;
        JTextField nameField = new JTextField(28);
        JTextField fileField = new JTextField(folder, 36);
        followName(nameField, fileField, folder, extension);
        SpaceFields space = new SpaceFields();

        JPanel form = new JPanel(new GridBagLayout());
        DesignerDialogs.addRow(form, 0, CreateObjectMessages.create_label_name, nameField);
        DesignerDialogs.addRow(form, 1, CreateObjectMessages.create_label_file, fileField);
        space.addTo(form, 2);

        record Values(String name, SQLServerStorageDDL.FileSpec spec) {
        }
        Values values = DesignerDialogs.showDialog(owner, title, form, () -> {
            String name = nameField.getText().trim();
            if (name.isEmpty()) {
                return DesignerDialogs.error(CreateObjectMessages.create_error_name);
            }
            String file = fileField.getText().trim();
            if (file.isEmpty()) {
                return DesignerDialogs.error(CreateObjectMessages.create_error_file);
            }
            try {
                Long sizeMb = parse(space.sizeField, CreateObjectMessages.create_error_size);
                SQLServerStorageDDL.Growth growth = space.growth();
                Long maxSizeMb = space.autogrowthBox.isSelected() ? parse(space.maxField, CreateObjectMessages.create_error_size) : null;
                return new Values(name, new SQLServerStorageDDL.FileSpec(file, sizeMb, growth, maxSizeMb));
            } catch (InvalidValue e) {
                return DesignerDialogs.error(e.message);
            }
        });
        if (values == null) {
            return null;
        }
        return (monitor, object, context) -> {
            SQLServerDatabaseFile newFile = (SQLServerDatabaseFile) object;
            // A logical name is a string in the statement (NAME = N'…'): kept as typed
            newFile.setName(values.name());
            newFile.setInitialSpec(values.spec());
        };
    }

    /**
     * Fills the file field with the folder, the name and the extension while the user types the name, until the user
     * edits the file field.
     */
    private static void followName(@NotNull JTextField nameField, @NotNull JTextField fileField, @NotNull String folder, @NotNull String extension) {
        boolean[] following = {true};
        boolean[] updating = {false};
        fileField.getDocument().addDocumentListener(new DocumentAdapter(() -> {
            if (!updating[0]) {
                following[0] = false;
            }
        }));
        nameField.getDocument().addDocumentListener(new DocumentAdapter(() -> {
            if (!following[0]) {
                return;
            }
            String name = nameField.getText().trim();
            updating[0] = true;
            try {
                fileField.setText(name.isEmpty() ? folder : folder + name + extension);
            } finally {
                updating[0] = false;
            }
        }));
    }

    //////////////////////////////////////////////////////////
    // File Settings (Object Explorer maintenance command)

    /**
     * File Settings: size, autogrowth and maximum size of a data or log file, filled with its current values.
     *
     * @return the change, {@link AltibaseTablespaceDialogs#UNCHANGED} if the values are the current ones, or null if
     * canceled
     */
    @Nullable
    static Object askFileSettings(@NotNull Component owner, @NotNull String title, @NotNull SQLServerDatabaseFile file) {
        JTextField fileField = new JTextField(file.getFileName(), 36);
        fileField.setEditable(false);
        // Shown as the other dialogs show a read-only value, but never focused: the first focus goes to the size field
        fileField.setFocusable(false);
        SpaceFields space = new SpaceFields();
        String size = format(file.getSize());
        // The current size and growth can have a fraction of a MB: shown as they are (unchanged, they are not written)
        NumberInputFilter.setText(space.sizeField, size);
        boolean autogrowth = file.isAutogrowth();
        space.autogrowthBox.setSelected(autogrowth);
        String growth = autogrowth ? format(file.getGrowthValue()) : ""; //$NON-NLS-1$
        NumberInputFilter.setText(space.growthField, growth);
        boolean percent = autogrowth && file.isPercentGrowth();
        space.unitCombo.setSelectedItem(percent ? UNIT_PERCENT : UNIT_MB);
        Long currentMax = file.getMaxSizeMb();
        String max = autogrowth && currentMax != null ? String.valueOf(currentMax) : ""; //$NON-NLS-1$
        space.maxField.setText(max);
        space.syncEnabled();

        JPanel form = new JPanel(new GridBagLayout());
        DesignerDialogs.addRow(form, 0, CreateObjectMessages.create_label_file, fileField);
        space.addTo(form, 1);
        return DesignerDialogs.showDialog(owner, title, form, () -> {
            try {
                Long sizeMb = null;
                if (!space.sizeField.getText().trim().equals(size)) {
                    sizeMb = parse(space.sizeField, CreateObjectMessages.create_error_size);
                    if (sizeMb == null) {
                        return DesignerDialogs.error(CreateObjectMessages.create_error_size);
                    }
                }
                SQLServerStorageDDL.Growth newGrowth = null;
                Long maxSizeMb = null;
                boolean on = space.autogrowthBox.isSelected();
                if (on != autogrowth) {
                    newGrowth = growthRequired(space);
                    if (on) {
                        // Turned on: the maximum is written too (empty: no limit)
                        maxSizeMb = maxOrUnlimited(space);
                    }
                } else if (on) {
                    if (!space.growthField.getText().trim().equals(growth) || space.isPercent() != percent) {
                        newGrowth = growthRequired(space);
                    }
                    if (!space.maxField.getText().trim().equals(max)) {
                        maxSizeMb = maxOrUnlimited(space);
                    }
                }
                SQLServerStorageDDL.FileSpec change = new SQLServerStorageDDL.FileSpec(null, sizeMb, newGrowth, maxSizeMb);
                return change.isEmpty() ? AltibaseTablespaceDialogs.UNCHANGED : change;
            } catch (InvalidValue e) {
                return DesignerDialogs.error(e.message);
            }
        });
    }

    //////////////////////////////////////////////////////////
    // Helpers

    /**
     * The growth of a file whose autogrowth is set: off, or a value that must be entered.
     */
    @NotNull
    private static SQLServerStorageDDL.Growth growthRequired(@NotNull SpaceFields space) throws InvalidValue {
        SQLServerStorageDDL.Growth growth = space.growth();
        if (growth == null) {
            throw new InvalidValue(CreateObjectMessages.create_error_growth);
        }
        return growth;
    }

    private static long maxOrUnlimited(@NotNull SpaceFields space) throws InvalidValue {
        Long max = parse(space.maxField, CreateObjectMessages.create_error_size);
        return max == null ? SQLServerStorageDDL.UNLIMITED : max;
    }

    /**
     * The field's positive whole number; null if empty.
     *
     * @throws InvalidValue with the message if it is not a positive whole number
     */
    @Nullable
    private static Long parse(@NotNull JTextField field, @NotNull String message) throws InvalidValue {
        String text = field.getText().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            long value = Long.parseLong(text);
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw new InvalidValue(message);
    }

    @NotNull
    private static String format(@NotNull BigDecimal value) {
        return value.toPlainString();
    }

    /**
     * A document listener that runs one action on every change.
     */
    private record DocumentAdapter(@NotNull Runnable action) implements DocumentListener {
        @Override
        public void insertUpdate(DocumentEvent e) {
            action.run();
        }

        @Override
        public void removeUpdate(DocumentEvent e) {
            action.run();
        }

        @Override
        public void changedUpdate(DocumentEvent e) {
            action.run();
        }
    }
}
