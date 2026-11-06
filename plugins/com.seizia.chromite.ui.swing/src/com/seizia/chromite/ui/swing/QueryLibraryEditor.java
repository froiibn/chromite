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
import com.formdev.flatlaf.util.UIScale;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Query Library 쿼리를 편집·실행하는 쿼리 창 확장({@link SqlEditorExtension}): 이름·설명·기본 대상·기본 DB/스키마·매개변수 머리말,
 * 라이브러리로 저장, 실행 전 매개변수 묻기.
 *
 * 설계:
 * <ul>
 *     <li>편집과 실행은 같은 쿼리 창이다(쿼리 창의 구문 색, 자동완성, 찾기, GO 배치, 여러 결과, Messages를 그대로 씀). 트리의 Run은 이 쿼리의 창이
 *     같은 연결로 열려 있고 실행 중이 아니면 그 창에서, 아니면 새 창을 열어 실행한다. 다른 서버에서 같은 쿼리를 나란히 볼 수 있게 하기 위해서다.</li>
 *     <li>창은 쿼리 파일 이름(쿼리 이름)과 연결로 제목을 단다. 저장(Ctrl+S, 닫을 때 저장)은 파일에 SQL만 쓰지 않고 머리말과 함께
 *     {@link QueryLibrary#save}로 쓴다. 이름을 바꾸면 파일 이름도 바뀐다.</li>
 *     <li>머리말 입력란을 바꾸면 쿼리 창을 "고침"으로 표시한다. 매개변수 정의는 "Parameters…"에서 지금 SQL의 매개변수에 대해 정한다.</li>
 *     <li>실행 대상 찾기({@link #resolveTarget}): 정해진 연결은 데이터 소스 ID → 이름 순으로 찾고, 없거나 "묻기"면 연결을 고르게 한다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 Query Library: 최초 작성 (docs/chromite/swing-query-library.md "실행 흐름")
 */
final class QueryLibraryEditor implements SqlEditorExtension {

    private final QueryLibraryPanel.Host host;
    // The query as last saved, with the header fields applied on save
    private QueryLibraryItem item;
    @Nullable
    private SqlEditorPanel editor;
    private final JPanel header;
    private final JTextField nameField = new JTextField(18);
    private final JTextField descriptionField = new JTextField(30);
    private final JComboBox<Object> targetCombo = new JComboBox<>();
    private final JTextField databaseField = new JTextField(10);
    private final JTextField schemaField = new JTextField(10);
    private final JButton parametersButton = new JButton();
    private List<QueryLibraryParameter> parameters = new ArrayList<>();
    private boolean updating;

    private QueryLibraryEditor(@NotNull QueryLibraryPanel.Host host, @NotNull QueryLibraryItem item) {
        this.host = host;
        this.item = item.copy();
        this.parameters = new ArrayList<>(this.item.getParameters());
        header = createHeader();
        loadFields();
    }

    //////////////////////////////////////////////////////////
    // Opening and running (EDT)

    /**
     * Opens a query for editing: its window if it is open already, else a new one with the query's default target
     * (if it can be found without asking).
     */
    static void edit(@NotNull QueryLibraryPanel.Host host, @NotNull QueryLibraryItem item) {
        SqlEditorPanel existing = findWindow(host, item, null, false);
        if (existing != null) {
            host.showQueryWindow(existing);
            return;
        }
        openWindow(host, item, findDefaultTarget(host, item));
    }

    /**
     * Runs a query on its default target ({@code container == null}) or on the given connection, in its window
     * (reused if open on the same connection and idle) or a new one.
     */
    static void run(@NotNull QueryLibraryPanel.Host host, @NotNull QueryLibraryItem item, @Nullable DBPDataSourceContainer container) {
        DBPDataSourceContainer target = container != null ? container : resolveTarget(host, item);
        if (target == null) {
            return;
        }
        SqlEditorPanel editor = findWindow(host, item, target, true);
        if (editor == null) {
            editor = openWindow(host, item, target);
        } else {
            host.showQueryWindow(editor);
        }
        editor.execute();
    }

    @Nullable
    private static SqlEditorPanel findWindow(
        @NotNull QueryLibraryPanel.Host host,
        @NotNull QueryLibraryItem item,
        @Nullable DBPDataSourceContainer container,
        boolean idleOnly
    ) {
        for (SqlEditorPanel window : host.getQueryWindows()) {
            if (window.getExtension() instanceof QueryLibraryEditor libraryEditor
                && libraryEditor.item.getStateKey().equals(item.getStateKey())
                && (container == null || window.getDataSourceContainer() == container)
                && (!idleOnly || !window.isExecuting())) {
                return window;
            }
        }
        return null;
    }

    @NotNull
    private static SqlEditorPanel openWindow(
        @NotNull QueryLibraryPanel.Host host,
        @NotNull QueryLibraryItem item,
        @Nullable DBPDataSourceContainer container
    ) {
        Path file = QueryLibrary.getInstance().resolveFile(item);
        String fileName = file != null ? String.valueOf(file.getFileName()) : QueryLibraryFile.toFileName(item.getName()) + QueryLibraryFile.EXTENSION;
        SqlEditorPanel editor = host.openQueryWindow(container, fileName);
        editor.setContent(SqlFileContent.newFile(item.getSql()), file);
        QueryLibraryEditor extension = new QueryLibraryEditor(host, item);
        extension.editor = editor;
        editor.setExtension(extension);
        return editor;
    }

    /**
     * The connection to run a query on by default: its fixed connection (by id, else by name), the current connection,
     * or the one the user picks.
     */
    @Nullable
    static DBPDataSourceContainer resolveTarget(@NotNull QueryLibraryPanel.Host host, @NotNull QueryLibraryItem item) {
        DBPDataSourceContainer target = findDefaultTarget(host, item);
        if (target != null) {
            return target;
        }
        String reason = switch (item.getTargetMode()) {
            case CONNECTION -> "The connection '" + CommonUtils.notEmpty(item.getTargetName()) + "' of this query was not found.";
            case CURRENT -> "No connection is selected in Object Explorer.";
            case ASK -> null;
        };
        return chooseConnection(host.getComponent(), "Run " + item.getName(), reason, host.getCurrentConnection());
    }

    @Nullable
    private static DBPDataSourceContainer findDefaultTarget(@NotNull QueryLibraryPanel.Host host, @NotNull QueryLibraryItem item) {
        return switch (item.getTargetMode()) {
            case CONNECTION -> findConnection(item.getTargetId(), item.getTargetName());
            case CURRENT -> host.getCurrentConnection();
            case ASK -> null;
        };
    }

    /**
     * A connection by data source id, else by name (a shared query on another computer has other ids), in any project.
     */
    @Nullable
    static DBPDataSourceContainer findConnection(@Nullable String id, @Nullable String name) {
        List<? extends DBPProject> projects = DBWorkbench.getPlatform().getWorkspace().getProjects();
        if (id != null) {
            for (DBPProject project : projects) {
                DBPDataSourceContainer container = project.getDataSourceRegistry().getDataSource(id);
                if (container != null) {
                    return container;
                }
            }
        }
        if (name != null) {
            for (DBPProject project : projects) {
                DBPDataSourceContainer container = project.getDataSourceRegistry().findDataSourceByName(name);
                if (container != null) {
                    return container;
                }
            }
        }
        return null;
    }

    /**
     * The connections of the workspace (not hidden or temporary), by name.
     */
    @NotNull
    static List<DBPDataSourceContainer> getConnections() {
        List<DBPDataSourceContainer> result = new ArrayList<>();
        for (DBPProject project : DBWorkbench.getPlatform().getWorkspace().getProjects()) {
            for (DBPDataSourceContainer container : project.getDataSourceRegistry().getDataSources()) {
                if (!container.isHidden() && !container.isTemporary()) {
                    result.add(container);
                }
            }
        }
        result.sort(Comparator.comparing(DBPDataSourceContainer::getName, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    /**
     * Asks for a connection ("Run on…", or a default target that is not available).
     */
    @Nullable
    static DBPDataSourceContainer chooseConnection(
        @NotNull Component parent,
        @NotNull String title,
        @Nullable String reason,
        @Nullable DBPDataSourceContainer preselected
    ) {
        List<DBPDataSourceContainer> connections = getConnections();
        if (connections.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "There are no connections. Create a connection first.", title, JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        JComboBox<DBPDataSourceContainer> combo = new JComboBox<>(connections.toArray(new DBPDataSourceContainer[0]));
        combo.setRenderer(new ConnectionRenderer());
        if (preselected != null) {
            combo.setSelectedItem(preselected);
        }
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        if (reason != null) {
            panel.add(new JLabel(reason), BorderLayout.NORTH);
        }
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.add(new JLabel("Connection"), BorderLayout.WEST);
        row.add(combo, BorderLayout.CENTER);
        panel.add(row, BorderLayout.CENTER);
        int answer = JOptionPane.showConfirmDialog(parent, panel, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        return answer == JOptionPane.OK_OPTION && combo.getSelectedItem() instanceof DBPDataSourceContainer selected ? selected : null;
    }

    //////////////////////////////////////////////////////////
    // SqlEditorExtension

    @NotNull
    @Override
    public JComponent getHeader() {
        return header;
    }

    @NotNull
    @Override
    public Icon getTabIcon() {
        return UiIcons.get("library-query"); //$NON-NLS-1$
    }

    @Override
    public boolean save(@NotNull SqlEditorPanel editor) {
        QueryLibrary library = QueryLibrary.getInstance();
        Path file = library.resolveFile(item);
        if (file == null || library.isReadOnly(file)) {
            JOptionPane.showMessageDialog(editor, "This query is in a read-only library root and can't be saved.\n"
                    + "Use Duplicate in the Query Library to copy it to an editable root.",
                "Save", JOptionPane.INFORMATION_MESSAGE);
            return false;
        }
        String name = nameField.getText().strip();
        if (name.isEmpty()) {
            JOptionPane.showMessageDialog(editor, "Enter a name for the query.", "Save", JOptionPane.INFORMATION_MESSAGE);
            nameField.requestFocusInWindow();
            return false;
        }
        QueryLibraryItem updated = item.copy();
        updated.setFile(file);
        applyFields(updated, editor.getContent().text());
        try {
            library.save(updated);
        } catch (IOException e) {
            DBWorkbench.getPlatformUI().showError("Save", "Can't save the query to " + file, e);
            return false;
        }
        item = updated;
        Path savedFile = updated.getFile();
        if (savedFile != null) {
            editor.saved(savedFile, editor.getContent());
        }
        return true;
    }

    @Nullable
    @Override
    public SqlParameterBinding prepareExecution(@NotNull SqlEditorPanel editor, @NotNull DBPDataSourceContainer container, @NotNull String sql) {
        QueryLibraryItem current = item.copy();
        applyFields(current, editor.getContent().text());
        return askParameters(editor, current, container, sql);
    }

    /**
     * Asks for the values of the parameters of the SQL that runs (none: no dialog) and makes its binding.
     *
     * @return the binding, or null if canceled
     */
    @Nullable
    static SqlParameterBinding askParameters(
        @NotNull Component parent,
        @NotNull QueryLibraryItem item,
        @Nullable DBPDataSourceContainer container,
        @NotNull String sql
    ) {
        Map<String, Boolean> kinds = QueryParameterBinder.detect(sql);
        Map<String, String> values = new LinkedHashMap<>();
        if (!kinds.isEmpty()) {
            List<QueryLibraryParameter> definitions = QueryParameterDefinitionsDialog.merge(item.getParameters(), kinds);
            Map<String, String> lastValues = QueryLibraryState.getLastValues(item);
            Map<String, String> answer = QueryParameterDialog.open(parent, item.getName(), definitions, kinds, lastValues, container);
            if (answer == null) {
                return null;
            }
            Map<String, String> remembered = new LinkedHashMap<>(lastValues);
            remembered.putAll(answer);
            QueryLibraryState.setLastValues(item, remembered);
            values.putAll(answer);
        }
        QueryParameterBinder binder = new QueryParameterBinder(item, values);
        return binder.isNeeded() ? binder : SqlParameterBinding.NONE;
    }

    //////////////////////////////////////////////////////////
    // Header (EDT)

    /**
     * Two rows below the query tool bar: Name, Description | Target, Database, Schema, Parameters….
     */
    @NotNull
    private JPanel createHeader() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(UIScale.scale(2), UIScale.scale(10), UIScale.scale(6), UIScale.scale(10)));
        targetCombo.setRenderer(new ConnectionRenderer());
        targetCombo.setToolTipText("Connection a run from the Query Library uses (an unavailable connection is asked for)");
        nameField.setToolTipText("Name of the query in the Query Library (also its file name)");
        descriptionField.setToolTipText("What the query shows");
        descriptionField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Description");
        databaseField.setToolTipText("Default database (catalog) to switch to before a run; empty keeps the connection's");
        databaseField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Database");
        schemaField.setToolTipText("Default schema to switch to before a run; empty keeps the connection's");
        schemaField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Schema");
        parametersButton.setToolTipText("Label, type, default value and choices of the parameters (:name, ${name}) of the SQL");
        parametersButton.addActionListener(e -> editParameters());

        // Only Name and Target have labels; the other fields show placeholders, so both rows fit a narrow document area
        addCell(panel, new JLabel("Name"), 0, 0, 0, 1);
        addCell(panel, nameField, 1, 0, 0.35, 1);
        addCell(panel, descriptionField, 2, 0, 0.65, 3);
        addCell(panel, new JLabel("Target"), 0, 1, 0, 1);
        addCell(panel, targetCombo, 1, 1, 0.35, 1);
        addCell(panel, databaseField, 2, 1, 0.3, 1);
        addCell(panel, schemaField, 3, 1, 0.3, 1);
        addCell(panel, parametersButton, 4, 1, 0, 1);

        DocumentListener modified = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                fieldChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                fieldChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
            }
        };
        for (JTextField field : List.of(nameField, descriptionField, databaseField, schemaField)) {
            field.getDocument().addDocumentListener(modified);
        }
        targetCombo.addActionListener(e -> fieldChanged());
        return panel;
    }

    private static void addCell(@NotNull JPanel panel, @NotNull JComponent component, int x, int y, double weight, int width) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x;
        c.gridy = y;
        c.gridwidth = width;
        c.weightx = weight;
        c.fill = weight > 0 ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
        // Fields shrink to a usable width at most; labels and the button never shrink
        Dimension preferred = component.getPreferredSize();
        component.setMinimumSize(new Dimension(weight > 0 ? UIScale.scale(70) : preferred.width, preferred.height));
        if (weight > 0) {
            component.setPreferredSize(new Dimension(UIScale.scale(70), preferred.height));
        }
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(UIScale.scale(2), 0, UIScale.scale(2), UIScale.scale(6));
        panel.add(component, c);
    }

    private void loadFields() {
        updating = true;
        try {
            nameField.setText(item.getName());
            descriptionField.setText(CommonUtils.notEmpty(item.getDescription()));
            databaseField.setText(CommonUtils.notEmpty(item.getDatabase()));
            schemaField.setText(CommonUtils.notEmpty(item.getSchema()));
            // Show the start of long names and descriptions
            for (JTextField field : List.of(nameField, descriptionField, databaseField, schemaField)) {
                field.setCaretPosition(0);
            }
            DefaultComboBoxModel<Object> model = new DefaultComboBoxModel<>();
            model.addElement(QueryLibraryItem.TargetMode.ASK);
            model.addElement(QueryLibraryItem.TargetMode.CURRENT);
            Object selected = item.getTargetMode();
            DBPDataSourceContainer fixed = item.getTargetMode() == QueryLibraryItem.TargetMode.CONNECTION
                ? findConnection(item.getTargetId(), item.getTargetName()) : null;
            for (DBPDataSourceContainer container : getConnections()) {
                model.addElement(container);
                if (container == fixed) {
                    selected = container;
                }
            }
            if (item.getTargetMode() == QueryLibraryItem.TargetMode.CONNECTION && fixed == null) {
                // Keep a connection of another computer as it is
                MissingConnection missing = new MissingConnection(item.getTargetId(), CommonUtils.notEmpty(item.getTargetName()));
                model.addElement(missing);
                selected = missing;
            }
            model.setSelectedItem(selected);
            targetCombo.setModel(model);
            updateParametersButton();
        } finally {
            updating = false;
        }
    }

    private void fieldChanged() {
        if (!updating && editor != null) {
            editor.markModified();
        }
    }

    private void updateParametersButton() {
        long defined = parameters.stream().filter(p -> !p.isPlain()).count();
        parametersButton.setText(defined > 0 ? "Parameters (" + defined + ")…" : "Parameters…");
    }

    private void editParameters() {
        if (editor == null) {
            return;
        }
        Map<String, Boolean> kinds = QueryParameterBinder.detect(editor.getContent().text());
        List<QueryLibraryParameter> edited = QueryParameterDefinitionsDialog.open(editor, parameters, kinds);
        if (edited != null) {
            parameters = edited;
            updateParametersButton();
            editor.markModified();
        }
    }

    /**
     * Puts the header fields and the SQL into a copy of the query. Definitions of parameters that are no longer in the
     * SQL are dropped.
     */
    private void applyFields(@NotNull QueryLibraryItem target, @NotNull String sql) {
        String name = nameField.getText().strip();
        if (!name.isEmpty()) {
            target.setName(name);
        }
        target.setDescription(descriptionField.getText().strip());
        target.setDatabase(databaseField.getText().strip());
        target.setSchema(schemaField.getText().strip());
        Object selected = targetCombo.getSelectedItem();
        if (selected instanceof QueryLibraryItem.TargetMode mode) {
            target.setTargetMode(mode);
            target.setTarget(null, null);
        } else if (selected instanceof DBPDataSourceContainer container) {
            target.setTargetMode(QueryLibraryItem.TargetMode.CONNECTION);
            target.setTarget(container.getId(), container.getName());
        } else if (selected instanceof MissingConnection missing) {
            target.setTargetMode(QueryLibraryItem.TargetMode.CONNECTION);
            target.setTarget(missing.id(), missing.name());
        }
        target.setSql(sql);
        target.getParameters().clear();
        target.getParameters().addAll(QueryParameterDefinitionsDialog.merge(parameters, QueryParameterBinder.detect(sql)));
    }

    /**
     * The fixed target of a query that is not a connection here (written by another computer).
     */
    private record MissingConnection(@Nullable String id, @NotNull String name) {
    }

    /**
     * Target items: "Ask when run", "Current connection", connections with their driver icons.
     */
    private static final class ConnectionRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof DBPDataSourceContainer container) {
                setText(container.getName());
                setIcon(NodeIcons.getIcon(container.getDriver().getIcon()));
            } else if (value instanceof QueryLibraryItem.TargetMode mode) {
                setText(mode.getLabel());
                setIcon(null);
            } else if (value instanceof MissingConnection missing) {
                setText(missing.name() + " (not found)");
                setIcon(null);
            }
            return this;
        }
    }
}
