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

import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.DBECommandContext;
import com.seizia.chromite.model.edit.DBEObjectEditor;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.edit.AbstractCommandContext;
import com.seizia.chromite.model.meta.PropertyLength;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.model.preferences.DBPPropertyDescriptor;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.properties.ObjectPropertyDescriptor;
import com.seizia.chromite.runtime.properties.PropertySourceEditable;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.ToolsMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Objects;
import java.util.function.Function;

/**
 * Editing the properties of a database object in the Properties grid ({@link PropertiesPanel}: the Properties tool window
 * and the Properties tab of Object Details): which properties may be edited, their cell editors, and saving a change.
 *
 * 설계:
 * <ul>
 *     <li>무엇을 고칠 수 있는지는 모델이 정한다. SWT 객체 편집기의 속성 탭처럼 {@link PropertySourceEditable}로 속성을 모으고
 *     {@link ObjectPropertyDescriptor#isEditable}을 묻는다: 객체 관리자({@link DBEObjectEditor})가 있고 편집을 허락하며, 저장된 객체에서는
 *     {@code @Property(updatable = true)}(또는 {@code updatableExpr})인 속성이다. 여기서 더 빼는 것은 이름(Rename, F2가 이름 바꾸기 명령을
 *     만든다; 속성 명령으로는 ALTER … RENAME이 나오지 않는다), 암호 속성(값이 미리 보기와 쿼리 관리자에 남는다), 그리고 이 표에서 편집기를
 *     줄 수 없는 형식(날짜, 목록 제공자가 없는 객체 참조)이다. 읽기 전용 연결과 메타데이터 변경 제한({@link DBUtils#isReadOnly})이면 모두 읽기 전용이다.</li>
 *     <li>편집기: 가능한 값이 있으면(열거형, 목록 제공자: 소유자, 테이블스페이스, 엔진, 문자 집합) 콤보 상자, 참·거짓은 True/False 콤보 상자,
 *     숫자와 한 줄 글은 글 상자, 여러 줄 글({@link PropertyLength#MULTILINE}, 주석)은 대화상자다. 가능한 값은 DB를 읽을 수 있어 속성을 읽는
 *     작업 스레드에서 미리 읽는다({@link #describe}).</li>
 *     <li>저장: 값을 하나 바꿀 때마다 바로 저장 흐름을 탄다({@link ExplorerObjectCommands#runChange}: 스크립트 미리 보기(취소가 기본 단추) →
 *     열린 트랜잭션 질문 → 연결의 기본 실행 문맥에서 DDL). 쿼리 창은 저마다 격리 문맥을 쓰므로({@code QueryWindowContext}) 사용자의 쿼리 트랜잭션에
 *     DDL이 섞이지 않는다("단일 연결" 연결은 트랜잭션 질문이 막는다). 바꾼 값을 모아 두었다가 저장하는 방식(SWT 편집기의 Save)은 쓰지 않았다.
 *     속성 창은 탐색기 선택을 따라가므로 저장하지 않은 값이 다른 노드로 옮겨 가면 사라지거나 질문을 남기기 때문이다. 시퀀스 설정처럼 여러 값을 바꾸면
 *     값마다 미리 보기가 나온다.</li>
 *     <li>되돌리기: {@link PropertySourceEditable#setPropertyValue}는 값을 객체에 바로 쓰고 명령을 쌓는다. 그래서 명령 묶음은 원자적이 아닌
 *     {@link AbstractCommandContext}(atomic=false)다. 원자적 묶음은 되돌릴 때 반영기({@code undoCommand})를 부르지 않아 객체에 새 값이 남는다.
 *     미리 보기에서 취소, 트랜잭션 질문에서 취소, 실행 오류, 문장이 없는 변경이면 {@code runChange}가 {@code resetChanges(true)}로 명령을 되돌려
 *     객체가 원래 값으로 돌아간다(더러운 상태로 남지 않음).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: 속성 창과 Object Details 속성 탭의 속성 편집(SWT 감사 B-E1, B-E2, C-15) (docs/chromite/swing-object-properties.md)
 */
final class ObjectPropertyEdits {

    /**
     * Columns of the multi-line editor.
     */
    private static final int MULTILINE_COLUMNS = 56;
    private static final int MULTILINE_ROWS = 10;

    /**
     * The cell editor of a property.
     */
    enum Kind {
        TEXT, MULTILINE, NUMBER, BOOLEAN, LIST
    }

    /**
     * An editable property as read in the job.
     *
     * @param value       the current value, not formatted
     * @param items       the possible values (LIST)
     * @param customValue LIST: a value that is not in the list may be typed
     */
    record Edit(
        @NotNull String id,
        @NotNull String name,
        @NotNull Kind kind,
        @NotNull Class<?> type,
        @Nullable Object value,
        @Nullable Object[] items,
        boolean customValue
    ) {
    }

    private ObjectPropertyEdits() {
    }

    /**
     * Job thread: the property source of an object whose properties the user may change, or null if none may be changed
     * (no object manager, new object, read-only connection or metadata, not connected).
     */
    @Nullable
    static PropertySourceEditable createSource(@NotNull DBNNode node, @NotNull Object object) {
        if (!(object instanceof DBSObject dbObject) || !dbObject.isPersisted()
            || DBWorkbench.getPlatform().getEditorsRegistry().getObjectManager(dbObject.getClass(), DBEObjectEditor.class) == null) {
            return null;
        }
        DBPDataSource dataSource = dbObject.getDataSource();
        if (dataSource == null || dataSource.getContainer().isConnectionReadOnly() || DBUtils.isReadOnly(dbObject)) {
            return null;
        }
        DBCExecutionContext executionContext = DBUtils.getDefaultContext(dbObject, false);
        if (executionContext == null) {
            return null;
        }
        // The source answers isEditable; changes are made by a new source in apply (the context of the moment)
        PropertySourceEditable source = new PropertySourceEditable(createCommandContext(executionContext), node, object);
        source.collectProperties();
        return source;
    }

    /**
     * Job thread: the editable property with its current value and possible values, or null if the grid does not edit it.
     */
    @Nullable
    static Edit describe(
        @NotNull DBRProgressMonitor monitor,
        @NotNull PropertySourceEditable source,
        @NotNull DBPPropertyDescriptor property
    ) {
        Object object = source.getEditableValue();
        if (!(property instanceof ObjectPropertyDescriptor descriptor) || object == null
            || descriptor.isNameProperty() || descriptor.isPassword() || !descriptor.isEditable(object)) {
            return null;
        }
        Class<?> type = descriptor.getDataType();
        Object[] items = descriptor.getPossibleValues(object);
        Kind kind;
        if (items != null) {
            kind = Kind.LIST;
        } else if (type == Boolean.class || type == Boolean.TYPE) {
            kind = Kind.BOOLEAN;
        } else if (type == String.class) {
            kind = descriptor.getLength() == PropertyLength.MULTILINE ? Kind.MULTILINE : Kind.TEXT;
        } else if (isNumberType(type)) {
            kind = Kind.NUMBER;
        } else {
            // Dates, object references without a list: no editor in the grid
            return null;
        }
        Object value = source.getPropertyValue(monitor, object, descriptor, false);
        return new Edit(descriptor.getId(), descriptor.getDisplayName(), kind, type, value, items,
            kind == Kind.LIST && descriptor.allowCustomValue());
    }

    private static boolean isNumberType(@NotNull Class<?> type) {
        return Number.class.isAssignableFrom(type)
            || type == Integer.TYPE || type == Long.TYPE || type == Short.TYPE || type == Byte.TYPE
            || type == Double.TYPE || type == Float.TYPE;
    }

    //////////////////////////////////////////////////////////
    // Editors

    /**
     * The cell editor of the property. It starts from the property's value, not from the formatted text the grid shows
     * (numbers with grouping, object names).
     *
     * @param format the grid's display text of a value (combo box items)
     */
    @NotNull
    static DefaultCellEditor createCellEditor(@NotNull Edit edit, @NotNull Function<Object, String> format) {
        return switch (edit.kind()) {
            case BOOLEAN -> new ValueCellEditor(createComboBox(new Object[]{Boolean.TRUE, Boolean.FALSE}, false, format), edit.value());
            case LIST -> new ValueCellEditor(createComboBox(Objects.requireNonNull(edit.items()), edit.customValue(), format), edit.value());
            default -> new ValueCellEditor(new JTextField(), toText(edit.value()));
        };
    }

    @NotNull
    private static JComboBox<Object> createComboBox(@NotNull Object[] items, boolean editable, @NotNull Function<Object, String> format) {
        JComboBox<Object> comboBox = new JComboBox<>(items);
        comboBox.setEditable(editable);
        comboBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                return super.getListCellRendererComponent(list, format.apply(value), index, isSelected, cellHasFocus);
            }
        });
        return comboBox;
    }

    /**
     * The value as editor text: numbers without exponent, null as empty text.
     */
    @NotNull
    private static String toText(@Nullable Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        return value == null ? "" : value.toString(); //$NON-NLS-1$
    }

    /**
     * Asks a multi-line value (comments) in a dialog.
     *
     * @return the new text, or null if canceled
     */
    @Nullable
    static String editMultiline(@NotNull Component parent, @NotNull Edit edit) {
        JTextArea textArea = new JTextArea(toText(edit.value()), MULTILINE_ROWS, MULTILINE_COLUMNS);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setCaretPosition(0);
        JScrollPane scrollPane = new JScrollPane(textArea);
        // The text area gets the focus when the dialog opens, so typing starts at once
        textArea.addHierarchyListener(e -> {
            if (textArea.isShowing()) {
                SwingUtilities.invokeLater(textArea::requestFocusInWindow);
            }
        });
        int answer = JOptionPane.showConfirmDialog(SwingUtilities.getWindowAncestor(parent), scrollPane,
            NLS.bind(CoreMessages.properties_edit_title, edit.name()), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        return answer == JOptionPane.OK_OPTION ? textArea.getText() : null;
    }

    /**
     * Cell editor that starts from a given value (the property's own value) and needs a double click, as the grid's
     * single click selects the row.
     */
    private static final class ValueCellEditor extends DefaultCellEditor {
        private final Object initialValue;

        ValueCellEditor(@NotNull JTextField textField, @NotNull String initialValue) {
            super(textField);
            this.initialValue = initialValue;
            setClickCountToStart(2);
        }

        ValueCellEditor(@NotNull JComboBox<Object> comboBox, @Nullable Object initialValue) {
            super(comboBox);
            this.initialValue = initialValue;
            setClickCountToStart(2);
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
            return super.getTableCellEditorComponent(table, initialValue, isSelected, row, column);
        }
    }

    //////////////////////////////////////////////////////////
    // Values

    /**
     * The editor's value as the property's type.
     *
     * @throws IllegalArgumentException the text is not a value of the type (a number)
     */
    @Nullable
    static Object parse(@NotNull Edit edit, @Nullable Object input) {
        if (edit.kind() != Kind.NUMBER) {
            return input;
        }
        String text = input == null ? "" : input.toString().trim(); //$NON-NLS-1$
        Class<?> type = edit.type();
        if (text.isEmpty()) {
            if (type.isPrimitive()) {
                throw new IllegalArgumentException("Empty number"); //$NON-NLS-1$
            }
            return null;
        }
        if (type == Integer.class || type == Integer.TYPE) {
            return Integer.valueOf(text);
        } else if (type == Long.class || type == Long.TYPE) {
            return Long.valueOf(text);
        } else if (type == Short.class || type == Short.TYPE) {
            return Short.valueOf(text);
        } else if (type == Byte.class || type == Byte.TYPE) {
            return Byte.valueOf(text);
        } else if (type == Double.class || type == Double.TYPE) {
            return Double.valueOf(text);
        } else if (type == Float.class || type == Float.TYPE) {
            return Float.valueOf(text);
        } else if (type == BigInteger.class) {
            return new BigInteger(text);
        }
        // BigDecimal and Number
        return new BigDecimal(text);
    }

    /**
     * True if the new value differs from the property's value. Empty text equals no value, and numbers are compared by
     * value (1 and 1.0).
     */
    static boolean isChanged(@NotNull Edit edit, @Nullable Object newValue) {
        Object oldValue = edit.value();
        if (edit.kind() == Kind.TEXT || edit.kind() == Kind.MULTILINE) {
            return !CommonUtils.notEmpty(CommonUtils.toString(oldValue, null)).equals(CommonUtils.notEmpty(CommonUtils.toString(newValue, null)));
        }
        if (edit.kind() == Kind.NUMBER && oldValue instanceof Number && newValue instanceof Number) {
            try {
                return new BigDecimal(oldValue.toString()).compareTo(new BigDecimal(newValue.toString())) != 0;
            } catch (NumberFormatException e) {
                // NaN or infinity: compare as objects
                return !oldValue.equals(newValue);
            }
        }
        return !Objects.equals(oldValue, newValue);
    }

    //////////////////////////////////////////////////////////
    // Save

    /**
     * Changes the property and saves it the way the Object Explorer commands do: the script first (Cancel is the default
     * button), the open transaction question, the DDL on the connection's default context. A canceled or failed change
     * is undone in the model. EDT.
     */
    static void apply(
        @NotNull ExplorerObjectCommands commands,
        @NotNull DBNNode node,
        @NotNull DBSObject object,
        @NotNull Edit edit,
        @Nullable Object newValue
    ) {
        DBPDataSource dataSource = object.getDataSource();
        if (dataSource == null) {
            return;
        }
        commands.runChange(NLS.bind(CoreMessages.properties_change_title, edit.name()), dataSource.getContainer(),
            CoreMessages.properties_change_error, (monitor, changes) -> {
                DBCExecutionContext executionContext = DBUtils.getDefaultContext(object, false);
                if (executionContext == null) {
                    throw new DBException(NLS.bind(ToolsMessages.designer_error_not_connected, object.getName()));
                }
                DBECommandContext context = createCommandContext(executionContext);
                // Added first: whatever happens next, runChange undoes the context's commands
                changes.add(new ExplorerObjectCommands.Change(object, context, new HashMap<>()));
                PropertySourceEditable source = new PropertySourceEditable(context, node, object);
                source.collectProperties();
                if (!(source.getProperty(edit.id()) instanceof ObjectPropertyDescriptor property)) {
                    throw new DBException("Property '" + edit.id() + "' not found in " + object.getName()); //$NON-NLS-1$ //$NON-NLS-2$
                }
                try {
                    source.setPropertyValue(monitor, object, property, newValue);
                } catch (IllegalArgumentException e) {
                    // The value's constraints (minimum, maximum, not null)
                    throw new DBException(CommonUtils.notEmpty(e.getMessage()), e);
                }
            });
    }

    /**
     * A command context that applies each command to the model at once and undoes it on reset (not atomic, see the
     * class comment), as the SWT object editor's.
     */
    @NotNull
    private static DBECommandContext createCommandContext(@NotNull DBCExecutionContext executionContext) {
        return new AbstractCommandContext(executionContext, false) {
        };
    }
}
