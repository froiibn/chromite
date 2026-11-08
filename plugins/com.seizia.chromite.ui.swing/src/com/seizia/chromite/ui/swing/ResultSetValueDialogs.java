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

import com.seizia.chromite.ext.postgresql.model.data.PostgreHStoreValueHandler;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.ValueEditMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPDataKind;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.data.DBDAttributeBinding;
import com.seizia.chromite.model.data.DBDCollection;
import com.seizia.chromite.model.data.DBDComplexValue;
import com.seizia.chromite.model.data.DBDComposite;
import com.seizia.chromite.model.data.DBDDisplayFormat;
import com.seizia.chromite.model.data.DBDValueCloneable;
import com.seizia.chromite.model.data.DBDValueHandler;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import com.seizia.chromite.model.struct.DBSAttributeBase;
import com.seizia.chromite.model.struct.DBSTypedObject;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 결과 칸 하나를 대화상자로 고치는 값 편집기: 참조 값 고르기, MySQL/MariaDB {@code SET} 값 고르기, 배열·구조체·hstore 항목 편집.
 * 그리드의 "값 편집"(Shift+Enter)과 칸 편집기의 단추가 연다({@link ResultSetGrid}).
 *
 * 설계:
 * <ul>
 *     <li>SWT는 값 관리자 확장점({@code ReferenceValueEditor}, {@code ComplexObjectEditor}, {@code MySQLEnumValueManager})이 값 형식마다
 *     편집기를 만들었다. Swing에는 그 확장점이 없어 칸의 형식을 모델 정보로만 판단한다({@link #getKind}): 열의 외래 키(바인딩이 이미 아는 관계,
 *     DB를 읽지 않음), {@code SET} 열({@link ResultSetEditModel#isMultiValueColumn}), 자료형 {@code ARRAY}·{@code STRUCT}, PostgreSQL hstore 처리기.</li>
 *     <li>대화상자는 값만 만든다. 결과는 열의 값 처리기로 셀 값이 되고({@link ResultSetEditModel#parseObject}) 다른 셀 편집처럼 저장할 때 DB에 쓴다.
 *     그래서 저장·저장하고 커밋, 실행 취소 규칙이 그대로다(swing-grid-editing.md). DB를 바꾸지 않으므로 OK가 기본 단추이고 Esc는 취소다.</li>
 *     <li>배열·구조체 항목은 글자로 고친다. 글자는 항목 형식의 값 처리기가 편집 형식으로 만들고 값으로 바꾼다(SWT {@code ComplexObjectEditor}가
 *     항목마다 값 처리기를 부르는 것과 같음). 새 값은 원래 값 객체를 복제해 항목만 바꾼다({@link DBDValueCloneable}): 요소 형식과 바인딩 방식이
 *     그대로 남아 PostgreSQL 처리기가 배열 문자열·복합 형식 문자열로 바인딩한다. 안에 든 배열·구조체는 여기서 고치지 않는다(셀 글자로 고침).</li>
 *     <li>hstore 값은 모델이 문자열로 다룬다({@code PostgreHStoreValueHandler}). 키·값 표로 고치고 PostgreSQL hstore 입력 형식
 *     ({@code "키"=>"값", "키"=>NULL}, 큰따옴표·역슬래시는 역슬래시로 이스케이프)으로 만든다({@link #formatHStore}, {@link #parseHStore}).</li>
 *     <li>NULL 항목은 회색 NULL로 보이고 "항목을 NULL로"로 만든다. 빈 글자는 빈 문자열이다(빈 문자열과 NULL을 구분).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: 참조 값·SET·배열·구조체·hstore 편집기 (docs/chromite/swing-value-editors.md)
 * - 2026-11-09 한국어 메뉴 이름의 끝 말줄임표를 없앤 것에 맞춰 주석의 메뉴 이름을 고침 (docs/chromite/swing-l10n.md)
 */
final class ResultSetValueDialogs {

    /**
     * The kind of value dialog of a cell.
     */
    enum Kind {
        /**
         * A column of a foreign key: pick a key of the referenced table ({@link ResultSetReferencePicker}).
         */
        REFERENCE,
        /**
         * MySQL/MariaDB {@code SET}: check any of the allowed values.
         */
        MULTI_VALUE,
        ARRAY,
        STRUCT,
        HSTORE
    }

    /**
     * What a dialog made: the input for the column's value handler ({@link ResultSetEditModel#parseObject}), or null
     * for NULL.
     */
    record Picked(@Nullable Object input) {
    }

    private ResultSetValueDialogs() {
    }

    /**
     * The dialog of a cell's column, or null when the cell is only typed (no database access).
     */
    @Nullable
    static Kind getKind(@NotNull ResultSetEditModel model, int column) {
        DBDAttributeBinding binding = model.getAttributes()[column];
        if (model.isMultiValueColumn(column)) {
            return Kind.MULTI_VALUE;
        }
        if (!ResultSetReferences.getCellAssociations(binding).isEmpty()) {
            return Kind.REFERENCE;
        }
        DBPDataKind dataKind = binding.getDataKind();
        if (dataKind == DBPDataKind.ARRAY) {
            return Kind.ARRAY;
        }
        if (dataKind == DBPDataKind.STRUCT) {
            return Kind.STRUCT;
        }
        if (binding.getValueHandler() instanceof PostgreHStoreValueHandler) {
            return Kind.HSTORE;
        }
        return null;
    }

    /**
     * Opens the dialog of a cell (EDT, modal).
     *
     * @return what to put in the cell, or null if the dialog was canceled
     */
    @Nullable
    static Picked edit(@NotNull Component parent, @NotNull ResultSetEditModel model, int row, int column) {
        Kind kind = getKind(model, column);
        if (kind == null) {
            return null;
        }
        Object value = model.getRow(row).values[column];
        try {
            return switch (kind) {
                case REFERENCE -> ResultSetReferencePicker.pick(parent, model, row, column);
                case MULTI_VALUE -> chooseSetValues(parent, model, column, value);
                case ARRAY -> editArray(parent, model, column, value);
                case STRUCT -> editStruct(parent, model, column, value);
                case HSTORE -> editHStore(parent, model, column, value);
            };
        } catch (DBCException e) {
            JOptionPane.showMessageDialog(parent, NLS.bind(ValueEditMessages.valueedit_cant_edit, ErrorText.describeLine(e)),
                model.getAttributes()[column].getName(), JOptionPane.WARNING_MESSAGE);
            return null;
        }
    }

    private static boolean isNullable(@NotNull DBDAttributeBinding binding) {
        return binding.getAttribute() == null || !binding.getAttribute().isRequired();
    }

    //////////////////////////////////////////////////////////
    // SET

    /**
     * MySQL/MariaDB {@code SET} (SWT {@code MySQLEnumValueManager}): check boxes of the allowed values. The text is the
     * checked values in their declared order, separated by commas, as MySQL writes it.
     */
    @Nullable
    private static Picked chooseSetValues(@NotNull Component parent, @NotNull ResultSetEditModel model, int column, @Nullable Object value) {
        DBDAttributeBinding binding = model.getAttributes()[column];
        String[] allowed = model.getEnumValues(column);
        if (allowed == null) {
            return null;
        }
        Set<String> current = new HashSet<>();
        boolean isNull = DBUtils.isNullValue(value);
        if (!isNull) {
            current.addAll(Arrays.asList(value.toString().split(","))); //$NON-NLS-1$
        }
        JPanel boxes = new JPanel(new GridLayout(0, 1, 0, 2));
        List<JCheckBox> checks = new ArrayList<>();
        for (String allowedValue : allowed) {
            JCheckBox check = new JCheckBox(allowedValue, current.contains(allowedValue));
            checks.add(check);
            boxes.add(check);
        }
        JCheckBox nullBox = new JCheckBox(ValueEditMessages.valueedit_value_null, isNull);
        nullBox.addActionListener(e -> checks.forEach(check -> check.setEnabled(!nullBox.isSelected())));
        checks.forEach(check -> check.setEnabled(!isNull));

        JPanel content = new JPanel(new BorderLayout(0, 6));
        content.add(new JLabel(ValueEditMessages.valueedit_set_hint), BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(boxes);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setPreferredSize(new Dimension(320, Math.min(320, boxes.getPreferredSize().height + 8)));
        content.add(scroll, BorderLayout.CENTER);
        if (isNullable(binding)) {
            content.add(nullBox, BorderLayout.SOUTH);
        }
        ValueDialog dialog = new ValueDialog(parent, NLS.bind(ValueEditMessages.valueedit_set_title, binding.getName()), content, () -> {
            if (nullBox.isSelected()) {
                return new Picked(null);
            }
            StringBuilder text = new StringBuilder();
            for (JCheckBox check : checks) {
                if (check.isSelected()) {
                    if (!text.isEmpty()) {
                        text.append(',');
                    }
                    text.append(check.getText());
                }
            }
            return new Picked(text.toString());
        });
        return dialog.open();
    }

    //////////////////////////////////////////////////////////
    // Array, structure, hstore

    /**
     * Array elements (SWT {@code ComplexObjectEditor} for collections): one line per element, added, removed and moved.
     */
    @Nullable
    private static Picked editArray(@NotNull Component parent, @NotNull ResultSetEditModel model, int column, @Nullable Object value)
        throws DBCException {
        DBDAttributeBinding binding = model.getAttributes()[column];
        DBDCollection collection = value instanceof DBDCollection c ? c : null;
        if (collection == null) {
            // A new row's empty array value, or a column whose value was not made yet: the handler makes an empty array
            collection = model.parseObject(column, "{}") instanceof DBDCollection c ? c : null; //$NON-NLS-1$
        }
        if (collection == null || collection.getComponentType() == null) {
            throw new DBCException(ValueEditMessages.valueedit_no_element_type);
        }
        DBSTypedObject itemType = collection.getComponentType();
        DBDValueHandler itemHandler = collection.getComponentValueHandler();
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < collection.getItemCount(); i++) {
            Object item = collection.getItem(i);
            if (item instanceof DBDComplexValue && !DBUtils.isNullValue(item)) {
                throw new DBCException(ValueEditMessages.valueedit_nested_read_only);
            }
            entries.add(Entry.of(null, itemHandler, itemType, item));
        }
        DBDCollection original = collection;
        EntriesEditor editor = new EntriesEditor(EntriesEditor.Mode.ARRAY, entries);
        boolean nullable = isNullable(binding);
        return openEntries(parent, NLS.bind(ValueEditMessages.valueedit_array_title, binding.getName()), editor,
            nullable, DBUtils.isNullValue(value), () -> {
                Object[] items = new Object[editor.entries.size()];
                try (DBCSession session = openSession(model)) {
                    for (int i = 0; i < items.length; i++) {
                        items[i] = editor.parseItem(i, session, itemHandler, itemType);
                    }
                }
                if (original instanceof DBDValueCloneable cloneable) {
                    // Same element type and handler: the column's handler binds it as it binds fetched arrays
                    DBDCollection copy = (DBDCollection) cloneable.cloneValue(new VoidProgressMonitor());
                    copy.setContents(items);
                    return new Picked(copy);
                }
                return new Picked(items);
            });
    }

    /**
     * Composite attributes (SWT {@code ComplexObjectEditor} for structures): one line per attribute, with its type.
     */
    @Nullable
    private static Picked editStruct(@NotNull Component parent, @NotNull ResultSetEditModel model, int column, @Nullable Object value)
        throws DBCException {
        DBDAttributeBinding binding = model.getAttributes()[column];
        if (!(value instanceof DBDComposite composite) || composite.getAttributeCount() == 0 || !(composite instanceof DBDValueCloneable)) {
            throw new DBCException(ValueEditMessages.valueedit_nested_read_only);
        }
        DBSAttributeBase[] attributes = composite.getAttributes();
        DBDValueHandler[] handlers = new DBDValueHandler[attributes.length];
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < attributes.length; i++) {
            handlers[i] = DBUtils.findValueHandler(model.getExecutionContext().getDataSource(), attributes[i]);
            Object item = composite.getAttributeValue(attributes[i]);
            Entry entry = Entry.of(attributes[i].getName(), handlers[i], attributes[i], item);
            entry.type = attributes[i].getTypeName();
            // Nested arrays and structures keep their value: edited as the cell's text
            entry.readOnly = item instanceof DBDComplexValue && !DBUtils.isNullValue(item);
            entries.add(entry);
        }
        EntriesEditor editor = new EntriesEditor(EntriesEditor.Mode.STRUCT, entries);
        return openEntries(parent, NLS.bind(ValueEditMessages.valueedit_struct_title, binding.getName()), editor,
            isNullable(binding), false, () -> {
                DBDComposite copy = (DBDComposite) ((DBDValueCloneable) composite).cloneValue(new VoidProgressMonitor());
                try (DBCSession session = openSession(model)) {
                    for (int i = 0; i < attributes.length; i++) {
                        if (!editor.entries.get(i).readOnly) {
                            copy.setAttributeValue(attributes[i], editor.parseItem(i, session, handlers[i], attributes[i]));
                        }
                    }
                }
                return new Picked(copy);
            });
    }

    /**
     * PostgreSQL hstore: key and value lines. Values may be NULL; keys can't.
     */
    @Nullable
    private static Picked editHStore(@NotNull Component parent, @NotNull ResultSetEditModel model, int column, @Nullable Object value)
        throws DBCException {
        DBDAttributeBinding binding = model.getAttributes()[column];
        boolean isNull = DBUtils.isNullValue(value);
        List<Entry> entries = new ArrayList<>();
        if (!isNull) {
            for (String[] pair : parseHStore(value.toString())) {
                Entry entry = new Entry(pair[0], pair[1] == null ? "" : pair[1], pair[1] == null);
                entries.add(entry);
            }
        }
        EntriesEditor editor = new EntriesEditor(EntriesEditor.Mode.HSTORE, entries);
        return openEntries(parent, NLS.bind(ValueEditMessages.valueedit_hstore_title, binding.getName()), editor,
            isNullable(binding), isNull, () -> {
                List<String[]> pairs = new ArrayList<>();
                Set<String> keys = new HashSet<>();
                for (int i = 0; i < editor.entries.size(); i++) {
                    Entry entry = editor.entries.get(i);
                    String key = CommonUtils.notEmpty(entry.key);
                    if (key.isEmpty()) {
                        throw new DBCException(NLS.bind(ValueEditMessages.valueedit_empty_key, i + 1));
                    }
                    if (!keys.add(key)) {
                        throw new DBCException(NLS.bind(ValueEditMessages.valueedit_duplicate_key, key));
                    }
                    pairs.add(new String[]{key, entry.isNull ? null : entry.text});
                }
                return new Picked(formatHStore(pairs));
            });
    }

    @NotNull
    private static DBCSession openSession(@NotNull ResultSetEditModel model) {
        return model.getExecutionContext().openSession(new VoidProgressMonitor(), DBCExecutionPurpose.UTIL, "Make value from editor"); //$NON-NLS-1$
    }

    /**
     * Shows the entries table with the whole-value NULL box and waits.
     */
    @Nullable
    private static Picked openEntries(
        @NotNull Component parent,
        @NotNull String title,
        @NotNull EntriesEditor editor,
        boolean nullable,
        boolean isNull,
        @NotNull ValueMaker maker
    ) {
        JCheckBox nullBox = new JCheckBox(ValueEditMessages.valueedit_value_null, isNull);
        editor.setEnabledAll(!isNull);
        nullBox.addActionListener(e -> editor.setEnabledAll(!nullBox.isSelected()));
        JPanel content = new JPanel(new BorderLayout(0, 6));
        content.add(editor.panel, BorderLayout.CENTER);
        if (nullable) {
            content.add(nullBox, BorderLayout.SOUTH);
        }
        ValueDialog dialog = new ValueDialog(parent, title, content, () -> {
            if (nullBox.isSelected()) {
                return new Picked(null);
            }
            editor.stopEditing();
            return maker.make();
        });
        return dialog.open();
    }

    //////////////////////////////////////////////////////////
    // hstore text

    /**
     * Parses hstore text ({@code "a"=>"1", b=>NULL}; PostgreSQL output quotes every key and value, input may leave
     * them bare).
     *
     * @return key and value pairs in order; a NULL value is null
     * @throws DBCException not hstore text
     */
    @NotNull
    static List<String[]> parseHStore(@NotNull String text) throws DBCException {
        List<String[]> pairs = new ArrayList<>();
        int[] position = {0};
        skipSpaces(text, position);
        while (position[0] < text.length()) {
            String key = readHStoreToken(text, position, true);
            skipSpaces(text, position);
            if (!text.startsWith("=>", position[0])) { //$NON-NLS-1$
                throw new DBCException("Expected => at position " + (position[0] + 1) + " of the hstore value"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            position[0] += 2;
            skipSpaces(text, position);
            boolean quoted = position[0] < text.length() && text.charAt(position[0]) == '"';
            String value = readHStoreToken(text, position, false);
            pairs.add(new String[]{key, !quoted && "NULL".equalsIgnoreCase(value) ? null : value}); //$NON-NLS-1$
            skipSpaces(text, position);
            if (position[0] < text.length()) {
                if (text.charAt(position[0]) != ',') {
                    throw new DBCException("Expected a comma at position " + (position[0] + 1) + " of the hstore value"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                position[0]++;
                skipSpaces(text, position);
            }
        }
        return pairs;
    }

    private static void skipSpaces(@NotNull String text, @NotNull int[] position) {
        while (position[0] < text.length() && Character.isWhitespace(text.charAt(position[0]))) {
            position[0]++;
        }
    }

    @NotNull
    private static String readHStoreToken(@NotNull String text, @NotNull int[] position, boolean key) throws DBCException {
        StringBuilder token = new StringBuilder();
        int i = position[0];
        if (i < text.length() && text.charAt(i) == '"') {
            i++;
            while (true) {
                if (i >= text.length()) {
                    throw new DBCException("Unterminated quoted string in the hstore value"); //$NON-NLS-1$
                }
                char c = text.charAt(i++);
                if (c == '\\' && i < text.length()) {
                    token.append(text.charAt(i++));
                } else if (c == '"') {
                    break;
                } else {
                    token.append(c);
                }
            }
        } else {
            // A bare token ends at a space, a comma, or (a key) at =>
            while (i < text.length()) {
                char c = text.charAt(i);
                if (Character.isWhitespace(c) || c == ',' || (key && text.startsWith("=>", i))) { //$NON-NLS-1$
                    break;
                }
                token.append(c == '\\' && i + 1 < text.length() ? text.charAt(++i) : c);
                i++;
            }
            if (token.isEmpty()) {
                throw new DBCException("Empty token at position " + (position[0] + 1) + " of the hstore value"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        position[0] = i;
        return token.toString();
    }

    /**
     * Makes hstore input text: every key and value quoted, {@code \} and {@code "} escaped, NULL values bare.
     */
    @NotNull
    static String formatHStore(@NotNull List<String[]> pairs) {
        StringBuilder text = new StringBuilder();
        for (String[] pair : pairs) {
            if (!text.isEmpty()) {
                text.append(", "); //$NON-NLS-1$
            }
            appendQuoted(text, pair[0]);
            text.append("=>"); //$NON-NLS-1$
            if (pair[1] == null) {
                text.append("NULL"); //$NON-NLS-1$
            } else {
                appendQuoted(text, pair[1]);
            }
        }
        return text.toString();
    }

    private static void appendQuoted(@NotNull StringBuilder text, @NotNull String value) {
        text.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                text.append('\\');
            }
            text.append(c);
        }
        text.append('"');
    }

    //////////////////////////////////////////////////////////
    // Dialog parts

    /**
     * Makes the dialog's result when OK is pressed.
     */
    @FunctionalInterface
    interface ValueMaker {
        /**
         * @throws DBCException an entry is not a valid value; the dialog stays open and shows why
         */
        @NotNull
        Picked make() throws DBCException;
    }

    /**
     * A modal dialog with content, an error line and OK / Cancel. OK is the default (the cell changes, not the
     * database); Esc cancels. A value the maker rejects keeps the dialog open with the reason.
     */
    static final class ValueDialog extends JDialog {
        private final ValueMaker maker;
        private final JLabel errorLabel = new JLabel(" "); //$NON-NLS-1$
        @Nullable
        private Picked result;

        ValueDialog(@NotNull Component parent, @NotNull String title, @NotNull JComponent content, @NotNull ValueMaker maker) {
            super(parent instanceof Window w ? w : SwingUtilities.getWindowAncestor(parent), title, ModalityType.APPLICATION_MODAL);
            this.maker = maker;
            errorLabel.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE, "foreground: $Chromite.status.error"); //$NON-NLS-1$
            JButton okButton = new JButton(CoreMessages.common_ok);
            okButton.addActionListener(e -> accept());
            JButton cancelButton = new JButton(CoreMessages.common_cancel);
            cancelButton.addActionListener(e -> dispose());
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            buttons.add(okButton);
            buttons.add(cancelButton);
            JPanel south = new JPanel(new BorderLayout(8, 0));
            south.add(errorLabel, BorderLayout.CENTER);
            south.add(buttons, BorderLayout.EAST);
            JPanel root = new JPanel(new BorderLayout(0, 8));
            root.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
            root.add(content, BorderLayout.CENTER);
            root.add(south, BorderLayout.SOUTH);
            setContentPane(root);
            getRootPane().setDefaultButton(okButton);
            getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
            pack();
            setLocationRelativeTo(parent);
        }

        private void accept() {
            try {
                result = maker.make();
                dispose();
            } catch (DBCException e) {
                errorLabel.setText(ErrorText.describeLine(e));
                errorLabel.setToolTipText(errorLabel.getText());
                UIManager.getLookAndFeel().provideErrorFeedback(this);
            }
        }

        @Nullable
        Picked open() {
            setVisible(true);
            return result;
        }
    }

    /**
     * One line of the entries table: a key (hstore), attribute name (structure) or nothing (array), and the value as
     * text or NULL.
     */
    static final class Entry {
        @Nullable
        String key;
        @Nullable
        String type;
        @NotNull
        String text;
        boolean isNull;
        boolean readOnly;
        /**
         * The value read (a read-only entry keeps it).
         */
        @Nullable
        Object value;

        Entry(@Nullable String key, @NotNull String text, boolean isNull) {
            this.key = key;
            this.text = text;
            this.isNull = isNull;
        }

        @NotNull
        static Entry of(@Nullable String key, @NotNull DBDValueHandler handler, @NotNull DBSTypedObject type, @Nullable Object value) {
            boolean isNull = DBUtils.isNullValue(value);
            Entry entry = new Entry(key, isNull ? "" : handler.getValueDisplayString(type, value, DBDDisplayFormat.EDIT), isNull);
            entry.value = value;
            return entry;
        }
    }

    /**
     * The entries table with its buttons: Add, Remove, Move Up, Move Down (array), Add and Remove (hstore), and Set
     * Item to NULL.
     */
    static final class EntriesEditor {
        enum Mode {
            ARRAY,
            STRUCT,
            HSTORE
        }

        private final Mode mode;
        final List<Entry> entries;
        final JPanel panel = new JPanel(new BorderLayout(6, 6));
        private final JTable table;
        private final EntriesModel tableModel = new EntriesModel();
        private final List<JComponent> controls = new ArrayList<>();

        EntriesEditor(@NotNull Mode mode, @NotNull List<Entry> entries) {
            this.mode = mode;
            this.entries = entries;
            table = new JTable(tableModel);
            table.setFillsViewportHeight(true);
            table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE); //$NON-NLS-1$
            table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            table.getTableHeader().setReorderingAllowed(false);
            table.setDefaultRenderer(Object.class, new EntryRenderer());
            // A NULL item starts its editor empty, not with the NULL marker's text
            table.setDefaultEditor(Object.class, new DefaultCellEditor(new JTextField()) {
                @Override
                public Component getTableCellEditorComponent(JTable editorTable, Object value, boolean isSelected, int row, int column) {
                    return super.getTableCellEditorComponent(editorTable, value == ResultSetGrid.NULL_VALUE ? "" : value, isSelected, row, column); //$NON-NLS-1$
                }
            });
            if (mode == Mode.ARRAY) {
                table.getColumnModel().getColumn(0).setPreferredWidth(40);
                table.getColumnModel().getColumn(0).setMaxWidth(60);
                table.getColumnModel().getColumn(1).setPreferredWidth(360);
            } else if (mode == Mode.STRUCT) {
                table.getColumnModel().getColumn(0).setPreferredWidth(140);
                table.getColumnModel().getColumn(1).setPreferredWidth(100);
                table.getColumnModel().getColumn(2).setPreferredWidth(260);
            } else {
                table.getColumnModel().getColumn(0).setPreferredWidth(160);
                table.getColumnModel().getColumn(1).setPreferredWidth(260);
            }
            JScrollPane scroll = new JScrollPane(table);
            scroll.setPreferredSize(new Dimension(500, 260));
            panel.add(scroll, BorderLayout.CENTER);
            controls.add(table);

            JPanel buttons = new JPanel(new GridLayout(0, 1, 0, 4));
            if (mode != Mode.STRUCT) {
                addButton(buttons, ValueEditMessages.valueedit_add, this::addEntry);
                addButton(buttons, ValueEditMessages.valueedit_remove, this::removeEntries);
            }
            if (mode == Mode.ARRAY) {
                addButton(buttons, ValueEditMessages.valueedit_move_up, () -> moveEntry(-1));
                addButton(buttons, ValueEditMessages.valueedit_move_down, () -> moveEntry(1));
            }
            addButton(buttons, ValueEditMessages.valueedit_item_null, this::setItemsNull);
            JPanel east = new JPanel(new BorderLayout());
            east.add(buttons, BorderLayout.NORTH);
            panel.add(east, BorderLayout.EAST);
            JLabel hintLabel = new JLabel(ValueEditMessages.valueedit_hint_text);
            hintLabel.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
            panel.add(hintLabel, BorderLayout.SOUTH);
        }

        private void addButton(@NotNull JPanel buttons, @NotNull String text, @NotNull Runnable task) {
            JButton button = new JButton(text);
            button.addActionListener(e -> task.run());
            buttons.add(button);
            controls.add(button);
        }

        void setEnabledAll(boolean enabled) {
            if (!enabled) {
                stopEditing();
            }
            controls.forEach(control -> control.setEnabled(enabled));
        }

        void stopEditing() {
            if (table.isEditing()) {
                table.getCellEditor().stopCellEditing();
            }
        }

        private void addEntry() {
            stopEditing();
            int index = table.getSelectionModel().getMaxSelectionIndex() + 1;
            if (index <= 0) {
                index = entries.size();
            }
            entries.add(index, new Entry(mode == Mode.HSTORE ? "" : null, "", false)); //$NON-NLS-1$
            tableModel.fireTableDataChanged();
            table.changeSelection(index, mode == Mode.HSTORE ? 0 : 1, false, false);
            table.editCellAt(index, mode == Mode.HSTORE ? 0 : 1);
            if (table.getEditorComponent() != null) {
                table.getEditorComponent().requestFocusInWindow();
            }
        }

        private void removeEntries() {
            stopEditing();
            int[] rows = table.getSelectedRows();
            for (int i = rows.length - 1; i >= 0; i--) {
                entries.remove(rows[i]);
            }
            tableModel.fireTableDataChanged();
        }

        private void moveEntry(int delta) {
            stopEditing();
            int row = table.getSelectedRow();
            int target = row + delta;
            if (row < 0 || target < 0 || target >= entries.size()) {
                return;
            }
            entries.add(target, entries.remove(row));
            tableModel.fireTableDataChanged();
            table.changeSelection(target, 1, false, false);
        }

        private void setItemsNull() {
            stopEditing();
            for (int row : table.getSelectedRows()) {
                Entry entry = entries.get(row);
                if (!entry.readOnly) {
                    entry.isNull = true;
                    entry.text = ""; //$NON-NLS-1$
                }
            }
            tableModel.fireTableDataChanged();
        }

        /**
         * The value of an entry, made by the item's handler from its text (null for a NULL entry, the read value for a
         * read-only one).
         *
         * @throws DBCException the text is not a valid value; the message names the row
         */
        @Nullable
        Object parseItem(int index, @NotNull DBCSession session, @NotNull DBDValueHandler handler, @NotNull DBSTypedObject type)
            throws DBCException {
            Entry entry = entries.get(index);
            if (entry.readOnly) {
                return entry.value;
            }
            if (entry.isNull) {
                return null;
            }
            try {
                return handler.getValueFromObject(session, type, entry.text, false, true);
            } catch (DBCException | RuntimeException e) {
                table.changeSelection(index, table.getColumnCount() - 1, false, false);
                throw new DBCException(NLS.bind(ValueEditMessages.valueedit_invalid_item, index + 1, ErrorText.describeLine(e)), e);
            }
        }

        private final class EntriesModel extends AbstractTableModel {
            @Override
            public int getRowCount() {
                return entries.size();
            }

            @Override
            public int getColumnCount() {
                return mode == Mode.STRUCT ? 3 : 2;
            }

            @Override
            public String getColumnName(int column) {
                return switch (mode) {
                    case ARRAY -> column == 0 ? ValueEditMessages.valueedit_col_index : ValueEditMessages.valueedit_col_value;
                    case STRUCT -> column == 0 ? ValueEditMessages.valueedit_col_attribute
                        : column == 1 ? ValueEditMessages.valueedit_col_type : ValueEditMessages.valueedit_col_value;
                    case HSTORE -> column == 0 ? ValueEditMessages.valueedit_col_key : ValueEditMessages.valueedit_col_value;
                };
            }

            @Override
            public Object getValueAt(int rowIndex, int columnIndex) {
                Entry entry = entries.get(rowIndex);
                if (columnIndex == getColumnCount() - 1) {
                    return entry.isNull ? ResultSetGrid.NULL_VALUE : entry.text;
                }
                return switch (mode) {
                    case ARRAY -> rowIndex + 1;
                    case STRUCT -> columnIndex == 0 ? entry.key : entry.type;
                    case HSTORE -> entry.key;
                };
            }

            @Override
            public boolean isCellEditable(int rowIndex, int columnIndex) {
                Entry entry = entries.get(rowIndex);
                if (entry.readOnly) {
                    return false;
                }
                return columnIndex == getColumnCount() - 1 || (mode == Mode.HSTORE && columnIndex == 0);
            }

            @Override
            public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
                Entry entry = entries.get(rowIndex);
                String text = aValue == ResultSetGrid.NULL_VALUE ? "" : CommonUtils.toString(aValue); //$NON-NLS-1$
                if (columnIndex == getColumnCount() - 1) {
                    if (entry.isNull && text.isEmpty()) {
                        // An untouched NULL item stays NULL
                        return;
                    }
                    entry.text = text;
                    entry.isNull = false;
                } else {
                    entry.key = text;
                }
                fireTableRowsUpdated(rowIndex, rowIndex);
            }
        }

        /**
         * NULL items in the secondary color, read-only (nested) items dimmed.
         */
        private final class EntryRenderer extends DefaultTableCellRenderer {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                Entry entry = entries.get(row);
                if (!isSelected) {
                    setForeground(value == ResultSetGrid.NULL_VALUE || entry.readOnly || (column < table.getColumnCount() - 1 && mode != Mode.HSTORE)
                        ? UIManager.getColor("Chromite.secondaryForeground") : table.getForeground()); //$NON-NLS-1$
                }
                return this;
            }
        }
    }

}

