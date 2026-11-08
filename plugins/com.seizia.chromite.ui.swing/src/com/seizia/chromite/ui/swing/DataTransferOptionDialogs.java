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
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.preferences.DBPPropertyDescriptor;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import com.seizia.chromite.model.struct.DBSAttributeBase;
import com.seizia.chromite.model.struct.DBSDataContainer;
import com.seizia.chromite.runtime.DBInterruptedException;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.tools.transfer.DTConstants;
import com.seizia.chromite.tools.transfer.database.DatabaseMappingAttribute;
import com.seizia.chromite.tools.transfer.database.MappingNameCase;
import com.seizia.chromite.tools.transfer.database.MappingReplaceMechanism;
import com.seizia.chromite.tools.transfer.internal.DTActivator;
import com.seizia.chromite.tools.transfer.registry.DataTransferAttributeTransformerDescriptor;
import com.seizia.chromite.tools.transfer.registry.DataTransferRegistry;
import com.seizia.chromite.tools.transfer.stream.StreamMappingAttribute;
import com.seizia.chromite.tools.transfer.stream.StreamMappingContainer;
import com.seizia.chromite.tools.transfer.stream.StreamMappingType;
import com.seizia.chromite.ui.swing.internal.CoreMessages;
import com.seizia.chromite.ui.swing.internal.SettingsMessages;
import com.seizia.chromite.ui.swing.internal.TransferOptionsMessages;
import com.seizia.chromite.utils.PrefUtils;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 데이터 전송 고급 옵션의 작은 대화상자들: 테이블 복사의 매핑 규칙과 열 값 바꾸기, 파일 내보내기의 열 고르기.
 *
 * 설계:
 * <ul>
 *     <li><b>매핑 규칙</b>(SWT {@code MappingRulesDialog}): 새 테이블·열 이름의 대소문자와 공백 처리, 새 열의 최대 형식 길이. 모델은 이 값을
 *     대상 연결의 설정 저장소, 없으면 데이터 전송 번들 저장소에서 읽는다({@code DatabaseTransferUtils.getTransformedName}). 그래서 대상 연결에
 *     세 키를 모두 쓰고(연결 설정의 Data Transfer 페이지를 켠 것과 같음), 고르면 전역 값에도 쓴다. 전역에만 쓰면 연결이 자기 값을 가질 때
 *     적용되지 않기 때문이다. 이름은 모델이 매핑을 만들 때 다시 계산하므로 호출한 쪽이 매핑을 다시 만든다.</li>
 *     <li><b>열 값</b>(SWT {@code AttributeTransformerSettingsDialog}): 모델이 등록한 값 변환기(NULL, 상수, 식)를 열 매핑
 *     ({@link DatabaseMappingAttribute#setTransformer})에 둔다. 작업으로 저장하면 그 매핑의 설정으로 함께 저장된다.
 *     변환기 이름은 {@code plugin.xml}에 영어로만 있어 알려진 id는 이 번들의 문구를 쓴다.</li>
 *     <li><b>열 고르기</b>(SWT {@code ConfigureColumnsDialog}): 원본마다 {@link StreamMappingContainer}를 만들어 열마다 내보내기/건너뛰기를 둔다.
 *     모델은 매핑이 완전할 때(모든 열이 정해지고 하나 이상 내보냄)만 열을 거른다. 고친 값은 복사본에서 바꾸고 OK에서만 반영한다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/swing-data-transfer.md "고급 옵션")
 */
final class DataTransferOptionDialogs {

    private static final String TRANSFORMER_NULL = "null"; //$NON-NLS-1$
    private static final String TRANSFORMER_CONSTANT = "constant"; //$NON-NLS-1$
    private static final String TRANSFORMER_EXPRESSION = "expression"; //$NON-NLS-1$

    private DataTransferOptionDialogs() {
    }

    //////////////////////////////////////////////////////////
    // Mapping rules

    /**
     * Edits the mapping rules of new tables and columns in a destination connection.
     *
     * @return whether they were saved (the caller makes the mappings again)
     */
    static boolean openMappingRules(@NotNull Component owner, @NotNull DBPDataSourceContainer destination) {
        DBPPreferenceStore connectionStore = destination.getPreferenceStore();
        DBPPreferenceStore globalStore = DTActivator.getDefault().getPreferences();
        JComboBox<String> nameCaseCombo = new JComboBox<>();
        JComboBox<String> replaceCombo = new JComboBox<>();
        // Combo index = the model's selection id (as DataTransferOptionsPage)
        for (int i = 0; i < MappingNameCase.values().length; i++) {
            nameCaseCombo.addItem(MappingNameCase.getCaseBySelectionId(i).getName());
        }
        for (int i = 0; i < MappingReplaceMechanism.values().length; i++) {
            replaceCombo.addItem(MappingReplaceMechanism.getCaseBySelectionId(i).getName());
        }
        JSpinner maxLengthSpinner = new JSpinner(new SpinnerNumberModel(1, 1, Integer.MAX_VALUE, 1000));
        maxLengthSpinner.setEditor(new JSpinner.NumberEditor(maxLengthSpinner, "#")); //$NON-NLS-1$
        nameCaseCombo.setSelectedIndex(Math.clamp(readRule(connectionStore, globalStore, DTConstants.PREF_NAME_CASE_MAPPING), 0, nameCaseCombo.getItemCount() - 1));
        replaceCombo.setSelectedIndex(Math.clamp(readRule(connectionStore, globalStore, DTConstants.PREF_REPLACE_MAPPING), 0, replaceCombo.getItemCount() - 1));
        maxLengthSpinner.setValue(Math.max(1, readRule(connectionStore, globalStore, DTConstants.PREF_MAX_TYPE_LENGTH)));
        JCheckBox globalCheck = new JCheckBox(TransferOptionsMessages.rules_global);

        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;
        row = DataTransferObjects.addRow(form, row, null, DataTransferObjects.createNote(TransferOptionsMessages.rules_note), null);
        row = DataTransferObjects.addRow(form, row, SettingsMessages.transfer_name_case, nameCaseCombo, null);
        row = DataTransferObjects.addRow(form, row, SettingsMessages.transfer_replace, replaceCombo, null);
        row = DataTransferObjects.addRow(form, row, SettingsMessages.transfer_max_length, maxLengthSpinner, SettingsMessages.transfer_max_length_hint);
        row = DataTransferObjects.addRow(form, row, null, new JLabel(NLS.bind(TransferOptionsMessages.rules_scope, destination.getName())), null);
        DataTransferObjects.addRow(form, row, null, globalCheck, null);

        boolean[] saved = new boolean[1];
        OptionDialog dialog = new OptionDialog(owner, TransferOptionsMessages.rules_title, form, () -> {
            int nameCase = nameCaseCombo.getSelectedIndex();
            int replace = replaceCombo.getSelectedIndex();
            int maxLength = ((Number) maxLengthSpinner.getValue()).intValue();
            writeRules(connectionStore, nameCase, replace, maxLength);
            if (globalCheck.isSelected()) {
                writeRules(globalStore, nameCase, replace, maxLength);
            }
            saved[0] = true;
            return null;
        });
        dialog.setSize(560, 330);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        return saved[0];
    }

    /**
     * The connection's own value, else the global one: the model falls back to the data transfer bundle's store, not to
     * the parent of a connection's store.
     */
    private static int readRule(@NotNull DBPPreferenceStore connectionStore, @NotNull DBPPreferenceStore globalStore, @NotNull String key) {
        return connectionStore.contains(key) ? connectionStore.getInt(key) : globalStore.getInt(key);
    }

    private static void writeRules(@NotNull DBPPreferenceStore store, int nameCase, int replace, int maxLength) {
        store.setValue(DTConstants.PREF_NAME_CASE_MAPPING, nameCase);
        store.setValue(DTConstants.PREF_REPLACE_MAPPING, replace);
        store.setValue(DTConstants.PREF_MAX_TYPE_LENGTH, maxLength);
        PrefUtils.savePreferenceStore(store);
    }

    //////////////////////////////////////////////////////////
    // Column value

    /**
     * Chooses what a destination column receives: the source value, NULL, a constant or an expression.
     *
     * @return whether the column's value changed
     */
    static boolean openColumnValue(@NotNull Component owner, @NotNull DatabaseMappingAttribute attribute) {
        List<DataTransferAttributeTransformerDescriptor> transformers = DataTransferRegistry.getInstance().getAttributeTransformers();
        DataTransferAttributeTransformerDescriptor current = attribute.getTransformer();
        Map<String, Object> currentProperties = attribute.getTransformerProperties();
        ButtonGroup group = new ButtonGroup();
        JRadioButton noneButton = new JRadioButton(TransferOptionsMessages.transform_none, current == null);
        group.add(noneButton);
        Map<DataTransferAttributeTransformerDescriptor, JRadioButton> buttons = new LinkedHashMap<>();
        Map<DBPPropertyDescriptor, JTextField> fields = new LinkedHashMap<>();
        Map<DBPPropertyDescriptor, DataTransferAttributeTransformerDescriptor> fieldOwners = new HashMap<>();

        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;
        row = DataTransferObjects.addRow(form, row, null, noneButton, null);
        for (DataTransferAttributeTransformerDescriptor transformer : transformers) {
            JRadioButton button = new JRadioButton(getTransformerLabel(transformer), transformer == current);
            button.setToolTipText(transformer.getDescription());
            group.add(button);
            buttons.put(transformer, button);
            row = DataTransferObjects.addRow(form, row, null, button, null);
            for (DBPPropertyDescriptor property : transformer.getProperties()) {
                JTextField field = new JTextField(30);
                if (transformer == current) {
                    field.setText(CommonUtils.toString(currentProperties.get(property.getId())));
                }
                fields.put(property, field);
                fieldOwners.put(property, transformer);
                row = DataTransferObjects.addRow(form, row, "    " + getPropertyLabel(transformer, property), field, null); //$NON-NLS-1$
            }
            if (TRANSFORMER_EXPRESSION.equals(transformer.getId())) {
                row = DataTransferObjects.addRow(form, row, null, DataTransferObjects.createNote(TransferOptionsMessages.transform_expression_note), null);
            }
        }
        Runnable enablement = () -> fields.forEach((property, field) -> field.setEnabled(buttons.get(fieldOwners.get(property)).isSelected()));
        noneButton.addActionListener(e -> enablement.run());
        buttons.values().forEach(button -> button.addActionListener(e -> enablement.run()));
        enablement.run();

        String columnName = attribute.getSource() == null ? attribute.getTargetName() : attribute.getSource().getName();
        boolean[] changed = new boolean[1];
        OptionDialog dialog = new OptionDialog(owner, NLS.bind(TransferOptionsMessages.transform_title, columnName), form, () -> {
            DataTransferAttributeTransformerDescriptor selected = null;
            for (Map.Entry<DataTransferAttributeTransformerDescriptor, JRadioButton> entry : buttons.entrySet()) {
                if (entry.getValue().isSelected()) {
                    selected = entry.getKey();
                }
            }
            Map<String, Object> properties = new LinkedHashMap<>();
            if (selected != null) {
                for (DBPPropertyDescriptor property : selected.getProperties()) {
                    String text = fields.get(property).getText();
                    if (property.isRequired() && text.isBlank()) {
                        return NLS.bind(TransferOptionsMessages.transform_error_empty, getTransformerLabel(selected));
                    }
                    properties.put(property.getId(), text);
                }
            }
            attribute.setTransformer(selected);
            attribute.setTransformerProperties(properties);
            changed[0] = true;
            return null;
        });
        dialog.pack();
        dialog.setSize(Math.max(dialog.getWidth(), 520), dialog.getHeight());
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        return changed[0];
    }

    /**
     * What a destination column receives, in words, or an empty string for the source value.
     */
    @NotNull
    static String describeColumnValue(@NotNull DatabaseMappingAttribute attribute) {
        DataTransferAttributeTransformerDescriptor transformer = attribute.getTransformer();
        if (transformer == null) {
            return ""; //$NON-NLS-1$
        }
        StringBuilder text = new StringBuilder(getTransformerLabel(transformer));
        Map<String, Object> properties = attribute.getTransformerProperties();
        for (DBPPropertyDescriptor property : transformer.getProperties()) {
            text.append(text.length() == getTransformerLabel(transformer).length() ? ": " : ", ") //$NON-NLS-1$ //$NON-NLS-2$
                .append(CommonUtils.toString(properties.get(property.getId())));
        }
        return text.toString();
    }

    @NotNull
    private static String getTransformerLabel(@NotNull DataTransferAttributeTransformerDescriptor transformer) {
        return switch (transformer.getId()) {
            case TRANSFORMER_NULL -> TransferOptionsMessages.transform_null;
            case TRANSFORMER_CONSTANT -> TransferOptionsMessages.transform_constant;
            case TRANSFORMER_EXPRESSION -> TransferOptionsMessages.transform_expression;
            default -> transformer.getName();
        };
    }

    @NotNull
    private static String getPropertyLabel(@NotNull DataTransferAttributeTransformerDescriptor transformer, @NotNull DBPPropertyDescriptor property) {
        // The known transformers have one property: the radio button names it
        return switch (transformer.getId()) {
            case TRANSFORMER_CONSTANT, TRANSFORMER_EXPRESSION -> getTransformerLabel(transformer);
            default -> property.getDisplayName();
        };
    }

    //////////////////////////////////////////////////////////
    // Export columns

    /**
     * Chooses the exported columns of the sources. The chosen columns of each source are kept in {@code mappings}
     * (all columns exported: no mapping).
     */
    static void openExportColumns(
        @NotNull Component owner,
        @NotNull List<DBSDataContainer> sources,
        @NotNull Map<DBSDataContainer, StreamMappingContainer> mappings
    ) {
        if (sources.isEmpty()) {
            return;
        }
        // Edited copies (a source whose columns can't be read has none); the mappings change on OK only
        Map<DBSDataContainer, StreamMappingContainer> edited = new LinkedHashMap<>();
        JComboBox<DBSDataContainer> tableCombo = new JComboBox<>(sources.toArray(new DBSDataContainer[0]));
        tableCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof DBSDataContainer container) {
                    setText(DBUtils.getObjectFullName(container, DBPEvaluationContext.UI));
                }
                return this;
            }
        });
        ColumnsModel model = new ColumnsModel();
        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setMaxWidth(90);
        Runnable showTable = () -> {
            DBSDataContainer source = (DBSDataContainer) tableCombo.getSelectedItem();
            model.show(source == null ? null : edited.computeIfAbsent(source, s -> readMapping(s, mappings.get(s))));
        };
        tableCombo.addActionListener(e -> showTable.run());
        JButton allButton = new JButton(TransferOptionsMessages.columns_select_all);
        allButton.addActionListener(e -> model.setAll(StreamMappingType.export));
        JButton noneButton = new JButton(TransferOptionsMessages.columns_select_none);
        noneButton.addActionListener(e -> model.setAll(StreamMappingType.skip));

        JPanel top = new JPanel(new GridBagLayout());
        DataTransferObjects.addRow(top, 0, TransferOptionsMessages.columns_table, tableCombo, null);
        JPanel selectButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        selectButtons.add(allButton);
        selectButtons.add(noneButton);
        JPanel form = new JPanel(new BorderLayout(0, 8));
        form.add(top, BorderLayout.NORTH);
        form.add(new JScrollPane(table), BorderLayout.CENTER);
        form.add(selectButtons, BorderLayout.SOUTH);
        showTable.run();

        OptionDialog dialog = new OptionDialog(owner, TransferOptionsMessages.columns_title, form, () -> {
            for (Map.Entry<DBSDataContainer, StreamMappingContainer> entry : edited.entrySet()) {
                List<StreamMappingAttribute> attributes = entry.getValue().getAttributes(new VoidProgressMonitor());
                if (attributes.stream().noneMatch(attribute -> attribute.getMappingType() == StreamMappingType.export)) {
                    tableCombo.setSelectedItem(entry.getKey());
                    return NLS.bind(TransferOptionsMessages.columns_error_none, DBUtils.getObjectFullName(entry.getKey(), DBPEvaluationContext.UI));
                }
            }
            for (Map.Entry<DBSDataContainer, StreamMappingContainer> entry : edited.entrySet()) {
                StreamMappingContainer mapping = entry.getValue();
                boolean all = mapping.getAttributes(new VoidProgressMonitor()).stream()
                    .allMatch(attribute -> attribute.getMappingType() == StreamMappingType.export);
                if (all) {
                    mappings.remove(entry.getKey());
                } else {
                    mappings.put(entry.getKey(), mapping);
                }
            }
            return null;
        });
        dialog.setSize(620, 480);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    /**
     * A copy of the source's column choice, or a new one with every column exported (null if the columns can't be read).
     */
    @Nullable
    private static StreamMappingContainer readMapping(@NotNull DBSDataContainer source, @Nullable StreamMappingContainer current) {
        if (current != null) {
            return new StreamMappingContainer(current);
        }
        StreamMappingContainer mapping = new StreamMappingContainer(source);
        try {
            List<StreamMappingAttribute> attributes = ProgressDialog.runTask(
                NLS.bind(TransferOptionsMessages.columns_progress, DBUtils.getObjectFullName(source, DBPEvaluationContext.UI)),
                monitor -> mapping.getAttributes(monitor));
            if (attributes.isEmpty()) {
                // The model already showed why the columns could not be read
                return null;
            }
            for (StreamMappingAttribute attribute : attributes) {
                attribute.setMappingType(StreamMappingType.export);
            }
        } catch (DBInterruptedException e) {
            return null;
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(TransferOptionsMessages.columns_title,
                NLS.bind(TransferOptionsMessages.columns_progress, source.getName()), e);
            return null;
        }
        return mapping;
    }

    /**
     * The exported columns of a source in words ("a, b (2 of 5)"), or null when all are exported.
     */
    @Nullable
    static String describeExportColumns(@Nullable StreamMappingContainer mapping) {
        if (mapping == null) {
            return null;
        }
        List<StreamMappingAttribute> attributes = mapping.getAttributes(new VoidProgressMonitor());
        List<String> names = new ArrayList<>();
        for (StreamMappingAttribute attribute : attributes) {
            if (attribute.getMappingType() == StreamMappingType.export) {
                names.add(attribute.getName());
            }
        }
        return String.join(", ", names) + " (" + names.size() + "/" + attributes.size() + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    /**
     * Columns of one source with their export check box.
     */
    private static final class ColumnsModel extends AbstractTableModel {
        private final String[] columns = {TransferOptionsMessages.columns_column_export, TransferOptionsMessages.columns_column_name,
            TransferOptionsMessages.columns_column_type};
        private List<StreamMappingAttribute> attributes = List.of();

        void show(@Nullable StreamMappingContainer mapping) {
            attributes = mapping == null ? List.of() : mapping.getAttributes(new VoidProgressMonitor());
            fireTableDataChanged();
        }

        void setAll(@NotNull StreamMappingType type) {
            for (StreamMappingAttribute attribute : attributes) {
                attribute.setMappingType(type);
            }
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return attributes.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 0;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            StreamMappingAttribute attribute = attributes.get(rowIndex);
            DBSAttributeBase source = attribute.getAttribute();
            return switch (columnIndex) {
                case 0 -> attribute.getMappingType() == StreamMappingType.export;
                case 1 -> attribute.getName();
                default -> CommonUtils.notEmpty(source.getFullTypeName());
            };
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            if (columnIndex == 0) {
                attributes.get(rowIndex).setMappingType(Boolean.TRUE.equals(value) ? StreamMappingType.export : StreamMappingType.skip);
                fireTableRowsUpdated(rowIndex, rowIndex);
            }
        }
    }

    //////////////////////////////////////////////////////////
    // Dialog

    /**
     * A modal dialog with OK and Cancel; OK runs the check-and-save action, which returns an error to show or null to close.
     */
    private static final class OptionDialog extends JDialog {
        OptionDialog(@NotNull Component owner, @NotNull String title, @NotNull JComponent form, @NotNull Supplier<String> okAction) {
            super(owner instanceof Window window ? window : SwingUtilities.getWindowAncestor(owner), title, ModalityType.APPLICATION_MODAL);
            setIconImages(ChromiteIcons.getWindowIcons());
            JLabel error = new JLabel(" "); //$NON-NLS-1$
            error.putClientProperty(FlatClientProperties.STYLE, "foreground: $Component.error.focusedBorderColor"); //$NON-NLS-1$
            JButton okButton = new JButton(CoreMessages.common_ok);
            okButton.addActionListener(e -> {
                String problem = okAction.get();
                if (problem == null) {
                    dispose();
                } else {
                    error.setText(problem);
                }
            });
            JButton cancelButton = new JButton(CoreMessages.common_cancel);
            cancelButton.addActionListener(e -> dispose());
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            buttons.add(okButton);
            buttons.add(cancelButton);
            JPanel bottom = new JPanel(new BorderLayout(0, 6));
            bottom.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
            bottom.add(error, BorderLayout.NORTH);
            bottom.add(buttons, BorderLayout.SOUTH);
            JPanel content = new JPanel(new BorderLayout());
            content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
            content.add(form, BorderLayout.CENTER);
            content.add(bottom, BorderLayout.SOUTH);
            setContentPane(content);
            getRootPane().setDefaultButton(okButton);
            getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        }
    }
}
