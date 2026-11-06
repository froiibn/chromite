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
import com.seizia.chromite.ui.swing.internal.ToolsMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPEvaluationContext;
import org.jkiss.dbeaver.model.DBPContextProvider;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.data.DBDDataFilter;
import org.jkiss.dbeaver.model.data.DBDDisplayFormat;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCExecutionContextDefaults;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.model.sql.SQLQuery;
import org.jkiss.dbeaver.model.sql.SQLQueryContainer;
import org.jkiss.dbeaver.model.sql.SQLQueryType;
import org.jkiss.dbeaver.model.sql.SQLScriptElement;
import org.jkiss.dbeaver.model.sql.SQLUtils;
import org.jkiss.dbeaver.model.sql.parser.SQLScriptParser;
import org.jkiss.dbeaver.model.struct.DBSDataContainer;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;
import org.jkiss.dbeaver.runtime.DBInterruptedException;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.runtime.properties.PropertySourceCustom;
import org.jkiss.dbeaver.tools.transfer.DataTransferPipe;
import org.jkiss.dbeaver.tools.transfer.DataTransferSettings;
import org.jkiss.dbeaver.tools.transfer.database.DatabaseProducerSettings;
import org.jkiss.dbeaver.tools.transfer.database.DatabaseTransferProducer;
import org.jkiss.dbeaver.tools.transfer.processor.FailedExportFileCleanerProcessor;
import org.jkiss.dbeaver.tools.transfer.processor.ShowInExplorerEventProcessor;
import org.jkiss.dbeaver.tools.transfer.registry.DataTransferNodeDescriptor;
import org.jkiss.dbeaver.tools.transfer.registry.DataTransferProcessorDescriptor;
import org.jkiss.dbeaver.tools.transfer.registry.DataTransferRegistry;
import org.jkiss.dbeaver.tools.transfer.stream.StreamConsumerSettings;
import org.jkiss.dbeaver.tools.transfer.stream.StreamTransferConsumer;
import org.jkiss.dbeaver.utils.GeneralUtils;
import org.jkiss.dbeaver.utils.PrefUtils;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Export Data 마법사: 테이블·뷰(여러 개) 또는 결과 표의 쿼리를 모델이 등록한 파일 형식(CSV, XLSX, JSON, XML, HTML, Markdown, SQL INSERT, TXT 등)으로 내보낸다.
 *
 * 설계 (SWT {@code DataTransferWizard}의 내보내기 흐름을 Swing으로 옮김):
 * <ul>
 *     <li>원본: Object Explorer에서는 고른 테이블이 있는 스키마(또는 고른 스키마·폴더)의 테이블·뷰 목록에 체크 상자를 두어 여러 개를 고른다
 *     (SSMS "Select Source Tables and Views"). 트리의 단일 선택을 바꾸지 않고 여러 테이블을 고르기 위해서다. 원본마다
 *     {@link DatabaseTransferProducer}(SWT {@code DataExportHandler}와 같음)를 만들어 테이블 전체를 다시 읽는다.
 *     결과 표에서는 그 쿼리의 {@code SQLQueryDataContainer}로 producer를 만들어 쿼리를 다시 실행해 모든 행을 내보낸다(SWT 결과 표 내보내기의 기본값).
 *     다시 실행해도 되는 쿼리만 받는다({@link #canExportQuery}). 결과 뷰어가 서버에서 다시 읽은 필터와 정렬({@link DBDDataFilter}: WHERE, ORDER BY)은
 *     producer에 넘겨 같은 조건으로 내보낸다(SWT {@code ResultSetHandlerMain}이 모델의 데이터 필터를 넘기는 것과 같음). 표 안에서만 한 정렬은
 *     내보내기가 따를 수 없으므로 원본 페이지와 요약에 그렇다고 적는다.</li>
 *     <li>형식: {@link StreamTransferConsumer} 노드에 등록된 처리기 전체(SWT "Choose export format"과 같은 목록, 원본 형식에 맞는 것만)다.
 *     형식별 설정은 처리기 속성을 {@link PropertySourceCustom}에 담아 {@link ConnectionPropertiesTable}로 고친다(SWT {@code StreamConsumerPageSettings}).</li>
 *     <li>출력: {@link StreamConsumerSettings}의 폴더, 파일 이름 패턴(변수), 문자 집합, BOM, 하나의 파일, ZIP, 클립보드, 같은 이름 파일 처리,
 *     끝나면 폴더 열기({@link ShowInExplorerEventProcessor}), 실패한 파일 지우기({@link FailedExportFileCleanerProcessor}) — SWT {@code StreamConsumerPageOutput}.</li>
 *     <li>설정 기억: 폴더·패턴·문자 집합·BOM·형식·폴더 열기는 이 번들 설정 저장소에, 형식별 속성은 실행 중에만 기억한다.</li>
 *     <li>노드 설정은 모델의 저장된 대화상자 설정을 읽는 {@code loadNodeSettings}를 부르지 않고 객체의 기본값에서 시작해 화면 값을 직접 넣는다.
 *     빈 설정을 읽으면 {@link DatabaseProducerSettings}의 새 연결·행 수 세기가 꺼지기 때문이다(그 클래스의 {@code loadSettings}).</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 데이터 전송: 최초 작성 (docs/chromite/swing-data-transfer.md)
 * - 2026-11-06 필터·정렬한 결과 표의 Export Data…가 원래 쿼리(모든 행, 원래 순서)를 내보내던 문제: 뷰어의 서버 필터를 producer에 넘김,
 *   표 안에서만 한 정렬은 알림 (docs/chromite/swing-data-transfer.md)
 * - 2026-11-06 화면 문구를 ToolsMessages로 옮김(OS 언어를 따름)
 */
final class DataExportWizard extends DataTransferWizard {

    private static final String PREF_FOLDER = "dataTransfer.export.folder"; //$NON-NLS-1$
    private static final String PREF_PATTERN = "dataTransfer.export.filePattern"; //$NON-NLS-1$
    private static final String PREF_ENCODING = "dataTransfer.export.encoding"; //$NON-NLS-1$
    private static final String PREF_BOM = "dataTransfer.export.bom"; //$NON-NLS-1$
    private static final String PREF_FORMAT = "dataTransfer.export.format"; //$NON-NLS-1$
    private static final String PREF_OPEN_FOLDER = "dataTransfer.export.openFolder"; //$NON-NLS-1$

    /**
     * Format properties edited in this session, by processor id (the SWT wizard keeps them in its dialog settings).
     */
    private static final Map<String, Map<String, Object>> processorPropertiesHistory = new HashMap<>();

    private static final String[] VARIABLES = {
        StreamTransferConsumer.VARIABLE_TABLE, StreamTransferConsumer.VARIABLE_TIMESTAMP, StreamTransferConsumer.VARIABLE_DATE,
        StreamTransferConsumer.VARIABLE_SCHEMA, StreamTransferConsumer.VARIABLE_DATASOURCE, StreamTransferConsumer.VARIABLE_INDEX
    };

    private record SourceItem(@NotNull DBSDataContainer container, boolean[] selected) {
    }

    private final List<SourceItem> sources = new ArrayList<>();
    @Nullable
    private final SQLQueryContainer query;
    /**
     * The grid's filter and server-side sort, applied to the query when it runs again (null: the query as it is).
     */
    @Nullable
    private final DBDDataFilter queryFilter;
    /**
     * Why the exported rows are not in the grid's order (a sort done in the grid only), or null.
     */
    @Nullable
    private final String orderNote;
    private final DataTransferNodeDescriptor consumerNode;
    private final List<DataTransferProcessorDescriptor> processors;
    @Nullable
    private DataTransferProcessorDescriptor processor;
    @Nullable
    private PropertySourceCustom processorProperties;
    @Nullable
    private String sourceCatalog;
    @Nullable
    private String sourceSchema;

    // Format settings
    private final ConnectionPropertiesTable propertiesTable = new ConnectionPropertiesTable(false);
    private final JComboBox<DBDDisplayFormat> valueFormatCombo = new JComboBox<>(DBDDisplayFormat.values());
    private final JComboBox<StreamConsumerSettings.LobExtractType> lobExtractCombo =
        new JComboBox<>(StreamConsumerSettings.LobExtractType.values());
    private final JComboBox<StreamConsumerSettings.LobEncoding> lobEncodingCombo =
        new JComboBox<>(StreamConsumerSettings.LobEncoding.values());

    // Output
    private final JTextField folderField = new JTextField(30);
    private final JTextField patternField = new JTextField(30);
    private final JComboBox<String> encodingCombo = new JComboBox<>();
    private final JCheckBox bomCheck = new JCheckBox(ToolsMessages.export_output_bom);
    private final JCheckBox singleFileCheck = new JCheckBox(ToolsMessages.export_output_single_file);
    private final JCheckBox compressCheck = new JCheckBox(ToolsMessages.export_output_compress);
    private final JCheckBox clipboardCheck = new JCheckBox(ToolsMessages.export_output_clipboard);
    private final JComboBox<StreamConsumerSettings.DataFileConflictBehavior> conflictCombo =
        new JComboBox<>(StreamConsumerSettings.DataFileConflictBehavior.values());
    private final JCheckBox openFolderCheck = new JCheckBox(ToolsMessages.export_output_open_folder);
    private final JCheckBox deleteOnFailureCheck = new JCheckBox(ToolsMessages.export_output_delete_on_failure, true);

    private DataExportWizard(
        @Nullable Window owner,
        @NotNull List<DBSDataContainer> containers,
        @NotNull Set<DBSDataContainer> selected,
        @Nullable SQLQueryContainer query,
        @Nullable DBDDataFilter queryFilter,
        @Nullable String orderNote
    ) {
        super(owner, ToolsMessages.export_title);
        this.query = query;
        this.queryFilter = queryFilter;
        this.orderNote = orderNote;
        for (DBSDataContainer container : containers) {
            sources.add(new SourceItem(container, new boolean[]{selected.contains(container)}));
        }
        DataTransferRegistry registry = DataTransferRegistry.getInstance();
        consumerNode = registry.getNodeByType(StreamTransferConsumer.class);
        List<DBSObject> sourceObjects = new ArrayList<>(containers);
        processors = consumerNode == null ? List.of() : new ArrayList<>(consumerNode.getAvailableProcessors(sourceObjects));
        // By name: processors of several plugins (XLSX comes from data.office) form one alphabetical list
        processors.sort(Comparator.comparing(DataTransferProcessorDescriptor::getName, String.CASE_INSENSITIVE_ORDER));
        String lastFormat = getPreferences().getString(PREF_FORMAT);
        for (DataTransferProcessorDescriptor descriptor : processors) {
            if (processor == null || descriptor.getId().equals(lastFormat)) {
                processor = descriptor;
            }
        }
        if (query != null) {
            readContextDefaults(query);
        }
        initWizard(List.of(new SourcePage(), new FormatPage(), new FormatSettingsPage(), new OutputPage(), new SummaryPage()));
    }

    //////////////////////////////////////////////////////////
    // Entry points

    /**
     * Whether Object Explorer can export from this object: a table or view, or a container of them.
     */
    static boolean canExport(@Nullable DBSObject object) {
        if (object instanceof DBSDataContainer && object instanceof DBSEntity) {
            return true;
        }
        return DataTransferObjects.isDataContainerParent(object);
    }

    /**
     * Opens the wizard for an Object Explorer object: a table or view (its siblings are listed, it is checked),
     * or a schema, catalog or folder (all its tables and views are listed).
     */
    static void openForObject(@NotNull Component parent, @NotNull DBSObject object) {
        DBSObject container;
        Set<DBSDataContainer> selected = new LinkedHashSet<>();
        if (object instanceof DBSDataContainer dataContainer && object instanceof DBSEntity) {
            selected.add(dataContainer);
            container = object.getParentObject() instanceof DBSObjectContainer parentContainer ? parentContainer : null;
        } else {
            container = DataTransferObjects.isDataContainerParent(object) ? object : null;
        }
        List<DBSDataContainer> containers;
        try {
            containers = container == null ? new ArrayList<>(selected)
                : ProgressDialog.runTask(ToolsMessages.export_title, monitor -> DataTransferObjects.readDataContainers(monitor, container));
        } catch (DBInterruptedException e) {
            return;
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(ToolsMessages.export_title,
                NLS.bind(ToolsMessages.export_error_read_tables, DBUtils.getObjectFullName(object, DBPEvaluationContext.UI)), e);
            return;
        }
        for (DBSDataContainer dataContainer : selected) {
            if (!containers.contains(dataContainer)) {
                containers.addFirst(dataContainer);
            }
        }
        if (containers.isEmpty()) {
            JOptionPane.showMessageDialog(parent, ToolsMessages.export_error_nothing, ToolsMessages.export_title, JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        new DataExportWizard(SwingUtilities.getWindowAncestor(parent), containers, selected, null, null, null).open();
    }

    /**
     * Whether the query of a result grid can be run again to export all its rows: one SELECT statement without INTO
     * or FOR UPDATE (re-running other statements would repeat their changes).
     */
    static boolean canExportQuery(@Nullable DBSDataContainer container) {
        if (!(container instanceof SQLQueryContainer queryContainer)) {
            return false;
        }
        SQLScriptElement element = queryContainer.getQuery();
        // isModifying: SELECT … INTO, data-changing CTEs (SelectMutationVisitor) and SELECT … FOR UPDATE
        if (!(element instanceof SQLQuery sqlQuery) || sqlQuery.getType() != SQLQueryType.SELECT || sqlQuery.isModifying()) {
            return false;
        }
        // A GO batch of several statements is one query (SQLScriptBatches) and the type above is that of its first
        // statement only: count the statements so that "SELECT …; DELETE …" is not run again
        DBPDataSource dataSource = container.getDataSource();
        return dataSource != null && SQLScriptParser.parseScript(dataSource, sqlQuery.getText()).size() == 1;
    }

    /**
     * Opens the wizard for the query of a result grid; the query runs again to export all rows.
     *
     * @param filter    the grid's filter and server-side sort, applied to the query as when the grid read it again
     *                  (SWT exports a result with its model's data filter), or null
     * @param orderNote shown when the grid's order can't be reproduced by the export (sorted in the grid only), or null
     */
    static void openForQuery(
        @NotNull Component parent,
        @NotNull DBSDataContainer container,
        @Nullable DBDDataFilter filter,
        @Nullable String orderNote
    ) {
        if (!canExportQuery(container)) {
            return;
        }
        new DataExportWizard(SwingUtilities.getWindowAncestor(parent), List.of(container), Set.of(container), (SQLQueryContainer) container,
            filter != null && filter.hasFilters() ? new DBDDataFilter(filter) : null, orderNote).open();
    }

    /**
     * The grid's filter and sort applied to the exported query, as SQL ("WHERE …", "ORDER BY …"); empty without one.
     */
    @NotNull
    private List<String> describeQueryFilter() {
        List<String> lines = new ArrayList<>();
        DBPDataSource dataSource = sources.isEmpty() ? null : sources.getFirst().container().getDataSource();
        if (queryFilter == null || dataSource == null) {
            return lines;
        }
        try {
            if (queryFilter.hasConditions()) {
                StringBuilder where = new StringBuilder();
                SQLUtils.appendConditionString(queryFilter, dataSource, null, where, true);
                lines.add("WHERE " + where);
            }
            if (queryFilter.hasOrdering()) {
                StringBuilder order = new StringBuilder();
                SQLUtils.appendOrderString(queryFilter, dataSource, null, false, order);
                lines.add("ORDER BY " + order);
            }
        } catch (DBException e) {
            lines.add(NLS.bind(ToolsMessages.export_filter_line, CommonUtils.notEmpty(queryFilter.getWhere())));
        }
        return lines;
    }

    /**
     * The new connection of the export starts in the editor's default catalog and schema, as the SWT wizard
     * passes the editor context to the producer ({@code DataTransferPagePipes}: {@code setDefaultCatalog/Schema}).
     */
    private void readContextDefaults(@NotNull SQLQueryContainer queryContainer) {
        DBCExecutionContext context = queryContainer instanceof DBPContextProvider provider ? provider.getExecutionContext() : null;
        DBCExecutionContextDefaults<?, ?> defaults = context == null ? null : context.getContextDefaults();
        if (defaults != null) {
            sourceCatalog = defaults.getDefaultCatalog() == null ? null : defaults.getDefaultCatalog().getName();
            sourceSchema = defaults.getDefaultSchema() == null ? null : defaults.getDefaultSchema().getName();
        }
    }

    //////////////////////////////////////////////////////////
    // Transfer

    @Nullable
    @Override
    protected DataTransferSettings prepareTransfer() {
        if (consumerNode == null || processor == null || processorProperties == null) {
            return null;
        }
        List<DatabaseTransferProducer> producers = new ArrayList<>();
        for (SourceItem item : sources) {
            if (item.selected()[0]) {
                DatabaseTransferProducer producer = new DatabaseTransferProducer(item.container(), queryFilter);
                producer.setDefaultCatalog(sourceCatalog);
                producer.setDefaultSchema(sourceSchema);
                producers.add(producer);
            }
        }
        DataTransferSettings settings = createSettings(producers, null, true);
        settings.selectConsumer(consumerNode, processor, true);
        Map<String, Object> properties = processorProperties.getPropertiesWithDefaults();
        settings.setProcessorProperties(properties);
        processorPropertiesHistory.put(processor.getId(), new HashMap<>(processorProperties.getPropertyValues()));

        try {
            if (settings.getNodeSettings(producers.getFirst()) instanceof DatabaseProducerSettings producerSettings) {
                // Field defaults of the SWT wizard's first run: own connection (isolated), row count for the progress
                producerSettings.setOpenNewConnections(true);
                producerSettings.setQueryRowCount(true);
            }
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(ToolsMessages.export_title, ToolsMessages.export_error_configure, e);
            return null;
        }
        if (!(settings.getNodeSettings(consumerNode) instanceof StreamConsumerSettings output)) {
            return null;
        }
        output.setValueFormat((DBDDisplayFormat) valueFormatCombo.getSelectedItem());
        output.setLobExtractType((StreamConsumerSettings.LobExtractType) lobExtractCombo.getSelectedItem());
        output.setLobEncoding((StreamConsumerSettings.LobEncoding) lobEncodingCombo.getSelectedItem());
        output.setOutputClipboard(isClipboardOutput());
        output.setOutputFolder(folderField.getText().trim());
        output.setOutputFilePattern(patternField.getText().trim());
        output.setOutputEncoding(getEncoding());
        output.setOutputEncodingBOM(bomCheck.isSelected() && bomCheck.isEnabled());
        output.setUseSingleFile(singleFileCheck.isSelected() && singleFileCheck.isEnabled());
        output.setCompressResults(compressCheck.isSelected() && compressCheck.isEnabled());
        output.setDataFileConflictBehavior((StreamConsumerSettings.DataFileConflictBehavior) conflictCombo.getSelectedItem());
        output.setBlobFileConflictBehavior(StreamConsumerSettings.BlobFileConflictBehavior.PATCHNAME);
        if (openFolderCheck.isSelected() && !isClipboardOutput()) {
            output.getEventProcessorSettings(ShowInExplorerEventProcessor.ID);
        }
        if (deleteOnFailureCheck.isSelected()) {
            output.getEventProcessorSettings(FailedExportFileCleanerProcessor.ID);
        }
        saveOutputPreferences();
        return settings;
    }

    @NotNull
    @Override
    protected String describeSource(@NotNull DataTransferPipe pipe) {
        return pipe.getProducer() == null ? "?" : CommonUtils.truncateString(pipe.getProducer().getObjectName(), 200);
    }

    @NotNull
    @Override
    protected String describeTarget(@NotNull DataTransferPipe pipe) {
        if (isClipboardOutput()) {
            return ToolsMessages.export_target_clipboard;
        }
        return folderField.getText().trim();
    }

    @Nullable
    @Override
    protected String describeResult(@NotNull DataTransferPipe pipe) {
        if (pipe.getConsumer() instanceof StreamTransferConsumer consumer && !consumer.getOutputFiles().isEmpty()) {
            return consumer.getOutputFiles().stream().map(Path::toString).reduce((a, b) -> a + ", " + b).orElse(null);
        }
        return null;
    }

    private boolean isClipboardOutput() {
        return clipboardCheck.isSelected() && clipboardCheck.isEnabled();
    }

    @NotNull
    private String getEncoding() {
        Object selected = encodingCombo.getEditor().getItem();
        String encoding = selected == null ? "" : selected.toString().trim();
        return encoding.isEmpty() ? GeneralUtils.getDefaultFileEncoding() : encoding;
    }

    private int getSelectedCount() {
        int count = 0;
        for (SourceItem item : sources) {
            if (item.selected()[0]) {
                count++;
            }
        }
        return count;
    }

    @NotNull
    private static DBPPreferenceStore getPreferences() {
        return ChromiteSwingActivator.getInstance().getPreferences();
    }

    private void saveOutputPreferences() {
        DBPPreferenceStore preferences = getPreferences();
        preferences.setValue(PREF_FOLDER, folderField.getText().trim());
        preferences.setValue(PREF_PATTERN, patternField.getText().trim());
        preferences.setValue(PREF_ENCODING, getEncoding());
        preferences.setValue(PREF_BOM, bomCheck.isSelected());
        preferences.setValue(PREF_OPEN_FOLDER, openFolderCheck.isSelected());
        if (processor != null) {
            preferences.setValue(PREF_FORMAT, processor.getId());
        }
        PrefUtils.savePreferenceStore(preferences);
    }

    //////////////////////////////////////////////////////////
    // Pages

    /**
     * Source tables and views with check boxes (SSMS "Select Source Tables and Views"), or the query of a grid.
     */
    private final class SourcePage implements Page {
        private final JPanel panel = new JPanel(new BorderLayout(0, 8));
        private final SourceTableModel model = new SourceTableModel();

        SourcePage() {
            if (query != null) {
                JTextArea text = new JTextArea(CommonUtils.notEmpty(query.getQuery() == null ? null : query.getQuery().getText()));
                text.setEditable(false);
                text.setLineWrap(true);
                text.putClientProperty(FlatClientProperties.STYLE_CLASS, "monospaced"); //$NON-NLS-1$
                JLabel note = DataTransferObjects.createNote(
                    ToolsMessages.export_source_query_note);
                panel.add(note, BorderLayout.NORTH);
                panel.add(new JScrollPane(text), BorderLayout.CENTER);
                List<String> filterLines = describeQueryFilter();
                if (!filterLines.isEmpty() || orderNote != null) {
                    JPanel notes = new JPanel(new GridLayout(0, 1, 0, 4));
                    if (!filterLines.isEmpty()) {
                        notes.add(DataTransferObjects.createNote(NLS.bind(ToolsMessages.export_source_filter_note, String.join(" ", filterLines))));
                    }
                    if (orderNote != null) {
                        notes.add(DataTransferObjects.createNote(orderNote));
                    }
                    panel.add(notes, BorderLayout.SOUTH);
                }
                return;
            }
            JTable table = new JTable(model);
            table.setFillsViewportHeight(true);
            table.getTableHeader().setReorderingAllowed(false);
            table.getColumnModel().getColumn(0).setMaxWidth(40);
            table.getColumnModel().getColumn(1).setPreferredWidth(320);
            table.getColumnModel().getColumn(1).setCellRenderer(new DataTransferObjects.ObjectRenderer());
            JButton selectAll = new JButton(ToolsMessages.export_source_select_all);
            selectAll.addActionListener(e -> model.setAll(true));
            JButton deselectAll = new JButton(ToolsMessages.export_source_deselect_all);
            deselectAll.addActionListener(e -> model.setAll(false));
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            buttons.add(selectAll);
            buttons.add(deselectAll);
            panel.add(new JScrollPane(table), BorderLayout.CENTER);
            panel.add(buttons, BorderLayout.SOUTH);
        }

        @NotNull
        @Override
        public String getTitle() {
            return query != null ? ToolsMessages.export_source_query_title : ToolsMessages.export_source_tables_title;
        }

        @NotNull
        @Override
        public String getDescription() {
            return query != null ? ToolsMessages.export_source_query_description
                : ToolsMessages.export_source_tables_description;
        }

        @NotNull
        @Override
        public JComponent getComponent() {
            return panel;
        }

        @Nullable
        @Override
        public String leave() {
            return getSelectedCount() == 0 ? ToolsMessages.export_source_error_none : null;
        }
    }

    private final class SourceTableModel extends AbstractTableModel {
        private final String[] columns = {"", ToolsMessages.export_source_column_source, ToolsMessages.export_source_column_type};

        void setAll(boolean selected) {
            for (SourceItem item : sources) {
                item.selected()[0] = selected;
            }
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return sources.size();
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
            return columnIndex == 0 ? Boolean.class : Object.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 0;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            SourceItem item = sources.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> item.selected()[0];
                case 1 -> item.container();
                default -> item.container() instanceof DBSEntity entity ? entity.getEntityType().getName() : "";
            };
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            if (columnIndex == 0) {
                sources.get(rowIndex).selected()[0] = Boolean.TRUE.equals(value);
            }
        }
    }

    /**
     * Export format (SSMS "Choose a Destination"): the processors registered for the stream consumer.
     */
    private final class FormatPage implements Page {
        private final JPanel panel = new JPanel(new BorderLayout(0, 8));
        private final JList<DataTransferProcessorDescriptor> list = new JList<>(processors.toArray(new DataTransferProcessorDescriptor[0]));

        FormatPage() {
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setCellRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                    super.getListCellRendererComponent(l, value, index, isSelected, cellHasFocus);
                    if (value instanceof DataTransferProcessorDescriptor descriptor) {
                        setText("<html><b>" + escape(descriptor.getName()) + "</b>&nbsp;&nbsp;" + escape(CommonUtils.notEmpty(descriptor.getDescription()))
                            + "</html>");
                        setIcon(NodeIcons.getIcon(descriptor.getIcon()));
                        setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
                    }
                    return this;
                }
            });
            list.setSelectedValue(processor, true);
            list.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting() && list.getSelectedValue() != null) {
                    setProcessor(list.getSelectedValue());
                }
            });
            panel.add(new JScrollPane(list), BorderLayout.CENTER);
        }

        @NotNull
        @Override
        public String getTitle() {
            return ToolsMessages.export_format_title;
        }

        @NotNull
        @Override
        public String getDescription() {
            return ToolsMessages.export_format_description;
        }

        @NotNull
        @Override
        public JComponent getComponent() {
            return panel;
        }

        @Nullable
        @Override
        public String leave() {
            return processor == null ? ToolsMessages.export_format_error_none : null;
        }
    }

    private void setProcessor(@NotNull DataTransferProcessorDescriptor descriptor) {
        if (processor != null && processorProperties != null) {
            propertiesTable.stopEditing();
            processorPropertiesHistory.put(processor.getId(), new HashMap<>(processorProperties.getPropertyValues()));
        }
        processor = descriptor;
        processorProperties = null;
    }

    @NotNull
    private PropertySourceCustom getProcessorProperties() {
        if (processorProperties == null && processor != null) {
            processorProperties = new PropertySourceCustom(processor.getProperties(),
                processorPropertiesHistory.getOrDefault(processor.getId(), Map.of()));
        }
        return processorProperties == null ? new PropertySourceCustom() : processorProperties;
    }

    /**
     * Properties of the chosen format, and the value format and binary handling of the stream consumer.
     */
    private final class FormatSettingsPage implements Page {
        private final JPanel panel = new JPanel(new BorderLayout(0, 12));

        FormatSettingsPage() {
            valueFormatCombo.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                    super.getListCellRendererComponent(l, value, index, isSelected, cellHasFocus);
                    if (value instanceof DBDDisplayFormat format) {
                        setText(getValueFormatLabel(format));
                    }
                    return this;
                }
            });
            valueFormatCombo.setSelectedItem(DBDDisplayFormat.UI);
            lobExtractCombo.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                    super.getListCellRendererComponent(l, value, index, isSelected, cellHasFocus);
                    if (value instanceof StreamConsumerSettings.LobExtractType type) {
                        setText(switch (type) {
                            case SKIP -> ToolsMessages.export_lob_skip;
                            case FILES -> ToolsMessages.export_lob_files;
                            case INLINE -> ToolsMessages.export_lob_inline;
                        });
                    }
                    return this;
                }
            });
            lobExtractCombo.setSelectedItem(StreamConsumerSettings.LobExtractType.INLINE);
            lobEncodingCombo.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                    super.getListCellRendererComponent(l, value, index, isSelected, cellHasFocus);
                    if (value instanceof StreamConsumerSettings.LobEncoding encoding) {
                        setText(switch (encoding) {
                            case BASE64 -> ToolsMessages.export_encoding_base64;
                            case HEX -> ToolsMessages.export_encoding_hex;
                            case BINARY -> ToolsMessages.export_encoding_binary;
                            case NATIVE -> ToolsMessages.export_encoding_native;
                        });
                    }
                    return this;
                }
            });
            lobEncodingCombo.setSelectedItem(StreamConsumerSettings.LobEncoding.BINARY);
            lobExtractCombo.addActionListener(e -> lobEncodingCombo.setEnabled(
                lobExtractCombo.getSelectedItem() == StreamConsumerSettings.LobExtractType.INLINE));

            JPanel form = new JPanel(new GridBagLayout());
            int row = 0;
            row = DataTransferObjects.addRow(form, row, ToolsMessages.export_settings_value_format, valueFormatCombo,
                ToolsMessages.export_settings_value_format_note);
            row = DataTransferObjects.addRow(form, row, ToolsMessages.export_settings_lob, lobExtractCombo, ToolsMessages.export_settings_lob_note);
            DataTransferObjects.addRow(form, row, ToolsMessages.export_settings_lob_encoding, lobEncodingCombo,
                ToolsMessages.export_settings_lob_encoding_note);
            panel.add(propertiesTable, BorderLayout.CENTER);
            panel.add(form, BorderLayout.SOUTH);
        }

        @NotNull
        @Override
        public String getTitle() {
            return ToolsMessages.export_settings_title;
        }

        @NotNull
        @Override
        public String getDescription() {
            return processor == null ? ToolsMessages.export_settings_description
                : NLS.bind(ToolsMessages.export_settings_description_format, processor.getName());
        }

        @NotNull
        @Override
        public JComponent getComponent() {
            return panel;
        }

        @Override
        public void activate() {
            propertiesTable.setSource(getProcessorProperties(), null);
        }

        @Nullable
        @Override
        public String leave() {
            propertiesTable.stopEditing();
            return null;
        }
    }

    /**
     * Output folder, file name pattern, encoding and what happens after the export (SWT "Output" page).
     */
    private final class OutputPage implements Page {
        private final JPanel panel = new JPanel(new BorderLayout());

        OutputPage() {
            DBPPreferenceStore preferences = getPreferences();
            String folder = preferences.getString(PREF_FOLDER);
            folderField.setText(CommonUtils.isEmpty(folder) ? System.getProperty("user.home") : folder); //$NON-NLS-1$
            String pattern = preferences.getString(PREF_PATTERN);
            patternField.setText(CommonUtils.isEmpty(pattern)
                ? GeneralUtils.variablePattern(StreamTransferConsumer.VARIABLE_TABLE) + "_" + GeneralUtils.variablePattern(StreamTransferConsumer.VARIABLE_TIMESTAMP)
                : pattern);
            encodingCombo.setEditable(true);
            Set<String> encodings = new LinkedHashSet<>();
            encodings.add(GeneralUtils.getDefaultFileEncoding());
            for (String name : new String[]{"UTF-8", "UTF-16", "UTF-16LE", "UTF-16BE", "ISO-8859-1", "windows-1252", "US-ASCII"}) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$ //$NON-NLS-7$
                encodings.add(name);
            }
            encodings.add(Charset.defaultCharset().name());
            String nativeEncoding = System.getProperty("native.encoding"); //$NON-NLS-1$
            if (!CommonUtils.isEmpty(nativeEncoding)) {
                encodings.add(nativeEncoding);
            }
            encodings.forEach(encodingCombo::addItem);
            String encoding = preferences.getString(PREF_ENCODING);
            encodingCombo.setSelectedItem(CommonUtils.isEmpty(encoding) ? GeneralUtils.getDefaultFileEncoding() : encoding);
            bomCheck.setSelected(preferences.getBoolean(PREF_BOM));
            openFolderCheck.setSelected(preferences.getBoolean(PREF_OPEN_FOLDER));
            conflictCombo.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                    super.getListCellRendererComponent(l, value, index, isSelected, cellHasFocus);
                    if (value instanceof StreamConsumerSettings.DataFileConflictBehavior behavior) {
                        setText(behavior == StreamConsumerSettings.DataFileConflictBehavior.ASK ? ToolsMessages.export_output_conflict_ask : behavior.title);
                    }
                    return this;
                }
            });
            conflictCombo.setSelectedItem(StreamConsumerSettings.DataFileConflictBehavior.ASK);
            clipboardCheck.addActionListener(e -> updateEnablement());

            JButton browse = new JButton(ToolsMessages.button_browse);
            browse.addActionListener(e -> chooseFolder());
            JPanel folderPanel = new JPanel(new BorderLayout(6, 0));
            folderPanel.add(folderField, BorderLayout.CENTER);
            folderPanel.add(browse, BorderLayout.EAST);
            StringBuilder variables = new StringBuilder();
            for (String variable : VARIABLES) {
                variables.append(variables.isEmpty() ? "" : ", ").append(GeneralUtils.variablePattern(variable));
            }

            JPanel form = new JPanel(new GridBagLayout());
            int row = 0;
            row = DataTransferObjects.addRow(form, row, ToolsMessages.export_output_directory, folderPanel, null);
            row = DataTransferObjects.addRow(form, row, ToolsMessages.export_output_pattern, patternField, null);
            row = DataTransferObjects.addRow(form, row, null,
                DataTransferObjects.createNote(NLS.bind(ToolsMessages.export_output_variables, variables)), null);
            row = DataTransferObjects.addRow(form, row, ToolsMessages.export_output_encoding, encodingCombo, null);
            row = DataTransferObjects.addRow(form, row, null, bomCheck, null);
            row = DataTransferObjects.addRow(form, row, ToolsMessages.export_output_conflict, conflictCombo, null);
            row = DataTransferObjects.addRow(form, row, null, singleFileCheck, null);
            row = DataTransferObjects.addRow(form, row, null, compressCheck, null);
            row = DataTransferObjects.addRow(form, row, null, clipboardCheck, null);
            row = DataTransferObjects.addRow(form, row, null, openFolderCheck, null);
            DataTransferObjects.addRow(form, row, null, deleteOnFailureCheck, null);
            panel.add(form, BorderLayout.NORTH);
        }

        private void chooseFolder() {
            JFileChooser chooser = new JFileChooser(folderField.getText().trim());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle(ToolsMessages.export_output_choose_title);
            if (chooser.showDialog(DataExportWizard.this, ToolsMessages.export_output_choose_button) == JFileChooser.APPROVE_OPTION) {
                File folder = chooser.getSelectedFile();
                folderField.setText(folder.getAbsolutePath());
            }
        }

        private void updateEnablement() {
            boolean binary = processor != null && processor.isBinaryFormat();
            boolean appendable = processor != null && processor.isAppendable();
            clipboardCheck.setEnabled(!binary);
            boolean toFile = !isClipboardOutput();
            folderField.setEnabled(toFile);
            patternField.setEnabled(toFile);
            encodingCombo.setEnabled(!binary);
            bomCheck.setEnabled(toFile && !binary);
            conflictCombo.setEnabled(toFile);
            singleFileCheck.setEnabled(toFile && appendable && getSelectedCount() > 1);
            compressCheck.setEnabled(toFile);
            openFolderCheck.setEnabled(toFile);
            deleteOnFailureCheck.setEnabled(toFile);
        }

        @NotNull
        @Override
        public String getTitle() {
            return ToolsMessages.export_output_title;
        }

        @NotNull
        @Override
        public String getDescription() {
            return ToolsMessages.export_output_description;
        }

        @NotNull
        @Override
        public JComponent getComponent() {
            return panel;
        }

        @Override
        public void activate() {
            updateEnablement();
        }

        @Nullable
        @Override
        public String leave() {
            if (!isClipboardOutput()) {
                if (folderField.getText().isBlank()) {
                    return ToolsMessages.export_output_error_directory;
                }
                if (patternField.getText().isBlank()) {
                    return ToolsMessages.export_output_error_pattern;
                }
            }
            String encoding = getEncoding();
            if (!Charset.isSupported(encoding)) {
                return NLS.bind(ToolsMessages.export_output_error_encoding, encoding);
            }
            return null;
        }
    }

    /**
     * What will be done (SSMS "Complete the Wizard").
     */
    private final class SummaryPage implements Page {
        private final JTextArea text = new JTextArea();
        private final JPanel panel = new JPanel(new BorderLayout());

        SummaryPage() {
            text.setEditable(false);
            text.setLineWrap(true);
            text.setWrapStyleWord(true);
            panel.add(new JScrollPane(text), BorderLayout.CENTER);
        }

        @NotNull
        @Override
        public String getTitle() {
            return ToolsMessages.wizard_summary_title;
        }

        @NotNull
        @Override
        public String getDescription() {
            return ToolsMessages.export_summary_description;
        }

        @NotNull
        @Override
        public JComponent getComponent() {
            return panel;
        }

        @Override
        public void activate() {
            StringBuilder summary = new StringBuilder();
            summary.append(ToolsMessages.export_summary_source).append('\n');
            for (SourceItem item : sources) {
                if (item.selected()[0]) {
                    summary.append("    ").append(CommonUtils.truncateString(DBUtils.getObjectFullName(item.container(), DBPEvaluationContext.UI), 300)).append('\n');
                }
            }
            if (query != null) {
                summary.append("    ").append(ToolsMessages.export_summary_query_rerun).append('\n');
                for (String line : describeQueryFilter()) {
                    summary.append("    ").append(line).append('\n');
                }
                if (orderNote != null) {
                    summary.append("    ").append(orderNote).append('\n');
                }
            }
            summary.append('\n').append(NLS.bind(ToolsMessages.export_summary_format, processor == null ? "?" : processor.getName())).append('\n');
            Map<String, Object> changed = getProcessorProperties().getPropertyValues();
            for (Map.Entry<String, Object> entry : changed.entrySet()) {
                summary.append("    ").append(entry.getKey()).append(" = ").append(entry.getValue()).append('\n');
            }
            if (valueFormatCombo.getSelectedItem() instanceof DBDDisplayFormat format) {
                summary.append(NLS.bind(ToolsMessages.export_summary_value_format, getValueFormatLabel(format))).append('\n');
            }
            summary.append('\n').append(ToolsMessages.export_summary_output).append(' ');
            if (isClipboardOutput()) {
                summary.append(ToolsMessages.export_summary_clipboard).append('\n');
            } else {
                summary.append(Path.of(folderField.getText().trim()).resolve(patternField.getText().trim()))
                    .append(processor == null ? "" : "." + CommonUtils.notEmpty(CommonUtils.toString(
                        getProcessorProperties().getPropertiesWithDefaults().get(StreamConsumerSettings.PROP_FILE_EXTENSION))))
                    .append('\n');
                summary.append(NLS.bind(bomCheck.isSelected() && bomCheck.isEnabled()
                        ? ToolsMessages.export_summary_encoding_bom : ToolsMessages.export_summary_encoding,
                    getEncoding())).append('\n');
                if (singleFileCheck.isSelected() && singleFileCheck.isEnabled()) {
                    summary.append(ToolsMessages.export_summary_single_file).append('\n');
                }
                if (compressCheck.isSelected()) {
                    summary.append(ToolsMessages.export_summary_compress).append('\n');
                }
                if (openFolderCheck.isSelected()) {
                    summary.append(ToolsMessages.export_summary_open_folder).append('\n');
                }
            }
            text.setText(summary.toString());
            text.setCaretPosition(0);
        }
    }

    @NotNull
    private static String getValueFormatLabel(@NotNull DBDDisplayFormat format) {
        return switch (format) {
            case UI -> ToolsMessages.export_value_format_display;
            case EDIT -> ToolsMessages.export_value_format_edit;
            case NATIVE -> ToolsMessages.export_value_format_native;
        };
    }

    @NotNull
    private static String escape(@NotNull String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
    }
}
