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

import com.seizia.chromite.ext.oracle.model.OracleDataFile;
import com.seizia.chromite.ext.oracle.model.OracleDataSource;
import com.seizia.chromite.ext.oracle.model.OracleStorageRoles;
import com.seizia.chromite.ext.oracle.model.OracleTablespace;
import com.seizia.chromite.ext.oracle.model.OracleTablespaceDDL;
import com.seizia.chromite.model.impl.DBObjectNameCaseTransformer;
import com.seizia.chromite.ui.swing.internal.CreateObjectMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import javax.swing.*;
import java.awt.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

/**
 * Settings dialogs of the Oracle tablespace commands: New Tablespace, New Data File, Data File Settings.
 *
 * 설계: Altibase 계열의 같은 명령({@link AltibaseTablespaceDialogs})과 같은 틀·칸·문구다({@link DesignerDialogs#showDialog}의 OK/취소,
 * 칸 이름 + 값, 오류는 단추 줄의 한 줄, 안내 문구 없음). 대화상자는 값만 받고 문장은 모델({@code OracleTablespaceDDL})이 만든다.
 * 칸 묶음({@link SpaceFields})은 Altibase 것을 옮겨 함께 쓰지 않고 따로 둔다: 그쪽은 Altibase 모델의 값 형식을 돌려주기 때문이다.
 * <ul>
 *     <li>크기 칸은 MB이고 소수도 받는다(KB로 나누어떨어져야 함). 데이터 파일과 크기는 꼭 입력한다: 시험 서버는 {@code DB_CREATE_FILE_DEST}가
 *     없어 파일 이름 없는 문장(ORA-02236)과 크기 없는 새 파일(ORA-17610)을 거절했다. 증가 크기·최대 크기는 비우면 문장에서 빠진다.</li>
 *     <li>빅파일 확인란은 서버가 빅파일 테이블스페이스를 아는 10g 이상에서만 보이고, 처음 값은 데이터베이스 기본값
 *     ({@code DATABASE_PROPERTIES.DEFAULT_TBS_TYPE}, 진행 대화상자에서 읽음)이다. 문장에는 고른 쪽이 늘 적힌다.</li>
 *     <li>데이터 파일 설정은 지금 값으로 채워지고, 바뀐 것만 문장이 된다. 아무것도 바뀌지 않으면 {@link AltibaseTablespaceDialogs#UNCHANGED}를
 *     돌려준다(유지 관리 명령이 이미 "실행할 문장 없음"으로 처리하는 값).</li>
 *     <li>서버가 거절하는 값(최소 크기보다 작음, MAXSIZE가 크기보다 작음 등)은 여기서 막지 않는다. 서버의 오류가 실패한 변경으로 보인다.</li>
 * </ul>
 * EDT에서 부른다.
 *
 * 변경 이력:
 * - 2026-11-15 최초 작성(앱 변경 #127, docs/chromite/oracle-storage.md)
 * - 2026-11-16 크기·증가 크기·최대 크기 칸이 숫자와 소수점 하나만 받음({@link NumberInputFilter}, 앱 변경 #133)
 */
final class OracleStorageDialogs {

    private static final BigDecimal KB_PER_MB = BigDecimal.valueOf(1024);
    private static final BigInteger BYTES_PER_KB = BigInteger.valueOf(1024);

    /**
     * A tablespace kind in the type combo.
     */
    private record TypeChoice(@NotNull OracleTablespace.Contents contents, @NotNull String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * The size, next and maximum fields with the autoextend check box.
     */
    private static final class SpaceFields {
        final JTextField sizeField = new JTextField(12);
        final JCheckBox autoextendBox = new JCheckBox(CreateObjectMessages.create_label_autoextend);
        final JTextField nextField = new JTextField(12);
        final JTextField maxField = new JTextField(12);

        SpaceFields() {
            NumberInputFilter.install(sizeField, true);
            NumberInputFilter.install(nextField, true);
            NumberInputFilter.install(maxField, true);
            autoextendBox.addItemListener(e -> syncEnabled());
            syncEnabled();
        }

        void syncEnabled() {
            nextField.setEnabled(autoextendBox.isSelected());
            maxField.setEnabled(autoextendBox.isSelected());
        }

        int addTo(@NotNull JPanel form, int row) {
            DesignerDialogs.addRow(form, row++, CreateObjectMessages.create_label_size, sizeField);
            DesignerDialogs.addRow(form, row++, "", autoextendBox); //$NON-NLS-1$
            DesignerDialogs.addRow(form, row++, CreateObjectMessages.create_label_next, nextField);
            DesignerDialogs.addRow(form, row++, CreateObjectMessages.create_label_max_size, maxField);
            return row;
        }

        /**
         * The autoextend clause of a new file: null when the box is off (the server's default, off).
         */
        @Nullable
        OracleTablespaceDDL.Autoextend newAutoextend() throws InvalidSize {
            return autoextendBox.isSelected() ? new OracleTablespaceDDL.Autoextend(true, parseKb(nextField), parseKb(maxField)) : null;
        }

        /**
         * The autoextend settings as entered (OFF included).
         */
        @NotNull
        OracleTablespaceDDL.Autoextend autoextend() throws InvalidSize {
            return autoextendBox.isSelected()
                ? new OracleTablespaceDDL.Autoextend(true, parseKb(nextField), parseKb(maxField))
                : new OracleTablespaceDDL.Autoextend(false, null, null);
        }

        /**
         * The size field; it must be filled.
         */
        long sizeKb() throws InvalidSize {
            Long sizeKb = parseKb(sizeField);
            if (sizeKb == null) {
                throw new InvalidSize();
            }
            return sizeKb;
        }

        void fill(long sizeKb, @NotNull OracleTablespaceDDL.Autoextend autoextend) {
            sizeField.setText(formatMb(sizeKb));
            autoextendBox.setSelected(autoextend.enabled());
            nextField.setText(autoextend.enabled() ? formatMb(autoextend.nextKb()) : ""); //$NON-NLS-1$
            maxField.setText(autoextend.enabled() ? formatMb(autoextend.maxKb()) : ""); //$NON-NLS-1$
            syncEnabled();
        }
    }

    /**
     * A size field that is empty where a size is needed, or not a positive number of MB (whole KB).
     */
    private static final class InvalidSize extends Exception {
    }

    private OracleStorageDialogs() {
    }

    //////////////////////////////////////////////////////////
    // New Tablespace

    /**
     * New Tablespace: name, kind, bigfile, data file, size and autoextend.
     */
    @Nullable
    static NewObjectDialogs.Configurer askTablespace(@NotNull Component owner, @NotNull String title, @NotNull OracleDataSource dataSource) {
        // Servers before 10g have no bigfile tablespaces: no check box and no keyword
        JCheckBox bigFileBox = null;
        if (dataSource.isAtLeastV10()) {
            OracleStorageRoles roles = NewObjectDialogs.load(title, monitor -> OracleStorageRoles.read(monitor, dataSource));
            if (roles == null) {
                return null;
            }
            bigFileBox = new JCheckBox(CreateObjectMessages.create_label_bigfile, Boolean.TRUE.equals(roles.defaultBigFile()));
        }
        JTextField nameField = new JTextField(28);
        JComboBox<TypeChoice> typeCombo = new JComboBox<>(new TypeChoice[]{
            new TypeChoice(OracleTablespace.Contents.PERMANENT, CreateObjectMessages.create_type_permanent),
            new TypeChoice(OracleTablespace.Contents.TEMPORARY, CreateObjectMessages.create_type_temporary),
            new TypeChoice(OracleTablespace.Contents.UNDO, CreateObjectMessages.create_type_undo)
        });
        JTextField fileField = new JTextField(28);
        SpaceFields space = new SpaceFields();

        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;
        DesignerDialogs.addRow(form, row++, CreateObjectMessages.create_label_name, nameField);
        DesignerDialogs.addRow(form, row++, CreateObjectMessages.create_label_type, typeCombo);
        if (bigFileBox != null) {
            DesignerDialogs.addRow(form, row++, "", bigFileBox); //$NON-NLS-1$
        }
        DesignerDialogs.addRow(form, row++, CreateObjectMessages.create_label_data_file, fileField);
        space.addTo(form, row);

        JCheckBox chosenBigFileBox = bigFileBox;
        OracleTablespaceDDL.NewTablespace settings = DesignerDialogs.showDialog(owner, title, form, () -> {
            String name = nameField.getText().trim();
            if (name.isEmpty()) {
                return DesignerDialogs.error(CreateObjectMessages.create_error_name);
            }
            String file = fileField.getText().trim();
            if (!isValidFile(file)) {
                return DesignerDialogs.error(CreateObjectMessages.create_error_data_file);
            }
            try {
                OracleTablespaceDDL.Space fileSpace = new OracleTablespaceDDL.Space(file, space.sizeKb(), space.newAutoextend());
                return new OracleTablespaceDDL.NewTablespace(
                    ((TypeChoice) Objects.requireNonNull(typeCombo.getSelectedItem())).contents(),
                    chosenBigFileBox == null ? null : chosenBigFileBox.isSelected(),
                    fileSpace);
            } catch (InvalidSize e) {
                return DesignerDialogs.error(CreateObjectMessages.create_error_size);
            }
        });
        if (settings == null) {
            return null;
        }
        String name = nameField.getText().trim();
        return (monitor, object, context) -> {
            OracleTablespace tablespace = (OracleTablespace) object;
            tablespace.setName(DBObjectNameCaseTransformer.transformName(tablespace.getDataSource(), name));
            tablespace.setNewSettings(settings);
        };
    }

    //////////////////////////////////////////////////////////
    // New Data File

    /**
     * New Data File of a tablespace (a temp file of a temporary one): file name, size and autoextend.
     */
    @Nullable
    static NewObjectDialogs.Configurer askDataFile(@NotNull Component owner, @NotNull String title) {
        JTextField fileField = new JTextField(28);
        SpaceFields space = new SpaceFields();
        JPanel form = new JPanel(new GridBagLayout());
        DesignerDialogs.addRow(form, 0, CreateObjectMessages.create_label_data_file, fileField);
        space.addTo(form, 1);

        OracleTablespaceDDL.Space values = DesignerDialogs.showDialog(owner, title, form, () -> {
            String file = fileField.getText().trim();
            if (!isValidFile(file)) {
                return DesignerDialogs.error(CreateObjectMessages.create_error_data_file);
            }
            try {
                return new OracleTablespaceDDL.Space(file, space.sizeKb(), space.newAutoextend());
            } catch (InvalidSize e) {
                return DesignerDialogs.error(CreateObjectMessages.create_error_size);
            }
        });
        if (values == null) {
            return null;
        }
        return (monitor, object, context) -> ((OracleDataFile) object).setNewSpace(values);
    }

    //////////////////////////////////////////////////////////
    // Data File Settings (Object Explorer maintenance command)

    /**
     * Data File Settings: size and autoextend of a data or temp file, filled with its current values (as the tree read
     * them: DBA_DATA_FILES or DBA_TEMP_FILES).
     *
     * @return the change, {@link AltibaseTablespaceDialogs#UNCHANGED} if the values are the current ones, or null if canceled
     */
    @Nullable
    static Object askDataFileSettings(@NotNull Component owner, @NotNull String title, @NotNull OracleDataFile file) {
        long currentSizeKb = toKb(file.getBytes());
        // INCREMENT_BY is in blocks of the tablespace
        long blockSize = file.getTablespace().getBlockSize();
        OracleTablespaceDDL.Autoextend current = file.isAutoExtensible()
            ? new OracleTablespaceDDL.Autoextend(true, nullIfZero(file.getIncrementBy() * blockSize / 1024), nullIfZero(toKb(file.getMaxBytes())))
            : new OracleTablespaceDDL.Autoextend(false, null, null);
        JTextField fileField = new JTextField(file.getName(), 28);
        fileField.setEditable(false);
        // Shown as the other dialogs show a read-only value, but never focused: the first focus goes to the size field
        fileField.setFocusable(false);
        SpaceFields space = new SpaceFields();
        space.fill(currentSizeKb, current);
        JPanel form = new JPanel(new GridBagLayout());
        DesignerDialogs.addRow(form, 0, CreateObjectMessages.create_label_data_file, fileField);
        space.addTo(form, 1);
        return DesignerDialogs.showDialog(owner, title, form, () -> {
            try {
                long sizeKb = space.sizeKb();
                OracleTablespaceDDL.Autoextend entered = space.autoextend();
                boolean sizeChanged = sizeKb != currentSizeKb;
                boolean autoextendChanged = !sameAutoextend(entered, current);
                if (!sizeChanged && !autoextendChanged) {
                    return AltibaseTablespaceDialogs.UNCHANGED;
                }
                return new OracleTablespaceDDL.Space(null, sizeChanged ? sizeKb : null, autoextendChanged ? entered : null);
            } catch (InvalidSize e) {
                return DesignerDialogs.error(CreateObjectMessages.create_error_size);
            }
        });
    }

    //////////////////////////////////////////////////////////
    // Helpers

    /**
     * A file name for a quoted SQL string: not empty, without quotes.
     */
    private static boolean isValidFile(@NotNull String file) {
        return !file.isEmpty() && file.indexOf('\'') < 0;
    }

    private static boolean sameAutoextend(@NotNull OracleTablespaceDDL.Autoextend a, @NotNull OracleTablespaceDDL.Autoextend b) {
        return a.enabled() == b.enabled() && (!a.enabled() || Objects.equals(a.nextKb(), b.nextKb()) && Objects.equals(a.maxKb(), b.maxKb()));
    }

    /**
     * The field's MB as KB; null if empty.
     *
     * @throws InvalidSize if it is not a positive number of whole KB
     */
    @Nullable
    private static Long parseKb(@NotNull JTextField field) throws InvalidSize {
        String text = field.getText().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            BigInteger kb = new BigDecimal(text).multiply(KB_PER_MB).toBigIntegerExact();
            if (kb.signum() <= 0) {
                throw new InvalidSize();
            }
            return kb.longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new InvalidSize();
        }
    }

    @NotNull
    private static String formatMb(@Nullable Long kb) {
        return kb == null ? "" : BigDecimal.valueOf(kb).divide(KB_PER_MB).stripTrailingZeros().toPlainString(); //$NON-NLS-1$
    }

    private static long toKb(@Nullable BigDecimal bytes) {
        return bytes == null ? 0 : bytes.toBigInteger().divide(BYTES_PER_KB).longValue();
    }

    @Nullable
    private static Long nullIfZero(long kb) {
        return kb == 0 ? null : kb;
    }
}
