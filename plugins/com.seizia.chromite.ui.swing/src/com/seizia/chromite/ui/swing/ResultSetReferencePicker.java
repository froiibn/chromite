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
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.ValueEditMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ModelPreferences;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.data.DBDAttributeBinding;
import com.seizia.chromite.model.data.DBDAttributeValue;
import com.seizia.chromite.model.data.DBDDisplayFormat;
import com.seizia.chromite.model.data.DBDLabelValuePair;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSDictionary;
import com.seizia.chromite.model.struct.DBSDictionaryAccessor;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSEntityAssociation;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import com.seizia.chromite.model.struct.DBSEntityAttributeRef;
import com.seizia.chromite.model.struct.DBSEntityReferrer;
import com.seizia.chromite.model.struct.DBStructUtils;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 외래 키 열의 값을 참조하는 테이블의 키 값에서 고르는 대화상자(SWT {@code ReferenceValueEditor}).
 *
 * 설계:
 * <ul>
 *     <li>값 목록은 모델의 사전 접근기({@link DBSDictionaryAccessor})가 읽는다. 어느 관계를 쓸지는 SWT처럼
 *     {@link DBStructUtils#getEnumerableConstraint}(열의 첫 외래 키 중 참조 테이블이 값을 나열할 수 있는 것)가 정하고,
 *     참조되는 열은 {@link DBUtils#getReferenceAttribute}로 찾는다. 설명 열(가상 엔터티의 설명 열, 없으면 모델이 고른 글자 열)과
 *     정렬·검색 SQL도 접근기가 만든다. 그래서 실제 외래 키와 가상 외래 키 모두에 쓰고 DB마다 다른 코드는 없다.</li>
 *     <li>여러 열 외래 키는 같은 행의 다른 키 열 값을 조건으로 준다(SWT {@code restColumns}와 같음): 그 값과 맞는 키만 나온다.</li>
 *     <li>한 번에 읽는 수는 연결 설정 {@link ModelPreferences#DICTIONARY_MAX_ROWS}(기본 200)이다. 검색어가 없으면 처음부터({@code getValues}),
 *     있으면 값과 설명에서 대소문자 없이 찾는다({@code getSimilarValues}). "더 읽기"가 다음 묶음을 붙인다. SWT의 지금 값 주변 읽기
 *     ({@code getValuesNear})는 접근기마다 지원이 달라 쓰지 않고, 읽은 목록에 지금 값이 있으면 고른다.</li>
 *     <li>읽기는 백그라운드 Job이 하고, 입력이 바뀌면 0.3초 뒤 다시 읽는다. 늦게 끝난 이전 읽기 결과는 버린다(세대 번호).</li>
 *     <li>고른 값은 키 열의 값이고, 열의 값 처리기가 셀 값으로 바꾼다({@link ResultSetValueDialogs.Picked}). 셀만 바뀌므로 OK가 기본 단추다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: 참조 값 고르기 (docs/chromite/swing-value-editors.md)
 */
final class ResultSetReferencePicker extends JDialog {

    private static final int SEARCH_DELAY_MS = 300;

    private final ResultSetEditModel model;
    private final DBDAttributeBinding binding;
    private final int column;
    private final Object[] rowValues;
    @Nullable
    private final Object current;
    private final int pageSize;
    private final List<DBDLabelValuePair> pairs = new ArrayList<>();
    private final PairsModel pairsModel = new PairsModel();
    private final JTable table = new JTable(pairsModel);
    private final JTextField searchField = new JTextField(28);
    private final JLabel sourceLabel = new JLabel(" "); //$NON-NLS-1$
    private final JLabel statusLabel = new JLabel(" "); //$NON-NLS-1$
    private final JButton moreButton = new JButton(ValueEditMessages.valueedit_ref_more);
    private final JButton okButton = new JButton(CoreMessages.common_ok);
    private final Timer searchTimer;
    private int generation;
    @Nullable
    private ResultSetValueDialogs.Picked result;

    private ResultSetReferencePicker(@NotNull Window owner, @NotNull ResultSetEditModel model, int row, int column) {
        super(owner, NLS.bind(ValueEditMessages.valueedit_ref_title, model.getAttributes()[column].getName()), ModalityType.APPLICATION_MODAL);
        this.model = model;
        this.binding = model.getAttributes()[column];
        this.column = column;
        this.rowValues = model.getRow(row).values.clone();
        this.current = rowValues[column];
        int rows = model.getExecutionContext().getDataSource().getContainer().getPreferenceStore().getInt(ModelPreferences.DICTIONARY_MAX_ROWS);
        this.pageSize = rows > 0 ? rows : 200;

        searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, ValueEditMessages.valueedit_ref_search);
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, UiIcons.get("search")); //$NON-NLS-1$
        searchTimer = new Timer(SEARCH_DELAY_MS, e -> load(false));
        searchTimer.setRepeats(false);
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                searchTimer.restart();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                searchTimer.restart();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                searchTimer.restart();
            }
        });
        // Down moves from the search field to the list
        searchField.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "chromite.toList"); //$NON-NLS-1$
        searchField.getActionMap().put("chromite.toList", new AbstractAction() { //$NON-NLS-1$
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (table.getRowCount() > 0) {
                    table.requestFocusInWindow();
                    if (table.getSelectedRow() < 0) {
                        table.changeSelection(0, 0, false, false);
                    }
                }
            }
        });

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(160);
        table.getColumnModel().getColumn(1).setPreferredWidth(300);
        table.getSelectionModel().addListSelectionListener(e -> okButton.setEnabled(table.getSelectedRow() >= 0));
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e) && table.rowAtPoint(e.getPoint()) >= 0) {
                    pickSelected();
                }
            }
        });
        // Enter in the list picks the row (the table would move to the next row)
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "chromite.pick"); //$NON-NLS-1$
        table.getActionMap().put("chromite.pick", new AbstractAction() { //$NON-NLS-1$
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                pickSelected();
            }
        });
        statusLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        moreButton.setEnabled(false);
        moreButton.addActionListener(e -> load(true));
        okButton.setEnabled(false);
        okButton.addActionListener(e -> pickSelected());
        JButton cancelButton = new JButton(CoreMessages.common_cancel);
        cancelButton.addActionListener(e -> dispose());

        JPanel north = new JPanel(new BorderLayout(0, 6));
        north.add(sourceLabel, BorderLayout.NORTH);
        north.add(searchField, BorderLayout.CENTER);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(480, 320));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.add(okButton);
        buttons.add(cancelButton);
        JPanel south = new JPanel(new BorderLayout(8, 0));
        JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        statusRow.add(moreButton);
        statusRow.add(statusLabel);
        south.add(statusRow, BorderLayout.CENTER);
        south.add(buttons, BorderLayout.EAST);
        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        root.add(north, BorderLayout.NORTH);
        root.add(scroll, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);
        // Picking a value changes the cell, not the database: OK is the default, Esc cancels
        getRootPane().setDefaultButton(okButton);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * Shows the picker of a foreign key cell and waits.
     *
     * @return the key value picked, or null if canceled
     */
    @Nullable
    static ResultSetValueDialogs.Picked pick(@NotNull Component parent, @NotNull ResultSetEditModel model, int row, int column) {
        Window window = parent instanceof Window w ? w : SwingUtilities.getWindowAncestor(parent);
        ResultSetReferencePicker picker = new ResultSetReferencePicker(window, model, row, column);
        picker.load(false);
        picker.setVisible(true);
        picker.searchTimer.stop();
        // A load still running delivers to a closed dialog: its generation no longer matches
        picker.generation++;
        return picker.result;
    }

    private void pickSelected() {
        int selected = table.getSelectedRow();
        if (selected < 0) {
            return;
        }
        result = new ResultSetValueDialogs.Picked(pairs.get(selected).getValue());
        dispose();
    }

    /**
     * Reads the values of the search text: the first page, or the next one after those shown.
     */
    private void load(boolean more) {
        int loadGeneration = ++generation;
        String search = searchField.getText().strip();
        int offset = more ? pairs.size() : 0;
        statusLabel.setText(ValueEditMessages.valueedit_ref_loading);
        moreButton.setEnabled(false);
        new AbstractJob("Read referenced values") { //$NON-NLS-1$
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                List<DBDLabelValuePair> read = null;
                String source = null;
                Throwable error = null;
                try {
                    Reader reader = new Reader(monitor);
                    source = reader.source;
                    read = reader.read(search, offset);
                } catch (Throwable e) {
                    error = e;
                }
                List<DBDLabelValuePair> values = read;
                String sourceText = source;
                Throwable failure = error;
                SwingUtilities.invokeLater(() -> {
                    if (loadGeneration == generation) {
                        show(values, sourceText, failure, more);
                    }
                });
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    private void show(@Nullable List<DBDLabelValuePair> values, @Nullable String source, @Nullable Throwable error, boolean more) {
        if (source != null) {
            sourceLabel.setText(source);
        }
        if (error != null || values == null) {
            statusLabel.setText(error instanceof NoDictionaryException ? ValueEditMessages.valueedit_ref_no_dictionary
                : NLS.bind(ValueEditMessages.valueedit_ref_error, error == null ? "" : ErrorText.describeLine(error))); //$NON-NLS-1$
            statusLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.status.error"); //$NON-NLS-1$
            return;
        }
        statusLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Chromite.secondaryForeground"); //$NON-NLS-1$
        if (!more) {
            pairs.clear();
        }
        pairs.addAll(values);
        pairsModel.fireTableDataChanged();
        boolean hasMore = values.size() >= pageSize;
        moreButton.setEnabled(hasMore);
        statusLabel.setText(NLS.bind(hasMore ? ValueEditMessages.valueedit_ref_count_more : ValueEditMessages.valueedit_ref_count, pairs.size()));
        if (!more && current != null) {
            for (int i = 0; i < pairs.size(); i++) {
                if (isSameKey(current, pairs.get(i).getValue())) {
                    table.changeSelection(i, 0, false, false);
                    break;
                }
            }
        }
    }

    /**
     * Key values of the cell and of the referenced column may come as different number classes (Integer, Long):
     * equal values or equal text.
     */
    private static boolean isSameKey(@NotNull Object cellValue, @Nullable Object keyValue) {
        return Objects.equals(cellValue, keyValue) || (keyValue != null && cellValue.toString().equals(keyValue.toString()));
    }

    private static final class NoDictionaryException extends DBException {
        NoDictionaryException() {
            super("The referenced table can't list its key values"); //$NON-NLS-1$
        }
    }

    /**
     * Resolves the foreign key and reads values in the job thread.
     */
    private final class Reader {
        private final DBRProgressMonitor monitor;
        private final DBSEntityAssociation association;
        private final DBSEntityAttribute referencedColumn;
        private final DBSDictionary dictionary;
        @NotNull
        final String source;

        Reader(@NotNull DBRProgressMonitor monitor) throws DBException {
            this.monitor = monitor;
            DBSEntityAttribute tableColumn = binding.getEntityAttribute();
            DBSEntityReferrer referrer = DBStructUtils.getEnumerableConstraint(monitor, binding);
            if (tableColumn == null || !(referrer instanceof DBSEntityAssociation fk) || fk.getReferencedConstraint() == null
                || !(fk.getReferencedConstraint().getParentObject() instanceof DBSDictionary refDictionary)) {
                throw new NoDictionaryException();
            }
            DBSEntityAttribute refColumn = DBUtils.getReferenceAttribute(monitor, fk, tableColumn, false);
            if (refColumn == null) {
                throw new NoDictionaryException();
            }
            this.association = fk;
            this.referencedColumn = refColumn;
            this.dictionary = refDictionary;
            DBSEntity refTable = fk.getReferencedConstraint().getParentObject();
            this.source = NLS.bind(ValueEditMessages.valueedit_ref_source, binding.getName(),
                refTable.getName() + "." + refColumn.getName() + " (" + fk.getName() + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }

        @NotNull
        List<DBDLabelValuePair> read(@NotNull String search, int offset) throws Exception {
            try (DBSDictionaryAccessor accessor = dictionary.getDictionaryAccessor(monitor, referencedColumn, getRestColumns(), true, false)) {
                return search.isEmpty()
                    ? accessor.getValues(offset, pageSize)
                    : accessor.getSimilarValues(search, true, true, offset, pageSize);
            }
        }

        /**
         * The other columns of a multi-column foreign key, with the row's values, as conditions on the referenced
         * keys (SWT {@code ReferenceValueEditor}); columns not in the result or NULL in the row are left out.
         */
        @Nullable
        private List<DBDAttributeValue> getRestColumns() throws DBException {
            List<? extends DBSEntityAttributeRef> references = ((DBSEntityReferrer) association).getAttributeReferences(monitor);
            if (references == null || references.size() <= 1) {
                return null;
            }
            List<DBDAttributeValue> rest = new ArrayList<>();
            DBSEntityAttribute tableColumn = binding.getEntityAttribute();
            DBDAttributeBinding[] bindings = model.getAttributes();
            for (DBSEntityAttributeRef reference : references) {
                DBSEntityAttribute attribute = reference.getAttribute();
                if (attribute == null || attribute == tableColumn) {
                    continue;
                }
                for (int i = 0; i < bindings.length; i++) {
                    if (i != column && bindings[i].getEntityAttribute() == attribute && !DBUtils.isNullValue(rowValues[i])) {
                        DBSEntityAttribute referenced = DBUtils.getReferenceAttribute(monitor, association, attribute, false);
                        if (referenced != null) {
                            rest.add(new DBDAttributeValue(referenced, rowValues[i]));
                        }
                        break;
                    }
                }
            }
            return rest.isEmpty() ? null : rest;
        }
    }

    private final class PairsModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return pairs.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? ValueEditMessages.valueedit_ref_col_value : ValueEditMessages.valueedit_ref_col_description;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            DBDLabelValuePair pair = pairs.get(rowIndex);
            String value = DBUtils.isNullValue(pair.getValue()) ? ResultSetGrid.NULL_VALUE.toString()
                : binding.getValueHandler().getValueDisplayString(binding, pair.getValue(), DBDDisplayFormat.UI);
            if (columnIndex == 0) {
                return value;
            }
            // The accessor gives the key itself as the label when the table has no description column
            String label = CommonUtils.notEmpty(pair.getLabel());
            return label.equals(value) ? "" : label; //$NON-NLS-1$
        }
    }
}
