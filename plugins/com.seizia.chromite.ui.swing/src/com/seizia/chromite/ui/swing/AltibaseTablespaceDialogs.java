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

import com.seizia.chromite.ext.altibase.model.AltibaseDataFile4Disk;
import com.seizia.chromite.ext.altibase.model.AltibaseTablespace;
import com.seizia.chromite.ext.altibase.model.AltibaseTablespaceDDL;
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
 * Settings dialogs of the Altibase-family tablespace commands (Altibase and ShardSphere share the model): New Tablespace,
 * New Data File, Autoextend Settings of a memory or volatile tablespace, Data File Settings.
 *
 * 설계: 다른 "New …" 대화상자({@link NewObjectDialogs})와 같은 틀이다({@link DesignerDialogs#showDialog}의 OK/취소, 칸 이름 + 값,
 * 오류는 단추 줄의 한 줄). 대화상자는 값만 받고 문장은 모델({@code AltibaseTablespaceDDL})이 만든다.
 * <ul>
 *     <li>크기 칸은 MB이고 소수도 받는다(KB로 나누어떨어져야 함). 빈 칸은 문장에서 빠져 서버 기본값을 따른다(디스크 파일 100 MB,
 *     NEXT·MAXSIZE의 서버 기본). 메모리·휘발성의 크기는 서버가 꼭 요구해서(없으면 구문 오류) 비울 수 없다. 이 규칙을 대화상자에 글로
 *     적지 않는다(설명 문구를 두지 않는다는 사용자 규칙, 다른 New 대화상자의 "(기본값)" 줄도 여기에는 맞지 않음).</li>
 *     <li>읽기 전용 값(데이터 파일 설정의 파일 경로)은 다른 대화상자처럼 편집할 수 없는 글 칸이지만 초점을 받지 않아, 처음 초점은 크기 칸이다.</li>
 *     <li>자동 확장 확인란을 끄면 새 개체에는 절을 쓰지 않는다(서버 기본: 꺼짐). 설정 대화상자에서는 OFF를 쓴다.</li>
 *     <li>설정 대화상자는 지금 값으로 채워지고, 바뀐 것만 문장이 된다. 자동 확장은 NEXT를 빼면 서버가 기본값으로 되돌리므로, 하나라도
 *     바뀌면 켜기·NEXT·MAXSIZE를 함께 다시 쓴다.</li>
 *     <li>서버가 거절하는 값(메모리 크기가 4 MB의 배수가 아님, 최소 크기보다 작음 등)은 여기서 막지 않는다. 서버의 오류가 실패한 변경으로
 *     보인다(다른 변경과 같음).</li>
 * </ul>
 * EDT에서 부른다. 자동 확장 대화상자는 지금 값을 진행 대화상자에서 읽는다.
 *
 * 변경 이력:
 * - 2026-11-15 최초 작성(앱 변경 #126, docs/chromite/altibase.md "테이블스페이스 관리")
 * - 2026-11-15 검토 뒤: 새 테이블스페이스·새 데이터 파일의 "(기본값)" 안내 줄을 뺌, 파일 경로 칸이 초점을 받지 않음
 * - 2026-11-16 크기·증가 크기·최대 크기 칸이 숫자와 소수점 하나만 받음({@link NumberInputFilter}, 앱 변경 #133)
 */
final class AltibaseTablespaceDialogs {

    private static final BigDecimal KB_PER_MB = BigDecimal.valueOf(1024);

    /**
     * The result of a settings dialog whose values are the current ones: nothing to run.
     */
    static final Object UNCHANGED = new Object();

    /**
     * A tablespace type in the type combo.
     */
    private record TypeChoice(@NotNull AltibaseTablespace.TbsType type, @NotNull String label) {
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

        int addTo(@NotNull JPanel form, int row, boolean withSize) {
            if (withSize) {
                DesignerDialogs.addRow(form, row++, CreateObjectMessages.create_label_size, sizeField);
            }
            DesignerDialogs.addRow(form, row++, "", autoextendBox); //$NON-NLS-1$
            DesignerDialogs.addRow(form, row++, CreateObjectMessages.create_label_next, nextField);
            DesignerDialogs.addRow(form, row++, CreateObjectMessages.create_label_max_size, maxField);
            return row;
        }

        /**
         * The autoextend clause of a new object: null when the box is off (the server's default).
         */
        @Nullable
        AltibaseTablespaceDDL.Autoextend newAutoextend() throws InvalidSize {
            return autoextendBox.isSelected() ? new AltibaseTablespaceDDL.Autoextend(true, parseKb(nextField), parseKb(maxField)) : null;
        }

        /**
         * The autoextend settings as entered (OFF included).
         */
        @NotNull
        AltibaseTablespaceDDL.Autoextend autoextend() throws InvalidSize {
            return autoextendBox.isSelected()
                ? new AltibaseTablespaceDDL.Autoextend(true, parseKb(nextField), parseKb(maxField))
                : new AltibaseTablespaceDDL.Autoextend(false, null, null);
        }

        void fill(@Nullable Long sizeKb, @NotNull AltibaseTablespaceDDL.Autoextend autoextend) {
            sizeField.setText(formatMb(sizeKb));
            autoextendBox.setSelected(autoextend.enabled());
            nextField.setText(autoextend.enabled() ? formatMb(autoextend.nextKb()) : ""); //$NON-NLS-1$
            maxField.setText(autoextend.enabled() ? formatMb(autoextend.maxKb()) : ""); //$NON-NLS-1$
            syncEnabled();
        }
    }

    /**
     * A size field that is not a positive number of MB (whole KB).
     */
    private static final class InvalidSize extends Exception {
    }

    private AltibaseTablespaceDialogs() {
    }

    //////////////////////////////////////////////////////////
    // New Tablespace

    /**
     * New Tablespace: name, type, data file (disk and temporary), size and autoextend.
     */
    @Nullable
    static NewObjectDialogs.Configurer askTablespace(@NotNull Component owner, @NotNull String title) {
        JTextField nameField = new JTextField(28);
        JComboBox<TypeChoice> typeCombo = new JComboBox<>(new TypeChoice[]{
            new TypeChoice(AltibaseTablespace.TbsType.MEMORY_USER_DATA, CreateObjectMessages.create_type_memory),
            new TypeChoice(AltibaseTablespace.TbsType.DISK_USER_DATA, CreateObjectMessages.create_type_disk),
            new TypeChoice(AltibaseTablespace.TbsType.DISK_USER_TEMP, CreateObjectMessages.create_type_temporary),
            new TypeChoice(AltibaseTablespace.TbsType.VOLATILE_USER_DATA, CreateObjectMessages.create_type_volatile)
        });
        // CREATE TABLESPACE without a type keyword is a disk tablespace
        typeCombo.setSelectedIndex(1);
        JTextField fileField = new JTextField(28);
        SpaceFields space = new SpaceFields();
        Runnable syncType = () -> fileField.setEnabled(hasFile(selectedType(typeCombo)));
        typeCombo.addActionListener(e -> syncType.run());
        syncType.run();

        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;
        DesignerDialogs.addRow(form, row++, CreateObjectMessages.create_label_name, nameField);
        DesignerDialogs.addRow(form, row++, CreateObjectMessages.create_label_type, typeCombo);
        DesignerDialogs.addRow(form, row++, CreateObjectMessages.create_label_data_file, fileField);
        space.addTo(form, row, true);

        record Values(String name, AltibaseTablespace.TbsType type, AltibaseTablespaceDDL.Space space) {
        }
        Values values = DesignerDialogs.showDialog(owner, title, form, () -> {
            String name = nameField.getText().trim();
            if (name.isEmpty()) {
                return DesignerDialogs.error(CreateObjectMessages.create_error_name);
            }
            AltibaseTablespace.TbsType type = selectedType(typeCombo);
            String file = null;
            if (hasFile(type)) {
                file = fileField.getText().trim();
                if (!isValidFile(file)) {
                    return DesignerDialogs.error(CreateObjectMessages.create_error_data_file);
                }
            }
            try {
                Long sizeKb = parseKb(space.sizeField);
                // Memory and volatile tablespaces have no default size (the server's syntax needs SIZE)
                if (sizeKb == null && !hasFile(type)) {
                    return DesignerDialogs.error(CreateObjectMessages.create_error_size);
                }
                return new Values(name, type, new AltibaseTablespaceDDL.Space(file, sizeKb, space.newAutoextend()));
            } catch (InvalidSize e) {
                return DesignerDialogs.error(CreateObjectMessages.create_error_size);
            }
        });
        if (values == null) {
            return null;
        }
        return (monitor, object, context) -> {
            AltibaseTablespace tablespace = (AltibaseTablespace) object;
            tablespace.setName(DBObjectNameCaseTransformer.transformName(tablespace.getDataSource(), values.name()));
            tablespace.setTbsType(values.type());
            tablespace.setInitialSpace(values.space());
        };
    }

    //////////////////////////////////////////////////////////
    // New Data File

    /**
     * New Data File of a disk data or temporary tablespace: file name, size and autoextend.
     */
    @Nullable
    static NewObjectDialogs.Configurer askDataFile(@NotNull Component owner, @NotNull String title) {
        JTextField fileField = new JTextField(28);
        SpaceFields space = new SpaceFields();
        JPanel form = new JPanel(new GridBagLayout());
        DesignerDialogs.addRow(form, 0, CreateObjectMessages.create_label_data_file, fileField);
        space.addTo(form, 1, true);

        AltibaseTablespaceDDL.Space values = DesignerDialogs.showDialog(owner, title, form, () -> {
            String file = fileField.getText().trim();
            if (!isValidFile(file)) {
                return DesignerDialogs.error(CreateObjectMessages.create_error_data_file);
            }
            try {
                return new AltibaseTablespaceDDL.Space(file, parseKb(space.sizeField), space.newAutoextend());
            } catch (InvalidSize e) {
                return DesignerDialogs.error(CreateObjectMessages.create_error_size);
            }
        });
        if (values == null) {
            return null;
        }
        return (monitor, object, context) -> ((AltibaseDataFile4Disk) object).setInitialSpace(values);
    }

    //////////////////////////////////////////////////////////
    // Settings of existing objects (Object Explorer maintenance commands)

    /**
     * Autoextend Settings of a memory or volatile tablespace, filled with its current settings (read in a progress
     * dialog).
     *
     * @return the new settings, {@link #UNCHANGED} if they are the current ones, or null if canceled or the current
     * settings could not be read
     */
    @Nullable
    static Object askAutoextend(@NotNull Component owner, @NotNull String title, @NotNull AltibaseTablespace tablespace) {
        AltibaseTablespaceDDL.Autoextend current = NewObjectDialogs.load(title, tablespace::readAutoextend);
        if (current == null) {
            return null;
        }
        SpaceFields space = new SpaceFields();
        space.fill(null, current);
        JPanel form = new JPanel(new GridBagLayout());
        space.addTo(form, 0, false);
        return DesignerDialogs.showDialog(owner, title, form, () -> {
            try {
                AltibaseTablespaceDDL.Autoextend entered = space.autoextend();
                return sameAutoextend(entered, current) ? UNCHANGED : entered;
            } catch (InvalidSize e) {
                return DesignerDialogs.error(CreateObjectMessages.create_error_size);
            }
        });
    }

    /**
     * Data File Settings: size and autoextend of a disk data or temp file, filled with its current values.
     *
     * @return the change, {@link #UNCHANGED} if the values are the current ones, or null if canceled
     */
    @Nullable
    static Object askDataFileSettings(@NotNull Component owner, @NotNull String title, @NotNull AltibaseDataFile4Disk file) {
        long currentSizeKb = file.getCurrSize().divide(BigInteger.valueOf(1024)).longValue();
        AltibaseTablespaceDDL.Autoextend current = file.getAutoExtend()
            ? new AltibaseTablespaceDDL.Autoextend(true, toKb(file.getNextSize()), toKb(file.getMaxSize()))
            : new AltibaseTablespaceDDL.Autoextend(false, null, null);
        JTextField fileField = new JTextField(file.getName(), 28);
        fileField.setEditable(false);
        // Shown as the other dialogs show a read-only value, but never focused: the first focus goes to the size field
        fileField.setFocusable(false);
        SpaceFields space = new SpaceFields();
        space.fill(currentSizeKb, current);
        JPanel form = new JPanel(new GridBagLayout());
        DesignerDialogs.addRow(form, 0, CreateObjectMessages.create_label_data_file, fileField);
        space.addTo(form, 1, true);
        return DesignerDialogs.showDialog(owner, title, form, () -> {
            try {
                Long sizeKb = parseKb(space.sizeField);
                if (sizeKb == null) {
                    return DesignerDialogs.error(CreateObjectMessages.create_error_size);
                }
                AltibaseTablespaceDDL.Autoextend entered = space.autoextend();
                boolean sizeChanged = sizeKb != currentSizeKb;
                boolean autoextendChanged = !sameAutoextend(entered, current);
                if (!sizeChanged && !autoextendChanged) {
                    return UNCHANGED;
                }
                return new AltibaseTablespaceDDL.Space(null, sizeChanged ? sizeKb : null, autoextendChanged ? entered : null);
            } catch (InvalidSize e) {
                return DesignerDialogs.error(CreateObjectMessages.create_error_size);
            }
        });
    }

    //////////////////////////////////////////////////////////
    // Helpers

    @NotNull
    private static AltibaseTablespace.TbsType selectedType(@NotNull JComboBox<TypeChoice> combo) {
        return ((TypeChoice) Objects.requireNonNull(combo.getSelectedItem())).type();
    }

    private static boolean hasFile(@NotNull AltibaseTablespace.TbsType type) {
        return type == AltibaseTablespace.TbsType.DISK_USER_DATA || type == AltibaseTablespace.TbsType.DISK_USER_TEMP;
    }

    /**
     * A file name for a quoted SQL string: not empty, without quotes (as the PostgreSQL tablespace location).
     */
    private static boolean isValidFile(@NotNull String file) {
        return !file.isEmpty() && file.indexOf('\'') < 0;
    }

    private static boolean sameAutoextend(@NotNull AltibaseTablespaceDDL.Autoextend a, @NotNull AltibaseTablespaceDDL.Autoextend b) {
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

    @Nullable
    private static Long toKb(@NotNull BigInteger bytes) {
        long kb = bytes.divide(BigInteger.valueOf(1024)).longValue();
        return kb == 0 ? null : kb;
    }
}
