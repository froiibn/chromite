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

import com.seizia.chromite.model.admin.security.Securable;
import com.seizia.chromite.model.admin.security.SecurityDialect;
import com.seizia.chromite.model.admin.security.SecurityPrincipal;
import com.seizia.chromite.model.admin.security.SecurityStatement;
import com.seizia.chromite.ui.swing.internal.SecurityMessages;
import com.seizia.chromite.ui.swing.internal.ToolsMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.navigator.DBNDatabaseFolder;
import com.seizia.chromite.runtime.DBWorkbench;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Properties of a login, user or role, and the New Login / User / Role dialog.
 *
 * 설계: SSMS 로그인 속성 창의 페이지(일반, 서버 역할, 사용자 매핑, 보안 개체, 상태)를 탭으로 옮겼다. 상태 페이지의 "로그인 사용"은
 * 일반 탭에 두었다. DB마다 없는 항목은 {@link SecurityDialect}의 지원 여부로 숨긴다(PostgreSQL 역할 옵션, MySQL 호스트, SQL Server 기본
 * 데이터베이스·암호 정책·사용자 매핑). 탭에서 고친 값은 편집본 {@link SecurityPrincipal}에 모이고, OK·Script는 원본과의 차이를
 * {@link SecurityDialect#generateScript}로 만든다(SSMS처럼 OK 한 번에 적용하되, 테이블 디자이너처럼 스크립트를 먼저 보여 준다).
 * SWT에서는 DB마다 따로 있는 편집기({@code MySQLUserEditorGeneral}·{@code MySQLUserEditorPrivileges}, PostgreSQL 역할 속성 편집기와
 * {@code RolePermissionsEditor}, SQL Server 로그인 만들기 대화상자 {@code SQLServerLoginConfigurator})가 하는 일을 한 창에 모은 것이다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-security.md)
 */
final class PrincipalPropertiesDialog extends JDialog {

    /**
     * {@code yyyy-MM-dd} with an optional {@code HH:mm[:ss]} (PostgreSQL VALID UNTIL accepts both).
     */
    private static final Pattern EXPIRY_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}( \\d{2}:\\d{2}(:\\d{2})?)?"); //$NON-NLS-1$
    private static final String DEFAULT_SCHEMA = "dbo"; //$NON-NLS-1$

    private final SecurityDialect dialect;
    private final SecurityPrincipal original;
    private final SecurityPrincipal edited;
    private final ObjectExplorerPanel.QueryWindows queryWindows;
    private final ObjectExplorerPanel explorer;
    @Nullable
    private final DBNDatabaseFolder folder;

    private final JTextField nameField = new JTextField(28);
    private final JTextField hostField = new JTextField(28);
    private final JPasswordField passwordField = new JPasswordField(28);
    private final JPasswordField confirmField = new JPasswordField(28);
    private final JComboBox<String> defaultDatabaseCombo = new JComboBox<>();
    private final JTextField expiryField = new JTextField(28);
    private final JCheckBox enabledBox = new JCheckBox();
    private final Map<String, JCheckBox> optionBoxes = new LinkedHashMap<>();

    @Nullable
    private PermissionEditorPanel<Securable> permissionEditor;
    private final JComboBox<String> permissionDatabaseCombo = new JComboBox<>();
    /**
     * SQL Server User Mapping page (created with the page: the model reads the edited principal).
     */
    @Nullable
    private MappingModel mappingModel;
    @Nullable
    private JTable mappingTable;
    private final FlagsModel mappingRolesModel = new FlagsModel(new LinkedHashMap<>(), SecurityMessages.security_column_role);
    private final JLabel mappingRolesLabel = new JLabel(SecurityMessages.security_mapping_roles_none);

    private PrincipalPropertiesDialog(
        @NotNull ObjectExplorerPanel owner,
        @NotNull SecurityDialect dialect,
        @NotNull SecurityPrincipal principal,
        @NotNull List<String> databases,
        @NotNull ObjectExplorerPanel.QueryWindows queryWindows,
        @Nullable DBNDatabaseFolder folder
    ) {
        super(SwingUtilities.getWindowAncestor(owner), SecurityLabels.getTitle(principal), ModalityType.APPLICATION_MODAL);
        setIconImages(ChromiteIcons.getWindowIcons());
        this.dialect = dialect;
        this.original = principal;
        this.edited = principal.copy();
        this.queryWindows = queryWindows;
        this.explorer = owner;
        this.folder = folder;

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab(SecurityMessages.security_page_general, createGeneralPage(databases));
        if (!edited.getMemberships().isEmpty()) {
            tabs.addTab(SecurityLabels.getMembershipTitle(dialect.getMembershipStyle()),
                createFlagsPage(new FlagsModel(edited.getMemberships(), SecurityMessages.security_column_role)));
        }
        if (dialect.supportsUserMapping() && edited.getKind() == SecurityPrincipal.Kind.LOGIN) {
            tabs.addTab(SecurityMessages.security_page_user_mapping, createMappingPage());
        }
        if (dialect.supportsPermissions(edited)) {
            tabs.addTab(SecurityMessages.security_page_permissions, createPermissionsPage());
        }
        tabs.addChangeListener(e -> updatePermissionDatabases());

        JButton scriptButton = new JButton(SecurityMessages.security_button_script);
        scriptButton.addActionListener(e -> {
            List<SecurityStatement> statements = collectStatements();
            if (statements != null) {
                SecurityCommands.showScript(this, getTitle(), dialect, statements, queryWindows);
            }
        });
        JButton okButton = new JButton(ToolsMessages.button_ok);
        okButton.addActionListener(e -> apply());
        JButton cancelButton = new JButton(ToolsMessages.button_cancel);
        cancelButton.addActionListener(e -> dispose());
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.add(scriptButton);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        right.add(okButton);
        right.add(cancelButton);
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.add(left, BorderLayout.WEST);
        buttons.add(right, BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        content.add(tabs, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().setDefaultButton(okButton);
        getRootPane().registerKeyboardAction(e -> dispose(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        setSize(720, 600);
        setLocationRelativeTo(owner);
    }

    /**
     * Opens the dialog for a principal read with {@link SecurityDialect#loadPrincipal} or {@link SecurityDialect#newPrincipal}.
     *
     * @param folder the Logins / Roles / Users folder of a new principal (read again after creating it), or null
     */
    static void show(
        @NotNull ObjectExplorerPanel owner,
        @NotNull SecurityDialect dialect,
        @NotNull SecurityPrincipal principal,
        @NotNull ObjectExplorerPanel.QueryWindows queryWindows,
        @Nullable DBNDatabaseFolder folder
    ) {
        List<String> databases = List.of();
        if (principal.getDefaultDatabase() != null) {
            try {
                databases = ProgressDialog.runTask(SecurityLabels.getTitle(principal), dialect::listDatabases);
            } catch (DBException e) {
                DBWorkbench.getPlatformUI().showError(SecurityLabels.getTitle(principal), null, e);
                return;
            }
        }
        new PrincipalPropertiesDialog(owner, dialect, principal, databases, queryWindows, folder).setVisible(true);
    }

    //////////////////////////////////////////////////////////
    // General

    @NotNull
    private JComponent createGeneralPage(@NotNull List<String> databases) {
        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;
        nameField.setText(edited.getName());
        nameField.setEnabled(edited.isNew() || dialect.supportsRename(edited));
        row = addRow(form, row, SecurityLabels.getNameLabel(edited.getKind()), nameField, null);
        if (dialect.supportsHost() && edited.getKind() == SecurityPrincipal.Kind.USER) {
            hostField.setText(edited.getHost());
            row = addRow(form, row, SecurityMessages.security_label_host, hostField, SecurityMessages.security_host_hint);
        }
        if (dialect.supportsPassword(edited)) {
            row = addRow(form, row, SecurityMessages.security_label_password, passwordField,
                edited.isNew() ? null : SecurityMessages.security_password_unchanged_hint);
            row = addRow(form, row, SecurityMessages.security_label_confirm_password, confirmField, null);
        }
        if (edited.getDefaultDatabase() != null) {
            for (String database : databases) {
                defaultDatabaseCombo.addItem(database);
            }
            defaultDatabaseCombo.setSelectedItem(edited.getDefaultDatabase());
            row = addRow(form, row, SecurityMessages.security_label_default_database, defaultDatabaseCombo, null);
        }
        if (edited.getExpiry() != null) {
            expiryField.setText(edited.getExpiry());
            row = addRow(form, row, SecurityMessages.security_label_expiry, expiryField, SecurityMessages.security_expiry_hint);
        }
        SecurityDialect.EnabledStyle enabledStyle = dialect.getEnabledStyle(edited);
        if (enabledStyle != null && edited.getEnabled() != null) {
            enabledBox.setText(SecurityLabels.getEnabledLabel(enabledStyle));
            boolean enabled = edited.getEnabled();
            enabledBox.setSelected(enabledStyle == SecurityDialect.EnabledStyle.NOT_LOCKED ? !enabled : enabled);
            row = addRow(form, row, null, enabledBox, null);
        }
        for (Map.Entry<String, Boolean> option : edited.getOptions().entrySet()) {
            JCheckBox box = new JCheckBox(SecurityLabels.getOptionLabel(option.getKey()), Boolean.TRUE.equals(option.getValue()));
            optionBoxes.put(option.getKey(), box);
            row = addRow(form, row, null, box, null);
        }
        JPanel page = new JPanel(new BorderLayout());
        page.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        page.add(form, BorderLayout.NORTH);
        return new JScrollPane(page);
    }

    private static int addRow(@NotNull JPanel form, int row, @Nullable String label, @NotNull JComponent field, @Nullable String hint) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridy = row;
        constraints.insets = new Insets(0, 0, 6, 10);
        constraints.anchor = GridBagConstraints.WEST;
        if (label != null) {
            constraints.gridx = 0;
            form.add(new JLabel(label), constraints);
        }
        constraints.gridx = 1;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(0, 0, hint == null ? 6 : 2, 0);
        form.add(field, constraints);
        if (hint == null) {
            return row + 1;
        }
        JLabel hintLabel = new JLabel(hint);
        hintLabel.putClientProperty("FlatLaf.styleClass", "small"); //$NON-NLS-1$ //$NON-NLS-2$
        hintLabel.setForeground(UIManager.getColor("Label.disabledForeground")); //$NON-NLS-1$
        GridBagConstraints hintConstraints = new GridBagConstraints();
        hintConstraints.gridx = 1;
        hintConstraints.gridy = row + 1;
        hintConstraints.anchor = GridBagConstraints.WEST;
        hintConstraints.insets = new Insets(0, 0, 8, 0);
        form.add(hintLabel, hintConstraints);
        return row + 2;
    }

    //////////////////////////////////////////////////////////
    // Membership and user mapping

    @NotNull
    private static JComponent createFlagsPage(@NotNull FlagsModel model) {
        JTable table = createFlagsTable(model);
        JPanel page = new JPanel(new BorderLayout());
        page.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        page.add(new JScrollPane(table), BorderLayout.CENTER);
        return page;
    }

    @NotNull
    private static JTable createFlagsTable(@NotNull FlagsModel model) {
        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setMaxWidth(80);
        table.getColumnModel().getColumn(0).setPreferredWidth(70);
        return table;
    }

    @NotNull
    private JComponent createMappingPage() {
        MappingModel mappingModel = new MappingModel();
        JTable mappingTable = new JTable(mappingModel);
        this.mappingModel = mappingModel;
        this.mappingTable = mappingTable;
        mappingTable.setFillsViewportHeight(true);
        mappingTable.getTableHeader().setReorderingAllowed(false);
        mappingTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        mappingTable.getColumnModel().getColumn(0).setMaxWidth(70);
        mappingTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateMappingRoles();
            }
        });
        JTable rolesTable = createFlagsTable(mappingRolesModel);

        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.add(new JLabel(SecurityMessages.security_mapping_users), BorderLayout.NORTH);
        top.add(new JScrollPane(mappingTable), BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(0, 4));
        bottom.add(mappingRolesLabel, BorderLayout.NORTH);
        bottom.add(new JScrollPane(rolesTable), BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, bottom);
        split.setResizeWeight(0.5);
        split.setBorder(BorderFactory.createEmptyBorder());
        JPanel page = new JPanel(new BorderLayout());
        page.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        page.add(split, BorderLayout.CENTER);
        if (mappingModel.getRowCount() > 0) {
            mappingTable.setRowSelectionInterval(0, 0);
        }
        return page;
    }

    private void updateMappingRoles() {
        if (mappingModel == null || mappingTable == null) {
            return;
        }
        int row = mappingTable.getSelectedRow();
        String database = row >= 0 ? mappingModel.databases.get(row) : null;
        SecurityPrincipal.DatabaseMapping mapping = database == null ? null : edited.getMappings().get(database);
        mappingRolesModel.setFlags(mapping == null ? new LinkedHashMap<>() : mapping.getRoles(), mapping != null && mapping.isMapped());
        mappingRolesLabel.setText(database == null ? SecurityMessages.security_mapping_roles_none
            : NLS.bind(SecurityMessages.security_mapping_roles, database));
    }

    //////////////////////////////////////////////////////////
    // Permissions

    @NotNull
    private JComponent createPermissionsPage() {
        boolean perDatabase = dialect.supportsUserMapping();
        PermissionEditorPanel<Securable> editor = new PermissionEditorPanel<>(edited.getPermissions(), SecurityMessages.security_securables,
            Securable::getDisplayName, securable -> SecurityLabels.getKindLabel(securable.kind()),
            securable -> dialect.getPrivileges(securable.kind()), dialect.supportsDeny(),
            SecurityMessages.security_add_objects, this::addObjects);
        permissionEditor = editor;
        JPanel page = new JPanel(new BorderLayout(0, 8));
        page.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        if (perDatabase) {
            JPanel databasePanel = new JPanel(new BorderLayout(6, 0));
            databasePanel.add(new JLabel(SecurityMessages.security_label_database), BorderLayout.WEST);
            databasePanel.add(permissionDatabaseCombo, BorderLayout.CENTER);
            page.add(databasePanel, BorderLayout.NORTH);
            permissionDatabaseCombo.addActionListener(e -> {
                String database = (String) permissionDatabaseCombo.getSelectedItem();
                editor.setRowFilter(securable -> database != null && database.equals(securable.catalog()));
                editor.setAddEnabled(database != null);
            });
            updatePermissionDatabases();
        }
        page.add(editor, BorderLayout.CENTER);
        return page;
    }

    /**
     * SQL Server: the databases the login is mapped to (in the edited state) are the ones it can have permissions in.
     */
    private void updatePermissionDatabases() {
        if (!dialect.supportsUserMapping() || permissionEditor == null) {
            return;
        }
        Object selected = permissionDatabaseCombo.getSelectedItem();
        List<String> mapped = new ArrayList<>();
        for (Map.Entry<String, SecurityPrincipal.DatabaseMapping> entry : edited.getMappings().entrySet()) {
            if (entry.getValue().isMapped()) {
                mapped.add(entry.getKey());
            }
        }
        List<String> current = new ArrayList<>();
        for (int i = 0; i < permissionDatabaseCombo.getItemCount(); i++) {
            current.add(permissionDatabaseCombo.getItemAt(i));
        }
        if (current.equals(mapped)) {
            return;
        }
        permissionDatabaseCombo.removeAllItems();
        for (String database : mapped) {
            permissionDatabaseCombo.addItem(database);
        }
        if (selected != null && mapped.contains(selected)) {
            permissionDatabaseCombo.setSelectedItem(selected);
        } else if (!mapped.isEmpty()) {
            permissionDatabaseCombo.setSelectedIndex(0);
        } else {
            permissionEditor.setRowFilter(securable -> false);
            permissionEditor.setAddEnabled(false);
        }
    }

    private void addObjects() {
        String database = dialect.supportsUserMapping() ? (String) permissionDatabaseCombo.getSelectedItem() : null;
        if (dialect.supportsUserMapping() && database == null) {
            JOptionPane.showMessageDialog(this, SecurityMessages.security_no_mapped_database, getTitle(), JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        List<Securable> securables;
        try {
            securables = ProgressDialog.runTask(SecurityMessages.security_pick_objects_title, monitor -> dialect.listSecurables(monitor, database));
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(SecurityMessages.security_pick_objects_title, null, e);
            return;
        }
        List<Securable> candidates = new ArrayList<>();
        for (Securable securable : securables) {
            if (!edited.getPermissions().hasRow(securable)) {
                candidates.add(securable);
            }
        }
        List<Securable> picked = SecurityPickerDialog.pick(this, SecurityMessages.security_pick_objects_title, candidates,
            Securable::getDisplayName, securable -> SecurityLabels.getKindLabel(securable.kind()));
        for (Securable securable : picked) {
            edited.getPermissions().addRow(securable);
        }
        if (!picked.isEmpty() && permissionEditor != null) {
            permissionEditor.refresh();
            permissionEditor.select(picked.getFirst());
        }
    }

    //////////////////////////////////////////////////////////
    // Apply

    /**
     * Copies the fields into the edited principal and makes the script, or shows what is wrong and returns null.
     */
    @Nullable
    private List<SecurityStatement> collectStatements() {
        if (mappingTable != null && mappingTable.isEditing()) {
            mappingTable.getCellEditor().stopCellEditing();
        }
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            return invalid(SecurityMessages.security_error_name_required, nameField);
        }
        char[] password = passwordField.getPassword();
        char[] confirm = confirmField.getPassword();
        try {
            if (!Arrays.equals(password, confirm)) {
                return invalid(SecurityMessages.security_error_password_mismatch, confirmField);
            }
            if (edited.isNew() && edited.getKind() == SecurityPrincipal.Kind.LOGIN && password.length == 0) {
                return invalid(SecurityMessages.security_error_password_required, passwordField);
            }
            edited.setPassword(password.length == 0 ? null : new String(password));
        } finally {
            Arrays.fill(password, '\0');
            Arrays.fill(confirm, '\0');
        }
        edited.setName(name);
        if (dialect.supportsHost() && edited.getKind() == SecurityPrincipal.Kind.USER) {
            edited.setHost(hostField.getText().trim().isEmpty() ? "%" : hostField.getText().trim()); //$NON-NLS-1$
        }
        if (edited.getDefaultDatabase() != null && defaultDatabaseCombo.getSelectedItem() instanceof String database) {
            edited.setDefaultDatabase(database);
        }
        if (edited.getExpiry() != null) {
            String expiry = expiryField.getText().trim();
            if (!expiry.isEmpty() && !EXPIRY_PATTERN.matcher(expiry).matches()) {
                return invalid(SecurityMessages.security_error_expiry, expiryField);
            }
            edited.setExpiry(expiry);
        }
        SecurityDialect.EnabledStyle enabledStyle = dialect.getEnabledStyle(edited);
        if (enabledStyle != null && edited.getEnabled() != null) {
            edited.setEnabled(enabledStyle == SecurityDialect.EnabledStyle.NOT_LOCKED ? !enabledBox.isSelected() : enabledBox.isSelected());
        }
        for (Map.Entry<String, JCheckBox> option : optionBoxes.entrySet()) {
            edited.getOptions().put(option.getKey(), option.getValue().isSelected());
        }
        for (Map.Entry<String, SecurityPrincipal.DatabaseMapping> entry : edited.getMappings().entrySet()) {
            if (entry.getValue().isMapped() && CommonUtils.isEmpty(entry.getValue().getUserName())) {
                return invalid(NLS.bind(SecurityMessages.security_error_user_required, entry.getKey()), nameField);
            }
        }
        try {
            return dialect.generateScript(original, edited);
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(getTitle(), null, e);
            return null;
        }
    }

    @Nullable
    private List<SecurityStatement> invalid(@NotNull String message, @NotNull JComponent field) {
        JOptionPane.showMessageDialog(this, message, getTitle(), JOptionPane.WARNING_MESSAGE);
        // The field may be on another tab than the one shown (OK and Script work from every tab)
        for (Component page = field; page != null && page.getParent() != null; page = page.getParent()) {
            if (page.getParent() instanceof JTabbedPane tabs) {
                tabs.setSelectedComponent(page);
                break;
            }
        }
        field.requestFocusInWindow();
        return null;
    }

    private void apply() {
        List<SecurityStatement> statements = collectStatements();
        if (statements == null) {
            return;
        }
        SecurityCommands.ApplyResult result = SecurityCommands.applyScript(this, getTitle(), dialect, statements, queryWindows,
            explorer, folder, original.getObject());
        // The password is only kept until it is executed or the dialog closes
        edited.setPassword(null);
        if (result == SecurityCommands.ApplyResult.EXECUTED) {
            dispose();
        }
    }

    //////////////////////////////////////////////////////////
    // Table models

    /**
     * Check box list of names (role memberships).
     */
    private static final class FlagsModel extends AbstractTableModel {
        private Map<String, Boolean> flags;
        private List<String> names;
        private final String nameTitle;
        private boolean editable = true;

        FlagsModel(@NotNull Map<String, Boolean> flags, @NotNull String nameTitle) {
            this.flags = flags;
            this.names = new ArrayList<>(flags.keySet());
            this.nameTitle = nameTitle;
        }

        void setFlags(@NotNull Map<String, Boolean> flags, boolean editable) {
            this.flags = flags;
            this.names = new ArrayList<>(flags.keySet());
            this.editable = editable;
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return names.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? SecurityMessages.security_column_member : nameTitle;
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 0 && editable;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            String name = names.get(rowIndex);
            return columnIndex == 0 ? Boolean.TRUE.equals(flags.get(name)) : name;
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            flags.put(names.get(rowIndex), Boolean.TRUE.equals(value));
            fireTableRowsUpdated(rowIndex, rowIndex);
        }
    }

    /**
     * SQL Server User Mapping: one row per database (Map, Database, User, Default Schema).
     */
    private final class MappingModel extends AbstractTableModel {
        private final List<String> databases = new ArrayList<>(edited.getMappings().keySet());

        @Override
        public int getRowCount() {
            return databases.size();
        }

        @Override
        public int getColumnCount() {
            return 4;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> SecurityMessages.security_column_map;
                case 1 -> SecurityMessages.security_column_database;
                case 2 -> SecurityMessages.security_column_user;
                default -> SecurityMessages.security_column_default_schema;
            };
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 0 || (columnIndex >= 2 && edited.getMappings().get(databases.get(rowIndex)).isMapped());
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            String database = databases.get(rowIndex);
            SecurityPrincipal.DatabaseMapping mapping = edited.getMappings().get(database);
            return switch (columnIndex) {
                case 0 -> mapping.isMapped();
                case 1 -> database;
                case 2 -> mapping.isMapped() ? CommonUtils.notEmpty(mapping.getUserName()) : ""; //$NON-NLS-1$
                default -> mapping.isMapped() ? CommonUtils.notEmpty(mapping.getDefaultSchema()) : ""; //$NON-NLS-1$
            };
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            SecurityPrincipal.DatabaseMapping mapping = edited.getMappings().get(databases.get(rowIndex));
            switch (columnIndex) {
                case 0 -> {
                    boolean mapped = Boolean.TRUE.equals(value);
                    mapping.setMapped(mapped);
                    // SSMS fills the user with the login name and leaves the schema to the database default (dbo)
                    if (mapped && CommonUtils.isEmpty(mapping.getUserName())) {
                        mapping.setUserName(nameField.getText().trim());
                    }
                    if (mapped && CommonUtils.isEmpty(mapping.getDefaultSchema())) {
                        mapping.setDefaultSchema(DEFAULT_SCHEMA);
                    }
                    updateMappingRoles();
                }
                case 2 -> mapping.setUserName(CommonUtils.toString(value).trim());
                case 3 -> mapping.setDefaultSchema(CommonUtils.toString(value).trim());
                default -> {
                }
            }
            fireTableRowsUpdated(rowIndex, rowIndex);
        }
    }
}
