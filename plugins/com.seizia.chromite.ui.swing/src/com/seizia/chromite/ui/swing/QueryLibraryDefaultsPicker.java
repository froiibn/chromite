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
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBIcon;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPImage;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionContextDefaults;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectContainer;
import com.seizia.chromite.model.struct.rdb.DBSCatalog;
import com.seizia.chromite.model.struct.rdb.DBSSchema;
import com.seizia.chromite.ui.swing.internal.ToolsMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * Database와 Schema 칸({@link QueryLibraryEditor} 머리말): 직접 입력할 수 있는 콤보이고, 목록은 연결의 카탈로그(DB)와 그 DB의 스키마다.
 *
 * 설계:
 * <ul>
 *     <li>직접 입력을 유지한다. 공유 쿼리는 다른 서버, 다른 DB 이름에서도 쓰므로 목록에 없는 값(저장된 값 포함)도 그대로 보이고 저장된다.</li>
 *     <li>목록은 대상이 정해진 연결이면 그 연결, "실행 때 묻기"·"현재 연결"이면 이 쿼리 창의 연결에서 읽는다(사용자 결정 2026-11-08).
 *     목록을 펼칠 때 작업 스레드에서 읽고, 연결되어 있지 않으면 그때 평소 연결 경로({@link DBUtils#initDataSource}: 비밀번호 묻기, 오류 표시)로
 *     연결한다. 머리말을 여는 것만으로는 연결하지 않는다.</li>
 *     <li>SQL Editor 도구 막대의 DB/스키마 콤보는 창 세션이 바꿀 수 있는 한 단계만 보여 주므로({@code ContextDefaultObjectsReader}) 두 칸에
 *     쓰지 않고, 같은 모델(데이터 소스의 {@link DBSObjectContainer}, 첫째 자식 형식)을 직접 읽는다: 첫째 자식이 {@link DBSCatalog}면 DB 목록,
 *     카탈로그의 첫째 자식이 {@link DBSSchema}면 스키마 목록, 데이터 소스의 첫째 자식이 {@link DBSSchema}면 스키마만.</li>
 *     <li>정해진 대상 연결이 쓰지 않는 칸(MySQL의 스키마, Oracle의 DB)은 끈다. 다만 값이 있으면 지울 수 있게 켜 둔다. 언제든 다른 연결에서
 *     실행될 수 있는 "실행 때 묻기"·"현재 연결"에서는 끄지 않는다. 연결되어 있을 때만 알 수 있으므로, 대상이 연결되어 있으면 머리말을 열 때
 *     (연결하지 않고) 읽고, 아니면 목록을 처음 펼칠 때 안다.</li>
 * </ul>
 * 모든 메서드는 EDT에서 불린다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: 머리말의 Database·Schema 글 칸을 목록이 있는 콤보로 바꿈 (docs/chromite/swing-query-library.md "머리말 DB/스키마 목록")
 */
final class QueryLibraryDefaultsPicker {

    private static final Log log = Log.getLog(QueryLibraryDefaultsPicker.class);

    /**
     * The connection the lists are read from, and whether it is the query's fixed target (else the query window's
     * connection, for "Ask when run" and "Current connection"). A fixed target that is not on this computer has none.
     */
    record Source(@Nullable DBPDataSourceContainer container, boolean fixed) {
    }

    /**
     * What a connection uses (catalogs, schemas) and the names read.
     */
    private record Lists(boolean usesDatabase, boolean usesSchema, @NotNull List<String> databases, @NotNull List<String> schemas) {
        static final Lists NONE = new Lists(false, false, List.of(), List.of());
    }

    private final Supplier<Source> source;
    private final Runnable modified;
    private final JComboBox<String> databaseCombo = new JComboBox<>();
    private final JComboBox<String> schemaCombo = new JComboBox<>();
    private boolean updating;
    // The popup is shown again to fit the list that was read: no new read
    private boolean reshowing;
    // A newer read of a list replaces older ones still running
    private int databaseSerial;
    private int schemaSerial;
    // What the fixed target uses, from its last read; null while not known
    @Nullable
    private Lists structure;

    /**
     * @param source   the connection to list, asked whenever a list is read
     * @param modified called when the user changes a value
     */
    QueryLibraryDefaultsPicker(@NotNull Supplier<Source> source, @NotNull Runnable modified) {
        this.source = source;
        this.modified = modified;
        configure(databaseCombo, DBIcon.TREE_DATABASE, ToolsMessages.library_editor_database, true);
        configure(schemaCombo, DBIcon.TREE_SCHEMA, ToolsMessages.library_editor_schema, false);
    }

    @NotNull
    JComboBox<String> getDatabaseCombo() {
        return databaseCombo;
    }

    @NotNull
    JComboBox<String> getSchemaCombo() {
        return schemaCombo;
    }

    @NotNull
    String getDatabase() {
        return getText(databaseCombo).strip();
    }

    @NotNull
    String getSchema() {
        return getText(schemaCombo).strip();
    }

    /**
     * Shows the values of a query (opened or reloaded), without reporting a change.
     */
    void setValues(@Nullable String database, @Nullable String schema) {
        setText(databaseCombo, CommonUtils.notEmpty(database));
        setText(schemaCombo, CommonUtils.notEmpty(schema));
        updateState();
    }

    /**
     * The target changed (or the query was opened): forgets the lists and what the old connection uses and, if the new
     * fixed target is connected, reads it without connecting.
     */
    void sourceChanged() {
        structure = null;
        // Reads of the old connection are dropped
        databaseSerial++;
        schemaSerial++;
        setItems(databaseCombo, List.of());
        setItems(schemaCombo, List.of());
        updateState();
        Source current = source.get();
        if (current.fixed() && current.container() != null && current.container().isConnected()) {
            readList(databaseCombo, false);
        }
    }

    //////////////////////////////////////////////////////////
    // Lists

    private void configure(@NotNull JComboBox<String> combo, @NotNull DBPImage icon, @NotNull String placeholder, boolean databases) {
        combo.setEditable(true);
        combo.setRenderer(new NameRenderer(icon));
        combo.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, placeholder);
        listenToEditor(combo);
        // A theme or density change gives the combo a new editor
        combo.addPropertyChangeListener("editor", e -> listenToEditor(combo)); //$NON-NLS-1$
        combo.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                if (!reshowing) {
                    readList(combo, true);
                }
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
            }
        });
    }

    private void listenToEditor(@NotNull JComboBox<String> combo) {
        if (combo.getEditor().getEditorComponent() instanceof JTextComponent text) {
            text.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    textChanged();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    textChanged();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                }
            });
        }
    }

    private void textChanged() {
        if (!updating) {
            modified.run();
        }
    }

    /**
     * Reads the list of a combo in a job: the databases, or the schemas of the database in the Database field (empty:
     * of the connection's default database).
     *
     * @param connect connect first if not connected (the user opened the list)
     */
    private void readList(@NotNull JComboBox<String> combo, boolean connect) {
        DBPDataSourceContainer container = source.get().container();
        if (container == null) {
            return;
        }
        boolean databases = combo == databaseCombo;
        int serial = databases ? ++databaseSerial : ++schemaSerial;
        String database = getDatabase();
        new AbstractJob("Read " + (databases ? "databases" : "schemas") + " of " + container.getName()) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                Lists lists = null;
                try {
                    // Without a listener the connection service shows its own errors
                    if (connect ? DBUtils.initDataSource(monitor, container, null) : container.isConnected()) {
                        DBPDataSource dataSource = container.getDataSource();
                        if (dataSource != null) {
                            lists = read(monitor, dataSource, database, !databases);
                        }
                    }
                } catch (DBException e) {
                    log.debug("Can't read the " + (databases ? "databases" : "schemas") + " of '" + container.getName() + "'", e); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                }
                Lists result = lists;
                SwingUtilities.invokeLater(() -> listRead(combo, serial, container, result));
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    private void listRead(@NotNull JComboBox<String> combo, int serial, @NotNull DBPDataSourceContainer container, @Nullable Lists lists) {
        boolean databases = combo == databaseCombo;
        Source current = source.get();
        if (serial != (databases ? databaseSerial : schemaSerial) || current.container() != container || lists == null) {
            return;
        }
        setItems(combo, databases ? lists.databases() : lists.schemas());
        if (current.fixed()) {
            structure = lists;
            updateState();
        }
        if (combo.isPopupVisible()) {
            // The popup was sized for the old list
            reshowing = true;
            try {
                combo.hidePopup();
                if (combo.isEnabled()) {
                    combo.showPopup();
                }
            } finally {
                reshowing = false;
            }
        }
    }

    /**
     * Reads what a connection uses and its databases, or the schemas of a database (job thread).
     *
     * @param database the Database field: the database whose schemas are read (empty: the connection's default one)
     */
    @NotNull
    private static Lists read(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSource dataSource,
        @NotNull String database,
        boolean withSchemas
    ) throws DBException {
        DBSObjectContainer root = DBUtils.getAdapter(DBSObjectContainer.class, dataSource);
        if (root == null) {
            return Lists.NONE;
        }
        // The order of the SQL Editor tool bar's picker (ContextDefaultObjectsReader): catalogs first
        Class<? extends DBSObject> childType = root.getPrimaryChildType(monitor);
        if (DBSCatalog.class.isAssignableFrom(childType)) {
            List<DBSCatalog> catalogs = filter(root.getChildren(monitor), DBSCatalog.class);
            DBSCatalog defaultCatalog = getDefaultCatalog(monitor, dataSource);
            // A name that is not a database here has no schema list
            DBSCatalog catalog = database.isEmpty() ? defaultCatalog : DBUtils.findObject(catalogs, database, true);
            DBSCatalog sample = catalog != null ? catalog : defaultCatalog != null ? defaultCatalog : catalogs.isEmpty() ? null : catalogs.getFirst();
            boolean usesSchema = sample != null && DBSSchema.class.isAssignableFrom(sample.getPrimaryChildType(monitor));
            List<String> schemas = withSchemas && usesSchema && catalog != null ? names(filter(catalog.getChildren(monitor), DBSSchema.class)) : List.of();
            return new Lists(true, usesSchema, names(catalogs), schemas);
        }
        if (DBSSchema.class.isAssignableFrom(childType)) {
            return new Lists(false, true, List.of(), withSchemas ? names(filter(root.getChildren(monitor), DBSSchema.class)) : List.of());
        }
        return Lists.NONE;
    }

    @Nullable
    private static DBSCatalog getDefaultCatalog(@NotNull DBRProgressMonitor monitor, @NotNull DBPDataSource dataSource) {
        DBCExecutionContext context = dataSource.getDefaultInstance().getDefaultContext(monitor, false);
        DBCExecutionContextDefaults<?, ?> defaults = context != null ? context.getContextDefaults() : null;
        return defaults != null ? defaults.getDefaultCatalog() : null;
    }

    @NotNull
    private static <T> List<T> filter(@Nullable Collection<? extends DBSObject> objects, @NotNull Class<T> type) {
        List<T> result = new ArrayList<>();
        if (objects != null) {
            for (DBSObject object : objects) {
                if (type.isInstance(object)) {
                    result.add(type.cast(object));
                }
            }
        }
        return result;
    }

    @NotNull
    private static List<String> names(@NotNull List<? extends DBSObject> objects) {
        return objects.stream().map(DBSObject::getName).toList();
    }

    //////////////////////////////////////////////////////////
    // State

    /**
     * Enables the fields the fixed target uses (a field with a value stays enabled so that the value can be removed)
     * and says where the lists come from.
     */
    private void updateState() {
        Source current = source.get();
        boolean usesDatabase = structure == null || structure.usesDatabase();
        boolean usesSchema = structure == null || structure.usesSchema();
        databaseCombo.setEnabled(usesDatabase || !getDatabase().isEmpty());
        schemaCombo.setEnabled(usesSchema || !getSchema().isEmpty());
        databaseCombo.setToolTipText(getToolTip(ToolsMessages.library_editor_database_tip, current,
            usesDatabase ? null : ToolsMessages.library_editor_database_unused));
        schemaCombo.setToolTipText(getToolTip(ToolsMessages.library_editor_schema_tip, current,
            usesSchema ? null : ToolsMessages.library_editor_schema_unused));
    }

    @NotNull
    private static String getToolTip(@NotNull String tip, @NotNull Source source, @Nullable String unused) {
        String note;
        if (unused != null) {
            note = unused;
        } else if (!source.fixed()) {
            note = ToolsMessages.library_editor_list_window;
        } else if (source.container() == null) {
            note = ToolsMessages.library_editor_list_missing;
        } else {
            note = ToolsMessages.library_editor_list_target;
        }
        return "<html>" + CommonUtils.escapeHtml(tip) + "<br>" + CommonUtils.escapeHtml(note) + "</html>"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @NotNull
    private static String getText(@NotNull JComboBox<String> combo) {
        return combo.getEditor().getEditorComponent() instanceof JTextComponent text ? text.getText() : String.valueOf(combo.getEditor().getItem());
    }

    private void setText(@NotNull JComboBox<String> combo, @NotNull String text) {
        updating = true;
        try {
            combo.setSelectedItem(text.isEmpty() ? null : text);
            combo.getEditor().setItem(text);
            // Show the start of a long name
            if (combo.getEditor().getEditorComponent() instanceof JTextComponent editor) {
                editor.setCaretPosition(0);
            }
        } finally {
            updating = false;
        }
    }

    /**
     * Replaces the list, keeping the field's text (also a value that is not in the list).
     */
    private void setItems(@NotNull JComboBox<String> combo, @NotNull List<String> names) {
        String text = getText(combo);
        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>(names.toArray(new String[0]));
        model.setSelectedItem(text.isEmpty() ? null : text);
        updating = true;
        try {
            combo.setModel(model);
            combo.getEditor().setItem(text);
        } finally {
            updating = false;
        }
    }

    /**
     * Database and schema names with the navigator's icon.
     */
    private static final class NameRenderer extends DefaultListCellRenderer {
        private final DBPImage icon;

        NameRenderer(@NotNull DBPImage icon) {
            this.icon = icon;
        }

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            setIcon(value != null ? NodeIcons.getIcon(icon) : null);
            return this;
        }
    }
}
