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

import com.seizia.chromite.ui.swing.internal.EditorMessages;
import com.seizia.chromite.ui.swing.internal.GridVirtualMessages;
import com.seizia.chromite.ui.swing.internal.ToolsMessages;
import org.apache.commons.jexl3.JexlExpression;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataKind;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataTypeProvider;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.data.DBDAttributeBinding;
import com.seizia.chromite.model.data.DBDAttributeBindingCustom;
import com.seizia.chromite.model.data.DBDAttributeConstraint;
import com.seizia.chromite.model.data.DBDDataFilter;
import com.seizia.chromite.model.data.DBDDisplayFormat;
import com.seizia.chromite.model.exec.DBExecUtils;
import com.seizia.chromite.model.impl.data.DBDValueError;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import com.seizia.chromite.model.struct.DBSAttributeBase;
import com.seizia.chromite.model.struct.DBSDataContainer;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import com.seizia.chromite.model.virtual.DBVEntity;
import com.seizia.chromite.model.virtual.DBVEntityAttribute;
import com.seizia.chromite.model.virtual.DBVUtils;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.utils.GeneralUtils;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 가상(계산) 열(SWT {@code controls/resultset/virtual}의 {@code VirtualAttributeAddAction}, {@code EditVirtualAttributePage}): 행의 다른 열 값으로
 * Chromite가 계산하는 열. 결과에 붙여 보이고, 데이터로 고칠 수 없다.
 *
 * 설계:
 * <ul>
 *     <li>저장은 모델이 하던 대로 한다: 결과 테이블의 가상 모델({@link DBVEntity})에 {@code custom} 표시와 JEXL 식을 가진 {@link DBVEntityAttribute}를
 *     넣고 연결 설정(data-sources.json의 가상 메타데이터)에 쓴다. 가상 키({@link ResultSetVirtualKeys})와 같은 곳이라 SWT에서 만든 가상 열도 그대로 읽힌다.
 *     DB는 바꾸지 않는다.</li>
 *     <li>열은 그 테이블 하나에서 온 결과마다 붙는다(테이블 데이터와 그 테이블만 읽는 쿼리). 모델은 데이터 컨테이너가 테이블일 때만 가상 열을 붙이므로
 *     ({@link DBUtils#injectAndFilterAttributeBindings}: 쿼리는 쿼리마다 메모리에만 있는 가상 엔터티를 씀), 쿼리 결과는 열을 테이블에 묶은 뒤
 *     ({@code DBExecUtils.bindAttributes}) 단일 원본 테이블의 가상 열을 여기서 붙인다({@link #appendTableColumns}).</li>
 *     <li>값은 행을 읽은 뒤 계산한다({@link #computeRow}, 모델 {@link DBUtils#getAttributeValue}: 식 오류는 그 칸에 문구로). 결과 집합에는 없는 열이라
 *     값을 읽지 않는다. LOB처럼 읽은 뒤 놓아 주는 값은 식에서 NULL이다. 서로를 가리키는 식은 스택이 넘치므로 그 칸에 오류를 넣는다.</li>
 *     <li>편집되지 않는다: 바인딩에 행 식별자가 없어 편집 모델이 읽기 전용 열로 두고, 저장 엔진은 INSERT·UPDATE에서 뺀다
 *     ({@code DBDResultSetDataUpdater.isVirtualColumn}). 서버에 없는 열이라 필터를 걸 수 없고, 정렬은 표 안에서만 한다
 *     (다시 읽는 필터에서는 빼낸다, {@link #withoutVirtualColumns}).</li>
 *     <li>추가·고치기 대화상자는 이름, 형식(글자·숫자·참거짓·날짜), 식과 고른 행의 미리 보기다. 형식은 연결의 기본 데이터 형식 이름(숫자는 SQL 표준 decimal 형식 먼저, {@link #getTypeName})으로 저장해 값 처리기가
 *     표시 형식을 정한다. 설정을 바꾸는 대화상자라 기본 단추는 Cancel이고, 지우기는 확인(기본 Cancel) 뒤에 한다.</li>
 * </ul>
 * 대화상자 메서드는 EDT에서 부른다. 바인딩·계산 메서드는 결과를 읽는 Job 스레드에서도 부른다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-saved-filters-virtual-columns.md "가상 열")
 */
final class ResultSetVirtualColumns {

    private static final Log log = Log.getLog(ResultSetVirtualColumns.class);

    private static final String DEFAULT_NAME = "vcolumn"; //$NON-NLS-1$
    private static final DBPDataKind[] KINDS = {DBPDataKind.STRING, DBPDataKind.NUMERIC, DBPDataKind.BOOLEAN, DBPDataKind.DATETIME};
    /**
     * SQL standard decimal type names, in order of preference (see {@link #getTypeName}).
     */
    private static final String[] DECIMAL_TYPE_NAMES = {"numeric", "decimal"}; //$NON-NLS-1$ //$NON-NLS-2$

    private ResultSetVirtualColumns() {
    }

    //////////////////////////////////////////////////////////
    // Result columns

    /**
     * The number of the bindings that are columns of the result set (the virtual columns come after them).
     */
    static int getReadCount(@NotNull DBDAttributeBinding[] bindings) {
        int count = 0;
        for (DBDAttributeBinding binding : bindings) {
            if (!binding.isCustom()) {
                count++;
            }
        }
        return count;
    }

    /**
     * The bindings with the virtual columns of the result's single source table at the end. A table's own data
     * container has them already (the model adds them when the bindings are made); a query over one table gets them
     * here, once its columns are bound to the table.
     */
    @NotNull
    static DBDAttributeBinding[] appendTableColumns(
        @NotNull DBPDataSource dataSource,
        @NotNull DBSDataContainer dataContainer,
        @NotNull DBDAttributeBinding[] bindings
    ) {
        for (DBDAttributeBinding binding : bindings) {
            if (binding.isCustom()) {
                return bindings;
            }
        }
        DBSEntity entity = DBExecUtils.detectSingleSourceTable(bindings);
        if (entity == null) {
            return bindings;
        }
        List<DBVEntityAttribute> columns = DBVUtils.getCustomAttributes(entity);
        if (columns.isEmpty()) {
            return bindings;
        }
        DBDAttributeBinding[] result = Arrays.copyOf(bindings, bindings.length + columns.size());
        for (int i = 0; i < columns.size(); i++) {
            result[bindings.length + i] = new DBDAttributeBindingCustom(null, dataContainer, dataSource, columns.get(i), bindings.length + i);
        }
        return result;
    }

    /**
     * Calculates the virtual columns of a row: the value (for the value panel, sorting and aggregates) and the display
     * string. The other columns' values must be in {@code values} already.
     *
     * @param cells the row's cell values, or null
     */
    static void computeRow(
        @NotNull DBDAttributeBinding[] bindings,
        @NotNull Object[] values,
        @NotNull Object[] display,
        @Nullable Object[] cells
    ) {
        for (int i = 0; i < bindings.length; i++) {
            DBDAttributeBinding binding = bindings[i];
            if (!binding.isCustom()) {
                continue;
            }
            Object value;
            String error = null;
            try {
                value = DBUtils.getAttributeValue(binding, bindings, values);
            } catch (StackOverflowError e) {
                // Expressions that refer to each other
                value = null;
                error = "Recursive virtual column expression"; //$NON-NLS-1$
            }
            if (value instanceof DBDValueError valueError) {
                value = null;
                error = valueError.getErrorTitle();
            }
            values[i] = value;
            if (cells != null) {
                cells[i] = value;
            }
            if (error != null) {
                display[i] = NLS.bind(EditorMessages.editor_grid_cell_error, error);
            } else if (DBUtils.isNullValue(value)) {
                display[i] = ResultSetGrid.NULL_VALUE;
            } else {
                display[i] = binding.getValueRenderer().getValueDisplayString(binding, value, DBDDisplayFormat.UI);
            }
        }
    }

    /**
     * A copy of a filter without the sort and conditions of virtual columns: the database has no such column, so a
     * filter that is read on the server leaves them out.
     */
    @NotNull
    static DBDDataFilter withoutVirtualColumns(@NotNull DBDDataFilter filter) {
        DBDDataFilter result = new DBDDataFilter(filter);
        for (DBDAttributeConstraint constraint : result.getConstraints()) {
            if (isVirtual(constraint)) {
                constraint.setOrderPosition(0);
                constraint.setOrderDescending(false);
                // Also the operator and value: the criteria setter clears them
                ResultSetColumnFilterPopup.clearCondition(constraint);
            }
        }
        return result;
    }

    /**
     * Whether a filter sorts by a virtual column (then the sort is done in the grid).
     */
    static boolean hasVirtualOrdering(@NotNull DBDDataFilter filter) {
        for (DBDAttributeConstraint constraint : filter.getOrderConstraints()) {
            if (isVirtual(constraint)) {
                return true;
            }
        }
        return false;
    }

    static boolean isVirtual(@NotNull DBDAttributeConstraint constraint) {
        DBSAttributeBase attribute = constraint.getAttribute();
        return attribute instanceof DBDAttributeBinding binding && binding.isCustom();
    }

    //////////////////////////////////////////////////////////
    // Add, edit, remove

    /**
     * Add Virtual Column / Edit Virtual Column: name, type and expression, with a preview on a row of the result.
     *
     * @param existing   the virtual column to edit, or null to add one
     * @param previewRow the values of the row the preview is calculated on (by binding), or null
     * @return whether the column was saved (the result should be read again)
     */
    static boolean edit(
        @NotNull Component parent,
        @NotNull DBSEntity entity,
        @NotNull DBDAttributeBinding[] bindings,
        @Nullable DBVEntityAttribute existing,
        @Nullable Object[] previewRow
    ) {
        JTextField nameField = new JTextField(existing != null ? existing.getName() : makeUniqueName(entity, bindings), 24);
        JComboBox<DBPDataKind> kindCombo = new JComboBox<>(KINDS);
        kindCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                return super.getListCellRendererComponent(list, value instanceof DBPDataKind kind ? getKindLabel(kind) : value, index,
                    isSelected, cellHasFocus);
            }
        });
        kindCombo.setSelectedItem(existing != null && Arrays.asList(KINDS).contains(existing.getDataKind()) ? existing.getDataKind() : DBPDataKind.STRING);
        JTextArea expressionArea = new JTextArea(existing != null ? CommonUtils.notEmpty(existing.getExpression()) : "", 4, 40); //$NON-NLS-1$
        expressionArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, expressionArea.getFont().getSize()));
        expressionArea.setLineWrap(true);
        expressionArea.setWrapStyleWord(true);
        List<String> columnNames = new ArrayList<>();
        for (DBDAttributeBinding binding : bindings) {
            boolean self = existing != null && binding.isCustom() && binding.getName().equals(existing.getName());
            if (!self && isIdentifier(binding.getLabel())) {
                columnNames.add(binding.getLabel());
            }
        }
        JComboBox<String> columnCombo = new JComboBox<>(columnNames.toArray(new String[0]));
        JButton insertButton = new JButton(GridVirtualMessages.gridvirt_column_insert);
        insertButton.addActionListener(e -> {
            if (columnCombo.getSelectedItem() instanceof String name) {
                expressionArea.replaceSelection(name);
                expressionArea.requestFocusInWindow();
            }
        });
        insertButton.setEnabled(!columnNames.isEmpty());
        JLabel previewLabel = new JLabel(" "); //$NON-NLS-1$
        Runnable preview = () -> previewLabel.setText(makePreview(entity, bindings, previewRow, nameField.getText().trim(), expressionArea.getText()));
        expressionArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                preview.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                preview.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                preview.run();
            }
        });
        preview.run();

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(2, 0, 2, 6);
        gc.anchor = GridBagConstraints.WEST;
        int row = 0;
        addRow(form, gc, row++, GridVirtualMessages.gridvirt_column_name, nameField);
        addRow(form, gc, row++, GridVirtualMessages.gridvirt_column_kind, kindCombo);
        JScrollPane expressionScroll = new JScrollPane(expressionArea);
        // The grid bag falls back to minimum sizes when the form is narrower than it wants (the long help line): keep
        // the four lines of the expression (it shrank to one line in the Korean dialog)
        expressionScroll.setMinimumSize(expressionScroll.getPreferredSize());
        gc.anchor = GridBagConstraints.NORTHWEST;
        addRow(form, gc, row++, GridVirtualMessages.gridvirt_column_expression, expressionScroll);
        gc.anchor = GridBagConstraints.WEST;
        JPanel insertPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        insertPanel.add(columnCombo);
        insertPanel.add(insertButton);
        addRow(form, gc, row++, null, insertPanel);
        addRow(form, gc, row++, null, new JLabel(GridVirtualMessages.gridvirt_column_expression_help));
        addRow(form, gc, row, GridVirtualMessages.gridvirt_column_preview, previewLabel);

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JLabel(NLS.bind(GridVirtualMessages.gridvirt_column_intro, escape(DBUtils.getObjectFullName(entity, DBPEvaluationContext.UI)))),
            BorderLayout.NORTH);
        panel.add(form, BorderLayout.CENTER);

        String title = existing != null ? GridVirtualMessages.gridvirt_column_title_edit : GridVirtualMessages.gridvirt_column_title_add;
        String[] options = {ToolsMessages.button_ok, ToolsMessages.button_cancel};
        while (true) {
            // Cancel is the default button: the dialog changes the connection's settings
            int answer = JOptionPane.showOptionDialog(SwingUtilities.getWindowAncestor(parent), panel, title,
                JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[1]);
            if (answer != 0) {
                return false;
            }
            String name = nameField.getText().trim();
            String expression = expressionArea.getText().trim();
            String problem = validate(entity, bindings, existing, name, expression);
            if (problem != null) {
                DBWorkbench.getPlatformUI().showMessageBox(title, problem, true);
                continue;
            }
            DBVEntity vEntity = DBVUtils.getVirtualEntity(entity, true);
            if (vEntity == null) {
                return false;
            }
            DBPDataKind kind = kindCombo.getSelectedItem() instanceof DBPDataKind selected ? selected : DBPDataKind.STRING;
            DBVEntityAttribute column = existing != null ? existing : new DBVEntityAttribute(vEntity, null, name);
            column.setName(name);
            column.setCustom(true);
            column.setDataKind(kind);
            column.setTypeName(getTypeName(entity.getDataSource(), kind));
            column.setExpression(expression);
            if (existing == null) {
                vEntity.addVirtualAttribute(column);
            } else {
                DBUtils.fireObjectUpdate(vEntity, column);
            }
            persist(entity);
            return true;
        }
    }

    /**
     * Removes a virtual column after a confirmation (Cancel is the default).
     *
     * @return whether it was removed
     */
    static boolean remove(@NotNull Component parent, @NotNull DBSEntity entity, @NotNull DBVEntityAttribute column) {
        String[] options = {GridVirtualMessages.gridvirt_column_remove_button, ToolsMessages.button_cancel};
        int answer = JOptionPane.showOptionDialog(SwingUtilities.getWindowAncestor(parent),
            NLS.bind(GridVirtualMessages.gridvirt_column_remove_confirm, column.getName(), DBUtils.getObjectFullName(entity, DBPEvaluationContext.UI)),
            GridVirtualMessages.gridvirt_column_remove_title, JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
        if (answer != 0) {
            return false;
        }
        DBVEntity vEntity = DBVUtils.getVirtualEntity(entity, false);
        if (vEntity == null) {
            return false;
        }
        vEntity.removeVirtualAttribute(column);
        persist(entity);
        return true;
    }

    @NotNull
    static String getKindLabel(@NotNull DBPDataKind kind) {
        return switch (kind) {
            case NUMERIC -> GridVirtualMessages.gridvirt_column_kind_numeric;
            case BOOLEAN -> GridVirtualMessages.gridvirt_column_kind_boolean;
            case DATETIME -> GridVirtualMessages.gridvirt_column_kind_datetime;
            default -> GridVirtualMessages.gridvirt_column_kind_string;
        };
    }

    private static void addRow(@NotNull JPanel form, @NotNull GridBagConstraints gc, int row, @Nullable String label, @NotNull JComponent field) {
        gc.gridy = row;
        gc.gridx = 0;
        gc.weightx = 0;
        gc.fill = GridBagConstraints.NONE;
        form.add(label == null ? new JLabel() : new JLabel(label), gc);
        gc.gridx = 1;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;
        form.add(field, gc);
    }

    /**
     * Whether an expression can name the column: the model resolves the expression's variables by column label
     * ({@code DBVDataContext}), and a label with spaces or symbols is not a variable name. The context's {@code row}
     * object can't be read in an expression either: the model's JEXL permissions allow only the expression namespaces
     * ({@code DBVUtils.parseExpression}), so {@code row['unit price']} is an undefined property.
     */
    private static boolean isIdentifier(@NotNull String name) {
        boolean identifier = !name.isEmpty() && Character.isJavaIdentifierStart(name.charAt(0));
        for (int i = 1; identifier && i < name.length(); i++) {
            identifier = Character.isJavaIdentifierPart(name.charAt(i));
        }
        return identifier;
    }

    @NotNull
    private static String makePreview(
        @NotNull DBSEntity entity,
        @NotNull DBDAttributeBinding[] bindings,
        @Nullable Object[] previewRow,
        @NotNull String name,
        @NotNull String expression
    ) {
        if (expression.isBlank()) {
            return " "; //$NON-NLS-1$
        }
        JexlExpression parsed;
        try {
            parsed = DBVUtils.parseExpression(expression);
        } catch (Exception e) {
            return NLS.bind(GridVirtualMessages.gridvirt_column_expression_invalid, GeneralUtils.getExpressionParseMessage(e));
        }
        if (previewRow == null) {
            return GridVirtualMessages.gridvirt_column_preview_none;
        }
        Object value;
        try {
            value = DBVUtils.evaluateDataExpression(entity, bindings, previewRow, parsed, name);
        } catch (StackOverflowError e) {
            return NLS.bind(GridVirtualMessages.gridvirt_column_expression_invalid, "recursion"); //$NON-NLS-1$
        }
        return value == null ? "NULL" : CommonUtils.truncateString(String.valueOf(value), 200); //$NON-NLS-1$
    }

    /**
     * Why the name or the expression can't be saved, or null.
     */
    @Nullable
    private static String validate(
        @NotNull DBSEntity entity,
        @NotNull DBDAttributeBinding[] bindings,
        @Nullable DBVEntityAttribute existing,
        @NotNull String name,
        @NotNull String expression
    ) {
        if (name.isEmpty()) {
            return GridVirtualMessages.gridvirt_column_name_required;
        }
        if (isNameTaken(entity, bindings, existing, name)) {
            return NLS.bind(GridVirtualMessages.gridvirt_column_name_exists, name);
        }
        if (expression.isEmpty()) {
            return GridVirtualMessages.gridvirt_column_expression_required;
        }
        try {
            DBVUtils.parseExpression(expression);
        } catch (Exception e) {
            return NLS.bind(GridVirtualMessages.gridvirt_column_expression_invalid, GeneralUtils.getExpressionParseMessage(e));
        }
        return null;
    }

    /**
     * Whether another column of the result or of the table, or another entry of the table's virtual model, has the
     * name: expressions find columns by name, so names must be unique.
     */
    private static boolean isNameTaken(
        @NotNull DBSEntity entity,
        @NotNull DBDAttributeBinding[] bindings,
        @Nullable DBVEntityAttribute existing,
        @NotNull String name
    ) {
        for (DBDAttributeBinding binding : bindings) {
            boolean self = existing != null && binding.isCustom() && binding.getName().equals(existing.getName());
            if (!self && (name.equalsIgnoreCase(binding.getLabel()) || name.equalsIgnoreCase(binding.getName()))) {
                return true;
            }
        }
        try {
            // The table's columns were read when the result was bound to the table (cached)
            for (DBSEntityAttribute attribute : CommonUtils.safeCollection(entity.getAttributes(new VoidProgressMonitor()))) {
                if (name.equalsIgnoreCase(attribute.getName())) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("Can't read the columns of " + entity.getName(), e);
        }
        DBVEntity vEntity = DBVUtils.getVirtualEntity(entity, false);
        if (vEntity != null && vEntity.getEntityAttributes() != null) {
            for (DBVEntityAttribute attribute : vEntity.getEntityAttributes()) {
                if (attribute != existing && name.equalsIgnoreCase(attribute.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    @NotNull
    private static String makeUniqueName(@NotNull DBSEntity entity, @NotNull DBDAttributeBinding[] bindings) {
        String name = DEFAULT_NAME;
        for (int index = 2; isNameTaken(entity, bindings, null, name); index++) {
            name = DEFAULT_NAME + index;
        }
        return name;
    }

    /**
     * The connection's default data type name for a kind of value (as SWT {@code EditVirtualAttributePage}): the value
     * handler that formats the column is chosen by it. Numbers take the SQL standard decimal type where the connection
     * has it: the defaults are integer types (PostgreSQL and SQL Server {@code int}), and an expression such as
     * {@code price * qty} has decimals, so the header would name a type the values are not.
     */
    @NotNull
    private static String getTypeName(@NotNull DBPDataSource dataSource, @NotNull DBPDataKind kind) {
        DBPDataTypeProvider provider = DBUtils.getAdapter(DBPDataTypeProvider.class, dataSource);
        if (provider != null && kind == DBPDataKind.NUMERIC) {
            for (String decimal : DECIMAL_TYPE_NAMES) {
                if (provider.getLocalDataType(decimal) != null) {
                    return decimal;
                }
            }
        }
        String typeName = provider == null ? null : provider.getDefaultDataTypeName(kind);
        return CommonUtils.isEmpty(typeName) ? kind.name().toLowerCase(Locale.ROOT) : typeName;
    }

    private static void persist(@NotNull DBSEntity entity) {
        if (!entity.getDataSource().getContainer().persistConfiguration()) {
            log.warn("Can't save the virtual columns of " + entity.getName());
        }
    }

    @NotNull
    private static String escape(@NotNull String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
    }
}
