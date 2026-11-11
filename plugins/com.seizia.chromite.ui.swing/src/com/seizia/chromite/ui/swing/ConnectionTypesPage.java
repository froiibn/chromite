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

import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPDataSourcePermission;
import com.seizia.chromite.model.DBPEvent;
import com.seizia.chromite.model.app.DBPDataSourceRegistry;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.connection.DBPConnectionConfiguration;
import com.seizia.chromite.model.connection.DBPConnectionType;
import com.seizia.chromite.registry.DataSourceProviderRegistry;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.swing.internal.LifecycleMessages;
import com.seizia.chromite.ui.swing.internal.SettingsMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.SecurityUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tools &gt; Options &gt; Connection Types: the connection types (Development, Test, Production and the user's own) to view,
 * change, add and remove.
 *
 * 설계:
 * <ul>
 *     <li>SWT 설정 페이지({@code PrefPageConnectionTypes})처럼 목록과 선택한 유형의 편집이다. 고친 내용은 이 페이지 안의 복사본에만 두고
 *     페이지의 [저장]에서 {@link #apply}가 원본에 옮긴 뒤 {@link DataSourceProviderRegistry#saveConnectionTypes}로 저장한다(SWT {@code performOk}와 같은 순서).
 *     표 편집(유형의 칸, 만들기, 지우기)은 값이라(저장 표준) 체크 상자도 바로 저장하지 않는다. 바뀐 수는 만들거나 바꾸거나 지운 유형의 수다.</li>
 *     <li>보이는 항목은 Swing 제품이 읽는 것만이다: 색({@link ConnectionColors}, 라이트·다크 테마 각각), 자동 커밋 기본값
 *     ({@code DataSourceDescriptor.isDefaultAutoCommit}), 실행 확인({@code SQLQueryExecuteJob}), 데이터 변경 확인({@link ResultSetGrid}),
 *     유휴 트랜잭션 알림과 기간({@code IdleTransactionMonitor}), 유휴 연결 닫기와 기간({@code DataSourceMonitorJob}), 수정 제한
 *     ({@code hasModifyPermission}: 데이터 편집, 구조 편집, 스크립트 실행, 데이터 가져오기), 스마트 커밋과 그 뒤 자동 커밋으로 돌아가기
 *     ({@link SmartCommit}, 2026-11-08부터 쿼리 창이 씀), 조회만 한 트랜잭션 자동으로 끝내기({@code IdleTransactionMonitor.endReadOnlyAfterWork}).</li>
 *     <li>유휴 트랜잭션은 "알림"이다(롤백 동의가 아님, docs/chromite/idle-transactions.md). 묻지 않는 자동 롤백은 연결마다 사용자가 켜고,
 *     유형에는 두지 않는다. 조회만 한 트랜잭션 자동으로 끝내기(2026-11-09, 앱 변경 #31)는 사용자 결정대로 유형에 두고 기본은 꺼짐이다.
 *     버리는 것이 없는 롤백이라 자동 롤백과 달리 경고 문구는 두지 않고 도구 설명에 언제 하고 언제 하지 않는지를 적는다.</li>
 *     <li>지우기: 미리 정의된 유형(개발·테스트·운영)은 지울 수 없다. 지운 유형을 쓰던 연결은 새 연결의 기본 유형으로 바뀌고 저장된다(SWT는
 *     {@code DEFAULT_TYPE}). 지우기 전에 그 유형을 쓰는 연결 수를 보여 주고 묻는다.</li>
 *     <li>트랜잭션 안전: 유형의 자동 커밋 기본값을 바꾸거나 유형을 지우면 연결의 실제 커밋 방식이 바뀔 수 있다. 그래서 저장 전에 연결된 연결마다
 *     실제 값을 기억하고, 저장 뒤 바뀐 연결마다 {@link AutoCommitApplier}(연결 대화상자의 Auto-commit과 같은 경로)로 열린 세션에 적용한다.
 *     자동 커밋으로 바뀌는 세션에 커밋하지 않은 변경이 있으면 커밋 / 롤백 / 취소를 묻는다. 질문이 겹치지 않게 한 연결씩 차례로 한다.</li>
 *     <li>저장 뒤 바뀐 유형을 쓰는 연결에 갱신 이벤트를 보내 개체 탐색기·등록된 서버의 색과 쿼리 창 상태 줄이 다시 그려지게 한다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-07 최초 작성 (docs/chromite/swing-options.md "연결 유형")
 * - 2026-11-08 연결 유형 삭제 확인은 삭제 / 취소 단추(취소가 기본) (docs/chromite/swing-frontend.md "실행 확인의 기본 단추")
 * - 2026-11-08 스마트 커밋·자동 커밋으로 돌아가기 칸: 쿼리 창이 스마트 커밋을 쓰게 되어 효과가 생김 (docs/chromite/connection-lifecycle.md "스마트 커밋")
 * - 2026-11-09 조회만 한 트랜잭션은 문장이 끝나면 자동으로 끝내기 칸 (docs/chromite/idle-transactions.md "조회만 한 트랜잭션 끝내기")
 * - 2026-11-12 Options 저장 표준: 바뀐 수, [저장] 뒤 새 사본, [되돌리기](앱 변경 #59, docs/chromite/swing-options.md "옵션 화면 모양과 저장 (#59)")
 */
final class ConnectionTypesPage extends JPanel {

    private static final Log log = Log.getLog(ConnectionTypesPage.class);
    private static final String NO_COLOR = "255,255,255"; //$NON-NLS-1$
    private static final int MAX_SECONDS = 7 * 24 * 3600;

    private final DataSourceProviderRegistry registry = DataSourceProviderRegistry.getInstance();
    // The edited copies in list order, and the registry's original of each (none for a new type)
    private final List<DBPConnectionType> types = new ArrayList<>();
    private final Map<DBPConnectionType, DBPConnectionType> originals = new IdentityHashMap<>();
    private final TypesModel model = new TypesModel();
    private final JTable table = new JTable(model);
    private final JButton deleteButton = new JButton(SettingsMessages.types_delete);
    private final JTextField nameField = new JTextField(20);
    private final JTextField descriptionField = new JTextField(30);
    private final JButton colorButton = new JButton();
    private final JButton colorNoneButton = new JButton(SettingsMessages.types_color_none);
    private final JButton darkColorButton = new JButton();
    private final JButton darkColorNoneButton = new JButton(SettingsMessages.types_color_none);
    private final JCheckBox autoCommitBox = new JCheckBox(SettingsMessages.types_auto_commit);
    private final JCheckBox smartCommitBox = new JCheckBox(LifecycleMessages.smart_commit);
    private final JCheckBox smartCommitRecoverBox = new JCheckBox(LifecycleMessages.smart_commit_recover);
    private final JCheckBox confirmExecuteBox = new JCheckBox(SettingsMessages.types_confirm_execute);
    private final JCheckBox confirmDataChangeBox = new JCheckBox(SettingsMessages.types_confirm_data_change);
    private final JCheckBox idleTxnBox = new JCheckBox(SettingsMessages.types_idle_transactions);
    private final JSpinner idleTxnSpinner = new JSpinner(new SpinnerNumberModel(1, 1, MAX_SECONDS / 60, 1));
    private final JCheckBox endReadOnlyBox = new JCheckBox(SettingsMessages.types_end_read_only);
    private final JCheckBox closeIdleBox = new JCheckBox(SettingsMessages.types_close_idle);
    private final JSpinner closeIdleSpinner = new JSpinner(new SpinnerNumberModel(1, 1, MAX_SECONDS, 60));
    private final Map<DBPDataSourcePermission, JCheckBox> restrictionBoxes = new EnumMap<>(DBPDataSourcePermission.class);
    private boolean updating;

    ConnectionTypesPage() {
        super(new BorderLayout(12, 0));
        setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
        loadTypes();

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setShowVerticalLines(false);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(110);
        table.getColumnModel().getColumn(1).setPreferredWidth(150);
        table.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
                setIcon(new ConnectionSettingsPanel.ColorSwatch(ConnectionColors.getTypeColor(types.get(t.convertRowIndexToModel(row)))));
                setIconTextGap(6);
                return this;
            }
        });
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showSelected();
            }
        });
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setPreferredSize(new Dimension(270, 240));
        JButton newButton = new JButton(SettingsMessages.types_new);
        newButton.addActionListener(e -> addType());
        deleteButton.addActionListener(e -> deleteType());
        JPanel listButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        listButtons.add(newButton);
        listButtons.add(deleteButton);
        JPanel left = new JPanel(new BorderLayout(0, 6));
        left.add(tableScroll, BorderLayout.CENTER);
        left.add(listButtons, BorderLayout.SOUTH);

        DocumentListener textListener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                storeSelected();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                storeSelected();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
            }
        };
        nameField.getDocument().addDocumentListener(textListener);
        descriptionField.getDocument().addDocumentListener(textListener);
        colorButton.addActionListener(e -> chooseColor(false));
        darkColorButton.addActionListener(e -> chooseColor(true));
        colorNoneButton.addActionListener(e -> setColor(false, NO_COLOR));
        darkColorNoneButton.addActionListener(e -> setColor(true, NO_COLOR));
        autoCommitBox.setToolTipText(SettingsMessages.types_auto_commit_tip);
        smartCommitBox.setToolTipText(LifecycleMessages.smart_commit_tip);
        smartCommitRecoverBox.setToolTipText(LifecycleMessages.smart_commit_recover_tip);
        confirmExecuteBox.setToolTipText(SettingsMessages.types_confirm_execute_tip);
        confirmDataChangeBox.setToolTipText(SettingsMessages.types_confirm_data_change_tip);
        idleTxnBox.setToolTipText(SettingsMessages.types_idle_transactions_tip);
        endReadOnlyBox.setToolTipText(SettingsMessages.types_end_read_only_tip);
        closeIdleBox.setToolTipText(SettingsMessages.types_close_idle_tip);
        for (JCheckBox box : List.of(autoCommitBox, smartCommitBox, smartCommitRecoverBox, confirmExecuteBox, confirmDataChangeBox, idleTxnBox,
            endReadOnlyBox, closeIdleBox)) {
            box.addActionListener(e -> storeSelected());
            // Fields of the selected type: saved with the page's Save, as the rest of the table
            box.putClientProperty(OptionsForm.VALUE_CHECK_BOX, true);
        }
        for (JSpinner spinner : List.of(idleTxnSpinner, closeIdleSpinner)) {
            // Without digit grouping, as in the connection dialog
            JSpinner.NumberEditor editor = new JSpinner.NumberEditor(spinner, "#"); //$NON-NLS-1$
            spinner.setEditor(editor);
            spinner.addChangeListener(e -> storeSelected());
        }

        JPanel form = new JPanel(new GridBagLayout());
        int row = OptionsDialog.addSection(form, 0, SettingsMessages.types_section_type);
        row = OptionsDialog.addRow(form, row, SettingsMessages.types_name, nameField, null);
        row = OptionsDialog.addRow(form, row, SettingsMessages.types_description, descriptionField, null);
        row = OptionsDialog.addRow(form, row, SettingsMessages.types_color, colorRow(colorButton, colorNoneButton), null);
        row = OptionsDialog.addRow(form, row, SettingsMessages.types_color_dark, colorRow(darkColorButton, darkColorNoneButton), null);
        row = OptionsDialog.addSection(form, row, SettingsMessages.types_section_transactions);
        row = OptionsDialog.addRow(form, row, null, autoCommitBox, null);
        row = OptionsDialog.addRow(form, row, null, smartCommitBox, null);
        row = OptionsDialog.addRow(form, row, null, smartCommitRecoverBox, null);
        row = OptionsDialog.addRow(form, row, null, confirmExecuteBox, null);
        row = OptionsDialog.addRow(form, row, null, confirmDataChangeBox, null);
        row = OptionsDialog.addRow(form, row, null, idleTxnBox, null);
        row = OptionsDialog.addRow(form, row, SettingsMessages.types_idle_transactions_period, idleTxnSpinner, SettingsMessages.types_minutes);
        row = OptionsDialog.addRow(form, row, null, endReadOnlyBox, null);
        row = OptionsDialog.addSection(form, row, SettingsMessages.types_section_connections);
        row = OptionsDialog.addRow(form, row, null, closeIdleBox, null);
        row = OptionsDialog.addRow(form, row, SettingsMessages.types_close_idle_period, closeIdleSpinner, SettingsMessages.types_seconds);
        row = OptionsDialog.addSection(form, row, SettingsMessages.types_section_restrictions);
        for (DBPDataSourcePermission permission : DBPDataSourcePermission.values()) {
            JCheckBox box = new JCheckBox(permission.getLabel());
            box.setToolTipText(permission.getDescription());
            box.addActionListener(e -> storeSelected());
            box.putClientProperty(OptionsForm.VALUE_CHECK_BOX, true);
            restrictionBoxes.put(permission, box);
            row = OptionsDialog.addRow(form, row, null, box, null);
        }
        JScrollPane formScroll = new JScrollPane(OptionsDialog.wrapPage(form),
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        formScroll.setBorder(BorderFactory.createEmptyBorder());

        add(left, BorderLayout.WEST);
        add(formScroll, BorderLayout.CENTER);
        if (!types.isEmpty()) {
            table.getSelectionModel().setSelectionInterval(0, 0);
        }
        showSelected();
    }

    @NotNull
    private static JComponent colorRow(@NotNull JButton chooseButton, @NotNull JButton noneButton) {
        // No outer gap: the buttons line up with the text fields of the other rows
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        panel.add(chooseButton);
        panel.add(Box.createHorizontalStrut(6));
        panel.add(noneButton);
        return panel;
    }

    @Nullable
    private DBPConnectionType getSelected() {
        int row = table.getSelectedRow();
        return row >= 0 && row < types.size() ? types.get(row) : null;
    }

    private void showSelected() {
        DBPConnectionType type = getSelected();
        updating = true;
        try {
            boolean editable = type != null;
            nameField.setText(type == null ? "" : type.getName());
            descriptionField.setText(type == null ? "" : CommonUtils.notEmpty(type.getDescription()));
            autoCommitBox.setSelected(type != null && type.isAutocommit());
            smartCommitBox.setSelected(type != null && type.isSmartCommit());
            smartCommitRecoverBox.setSelected(type != null && type.isSmartCommitRecover());
            confirmExecuteBox.setSelected(type != null && type.isConfirmExecute());
            confirmDataChangeBox.setSelected(type != null && type.isConfirmDataChange());
            idleTxnBox.setSelected(type != null && type.isAutoCloseTransactions());
            idleTxnSpinner.setValue(type == null ? 1 : Math.clamp(Math.round(type.getCloseIdleTransactionPeriod() / 60f), 1, MAX_SECONDS / 60));
            endReadOnlyBox.setSelected(type != null && type.isAutoEndReadOnlyTransactions());
            closeIdleBox.setSelected(type != null && type.isAutoCloseConnections());
            closeIdleSpinner.setValue(type == null ? 1 : Math.clamp(type.getCloseIdleConnectionPeriod(), 1, MAX_SECONDS));
            for (Map.Entry<DBPDataSourcePermission, JCheckBox> entry : restrictionBoxes.entrySet()) {
                entry.getValue().setSelected(type != null && !type.hasModifyPermission(entry.getKey()));
                entry.getValue().setEnabled(editable);
            }
            for (JComponent component : List.of(nameField, descriptionField, colorButton, colorNoneButton, darkColorButton,
                darkColorNoneButton, autoCommitBox, smartCommitBox, confirmExecuteBox, confirmDataChangeBox, idleTxnBox, endReadOnlyBox, closeIdleBox)) {
                component.setEnabled(editable);
            }
            deleteButton.setEnabled(type != null && !type.isPredefined());
            updateDependentFields();
            updateColorButtons();
        } finally {
            updating = false;
        }
    }

    /**
     * Copies the fields into the selected copy.
     */
    private void storeSelected() {
        DBPConnectionType type = getSelected();
        if (updating || type == null) {
            return;
        }
        type.setName(nameField.getText().trim());
        type.setDescription(CommonUtils.nullIfEmpty(descriptionField.getText().trim()));
        type.setAutocommit(autoCommitBox.isSelected());
        type.setSmartCommit(smartCommitBox.isSelected());
        type.setSmartCommitRecover(smartCommitRecoverBox.isSelected());
        type.setConfirmExecute(confirmExecuteBox.isSelected());
        type.setConfirmDataChange(confirmDataChangeBox.isSelected());
        type.setAutoCloseTransactions(idleTxnBox.isSelected());
        type.setCloseIdleTransactionPeriod((Integer) idleTxnSpinner.getValue() * 60);
        type.setAutoEndReadOnlyTransactions(endReadOnlyBox.isSelected());
        type.setAutoCloseConnections(closeIdleBox.isSelected());
        type.setCloseIdleConnectionPeriod((Integer) closeIdleSpinner.getValue());
        List<DBPDataSourcePermission> restrictions = new ArrayList<>();
        for (Map.Entry<DBPDataSourcePermission, JCheckBox> entry : restrictionBoxes.entrySet()) {
            if (entry.getValue().isSelected()) {
                restrictions.add(entry.getKey());
            }
        }
        type.setModifyPermissions(restrictions);
        updateDependentFields();
        int row = table.getSelectedRow();
        model.fireTableRowsUpdated(row, row);
    }

    /**
     * The periods only apply with their switch on, as the return to auto-commit only applies with smart commit.
     */
    private void updateDependentFields() {
        smartCommitRecoverBox.setEnabled(smartCommitBox.isEnabled() && smartCommitBox.isSelected());
        idleTxnSpinner.setEnabled(idleTxnBox.isEnabled() && idleTxnBox.isSelected());
        closeIdleSpinner.setEnabled(closeIdleBox.isEnabled() && closeIdleBox.isSelected());
    }

    private void updateColorButtons() {
        DBPConnectionType type = getSelected();
        colorButton.setIcon(new ConnectionSettingsPanel.ColorSwatch(type == null ? null : ConnectionColors.parse(type.getColorLight())));
        colorButton.setText(SettingsMessages.types_color_change);
        darkColorButton.setIcon(new ConnectionSettingsPanel.ColorSwatch(type == null ? null : ConnectionColors.parse(type.getColorDark())));
        darkColorButton.setText(SettingsMessages.types_color_change);
    }

    private void chooseColor(boolean dark) {
        DBPConnectionType type = getSelected();
        if (type == null) {
            return;
        }
        Color initial = ConnectionColors.parse(dark ? type.getColorDark() : type.getColorLight());
        Color chosen = JColorChooser.showDialog(this, dark ? SettingsMessages.types_color_dark : SettingsMessages.types_color, initial);
        if (chosen != null) {
            setColor(dark, ConnectionColors.format(chosen));
        }
    }

    private void setColor(boolean dark, @NotNull String rgb) {
        DBPConnectionType type = getSelected();
        if (type == null) {
            return;
        }
        if (dark) {
            type.setColorDark(rgb);
        } else {
            type.setColorLight(rgb);
        }
        updateColorButtons();
        int row = table.getSelectedRow();
        model.fireTableRowsUpdated(row, row);
    }

    private void addType() {
        // A copy of the built-in default (Development settings), as SWT does
        DBPConnectionType type = new DBPConnectionType(DBPConnectionType.DEFAULT_TYPE);
        type.setId(SecurityUtils.generateUniqueId());
        type.setName(SettingsMessages.types_new_name);
        type.setDescription(null);
        type.setColorLight(NO_COLOR);
        type.setColorDark(NO_COLOR);
        types.add(type);
        model.fireTableDataChanged();
        int row = types.size() - 1;
        table.getSelectionModel().setSelectionInterval(row, row);
        table.scrollRectToVisible(table.getCellRect(row, 0, true));
        nameField.requestFocusInWindow();
        nameField.selectAll();
    }

    private void deleteType() {
        DBPConnectionType type = getSelected();
        if (type == null || type.isPredefined()) {
            return;
        }
        DBPConnectionType original = originals.get(type);
        int used = original == null ? 0 : findConnections(Set.of(original)).size();
        String message = used == 0 ? NLS.bind(SettingsMessages.types_delete_confirm_unused, type.getName())
            : NLS.bind(SettingsMessages.types_delete_confirm, new Object[]{type.getName(), used, getReplacementType(Set.of()).getName()});
        if (!SwingPlatformUI.confirmChange(this, message, SettingsMessages.types_delete_title,
            SettingsMessages.types_delete, JOptionPane.WARNING_MESSAGE)) {
            return;
        }
        int row = table.getSelectedRow();
        types.remove(row);
        originals.remove(type);
        model.fireTableDataChanged();
        if (!types.isEmpty()) {
            int next = Math.min(row, types.size() - 1);
            table.getSelectionModel().setSelectionInterval(next, next);
        }
        showSelected();
    }

    /**
     * Copies of the registry's types, edited until they are saved.
     */
    private void loadTypes() {
        types.clear();
        originals.clear();
        for (DBPConnectionType type : registry.getConnectionTypes()) {
            DBPConnectionType copy = new DBPConnectionType(type);
            types.add(copy);
            originals.put(copy, type);
        }
    }

    /**
     * The number of added, changed and deleted types.
     */
    int getChangeCount() {
        int count = 0;
        for (DBPConnectionType copy : types) {
            DBPConnectionType original = originals.get(copy);
            if (original == null || !original.equals(copy)) {
                count++;
            }
        }
        for (DBPConnectionType type : registry.getConnectionTypes()) {
            if (!originals.containsValue(type)) {
                count++;
            }
        }
        return count;
    }

    /**
     * The page's Save: {@link #apply}, then new copies of the saved types (the same row stays selected).
     */
    void save(@Nullable Window owner) {
        int row = table.getSelectedRow();
        apply(owner);
        reload(row);
    }

    /**
     * The page's Revert: the saved types again.
     */
    void revert() {
        reload(table.getSelectedRow());
    }

    private void reload(int row) {
        loadTypes();
        model.fireTableDataChanged();
        if (!types.isEmpty()) {
            int index = Math.clamp(row, 0, types.size() - 1);
            table.getSelectionModel().setSelectionInterval(index, index);
        }
        showSelected();
    }

    /**
     * Saves the changed types, moves the connections of deleted types to the default type, and applies changed commit modes to the
     * open sessions.
     */
    private void apply(@Nullable Window owner) {
        Set<DBPConnectionType> removed = new HashSet<>();
        for (DBPConnectionType type : registry.getConnectionTypes()) {
            if (!originals.containsValue(type)) {
                removed.add(type);
            }
        }
        Set<DBPConnectionType> changed = new HashSet<>(removed);
        List<DBPConnectionType> added = new ArrayList<>();
        for (DBPConnectionType copy : types) {
            if (CommonUtils.isEmptyTrimmed(copy.getName())) {
                // A type needs a name in lists and the connection dialog
                copy.setName(originals.get(copy) != null ? originals.get(copy).getName() : copy.getId());
            }
            DBPConnectionType original = originals.get(copy);
            if (original == null) {
                added.add(copy);
            } else if (!original.equals(copy)) {
                changed.add(original);
            }
        }
        if (changed.isEmpty() && added.isEmpty()) {
            return;
        }
        // The commit mode each open connection has now (the type's default may change it)
        Map<DBPDataSourceContainer, Boolean> autoCommitBefore = new LinkedHashMap<>();
        Map<DBPDataSourceContainer, DBPDataSourceRegistry> affected = new LinkedHashMap<>();
        for (DBPDataSourceContainer container : findConnections(changed)) {
            affected.put(container, container.getRegistry());
            if (container.isConnected()) {
                autoCommitBefore.put(container, container.isDefaultAutoCommit());
            }
        }

        for (DBPConnectionType type : removed) {
            registry.removeConnectionType(type);
        }
        for (DBPConnectionType copy : types) {
            DBPConnectionType original = originals.get(copy);
            if (original != null && !original.equals(copy)) {
                copyType(copy, original);
            }
        }
        for (DBPConnectionType type : added) {
            DBPConnectionType stored = new DBPConnectionType(type);
            registry.addConnectionType(stored);
        }
        registry.saveConnectionTypes();

        DBPConnectionType replacement = getReplacementType(removed);
        Map<DBPDataSourceRegistry, List<DBPDataSourceContainer>> byRegistry = new LinkedHashMap<>();
        for (Map.Entry<DBPDataSourceContainer, DBPDataSourceRegistry> entry : affected.entrySet()) {
            DBPConnectionConfiguration configuration = entry.getKey().getConnectionConfiguration();
            if (removed.contains(configuration.getConnectionType())) {
                configuration.setConnectionType(replacement);
            }
            byRegistry.computeIfAbsent(entry.getValue(), r -> new ArrayList<>()).add(entry.getKey());
        }
        for (Map.Entry<DBPDataSourceRegistry, List<DBPDataSourceContainer>> entry : byRegistry.entrySet()) {
            try {
                // The connections keep their type in data-sources.json (and a copy of the type's settings)
                entry.getKey().updateDataSources(entry.getValue());
            } catch (DBException e) {
                log.debug("Can't save the connections of changed connection types", e);
                DBWorkbench.getPlatformUI().showError(SettingsMessages.types_tab, SettingsMessages.types_save_failed, e);
            }
        }
        for (DBPDataSourceContainer container : affected.keySet()) {
            container.getRegistry().notifyDataSourceListeners(new DBPEvent(DBPEvent.Action.OBJECT_UPDATE, container));
        }
        List<DBPDataSourceContainer> commitModeChanged = new ArrayList<>();
        for (Map.Entry<DBPDataSourceContainer, Boolean> entry : autoCommitBefore.entrySet()) {
            if (entry.getKey().isConnected() && entry.getKey().isDefaultAutoCommit() != entry.getValue()) {
                commitModeChanged.add(entry.getKey());
            }
        }
        applyCommitModes(owner, commitModeChanged, 0);
    }

    /**
     * Applies the new commit mode to the open sessions of one connection after the other, so that the questions about
     * uncommitted changes do not overlap.
     */
    private static void applyCommitModes(@Nullable Window owner, @NotNull List<DBPDataSourceContainer> containers, int index) {
        if (index < containers.size()) {
            AutoCommitApplier.apply(owner, containers.get(index), () -> applyCommitModes(owner, containers, index + 1));
        }
    }

    /**
     * SWT {@code PrefPageConnectionTypes.performOk}: every setting of the edited copy except the id.
     */
    private static void copyType(@NotNull DBPConnectionType from, @NotNull DBPConnectionType to) {
        to.setName(from.getName());
        to.setDescription(from.getDescription());
        to.setAutocommit(from.isAutocommit());
        to.setConfirmExecute(from.isConfirmExecute());
        to.setConfirmDataChange(from.isConfirmDataChange());
        to.setColorLight(from.getColorLight());
        to.setColorDark(from.getColorDark());
        to.setModifyPermissions(from.getModifyPermission());
        to.setSmartCommit(from.isSmartCommit());
        to.setSmartCommitRecover(from.isSmartCommitRecover());
        to.setAutoCloseTransactions(from.isAutoCloseTransactions());
        to.setCloseIdleTransactionPeriod(from.getCloseIdleTransactionPeriod());
        to.setAutoEndReadOnlyTransactions(from.isAutoEndReadOnlyTransactions());
        to.setAutoCloseConnections(from.isAutoCloseConnections());
        to.setCloseIdleConnectionPeriod(from.getCloseIdleConnectionPeriod());
    }

    /**
     * The connections of all projects that use one of the types.
     */
    @NotNull
    private static List<DBPDataSourceContainer> findConnections(@NotNull Set<DBPConnectionType> types) {
        List<DBPDataSourceContainer> result = new ArrayList<>();
        for (DBPProject project : DBWorkbench.getPlatform().getWorkspace().getProjects()) {
            for (DBPDataSourceContainer container : project.getDataSourceRegistry().getDataSources()) {
                if (types.contains(container.getConnectionConfiguration().getConnectionType())) {
                    result.add(container);
                }
            }
        }
        return result;
    }

    /**
     * The type that connections of a deleted type get: the default type of new connections, or Development.
     */
    @NotNull
    private DBPConnectionType getReplacementType(@NotNull Set<DBPConnectionType> removed) {
        DBPConnectionType type = DBPConnectionType.getDefaultConnectionType();
        if (removed.contains(type) || !originals.containsValue(type)) {
            type = registry.getConnectionType(DBPConnectionType.DEV.getId(), DBPConnectionType.DEV);
        }
        return type == null ? DBPConnectionType.DEV : type;
    }

    private final class TypesModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return types.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? SettingsMessages.types_name : SettingsMessages.types_description;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            DBPConnectionType type = types.get(rowIndex);
            return columnIndex == 0 ? type.getName() : CommonUtils.notEmpty(type.getDescription());
        }
    }
}
