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
import com.seizia.chromite.ui.swing.internal.DbCopyMessages;
import com.seizia.chromite.ui.swing.internal.ToolsMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.runtime.MonitorRunnableContext;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import com.seizia.chromite.model.struct.DBSAttributeBase;
import com.seizia.chromite.model.struct.DBSDataManipulator;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectContainer;
import com.seizia.chromite.runtime.DBInterruptedException;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.properties.PropertySourceCustom;
import com.seizia.chromite.tools.transfer.DataTransferPipe;
import com.seizia.chromite.tools.transfer.DataTransferSettings;
import com.seizia.chromite.tools.transfer.database.DatabaseConsumerSettings;
import com.seizia.chromite.tools.transfer.database.DatabaseMappingAttribute;
import com.seizia.chromite.tools.transfer.database.DatabaseMappingContainer;
import com.seizia.chromite.tools.transfer.database.DatabaseMappingType;
import com.seizia.chromite.tools.transfer.database.DatabaseTransferConsumer;
import com.seizia.chromite.tools.transfer.registry.DataTransferNodeDescriptor;
import com.seizia.chromite.tools.transfer.registry.DataTransferProcessorDescriptor;
import com.seizia.chromite.tools.transfer.registry.DataTransferRegistry;
import com.seizia.chromite.tools.transfer.stream.StreamEntityMapping;
import com.seizia.chromite.tools.transfer.stream.StreamProducerSettings;
import com.seizia.chromite.tools.transfer.stream.StreamTransferProducer;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Import Data 마법사: CSV 파일을 이미 있는 테이블에 넣는다(SSMS Import Data / Import Flat File).
 *
 * 설계 (SWT {@code DataImportHandler} → {@code DataTransferWizard}의 가져오기 흐름):
 * <ul>
 *     <li>원본은 {@link StreamTransferProducer}(파일 하나, {@link StreamEntityMapping}), 대상은 테이블의 {@link DatabaseTransferConsumer}다.
 *     형식은 모델이 등록한 가져오기 처리기(파일 producer 노드의 처리기, 지금은 CSV만)에서 고른다. XLSX 가져오기는 모델에 처리기가 없어 다루지 않는다.</li>
 *     <li>파일 설정(문자 집합, 구분자, 머리글, 따옴표, NULL 문자열, 날짜 형식 등)은 처리기 속성이다. SWT {@code StreamProducerPageSettings}처럼
 *     {@link PropertySourceCustom}에 담아 고친다. 파일 앞부분을 그 문자 집합으로 읽어 그대로 보여 준다.</li>
 *     <li>열 매핑: 파일 단계를 떠날 때 SWT와 같이 {@link StreamProducerSettings#updateProducerSettingsFromStream}으로 파일의 열을 읽고,
 *     {@link DatabaseMappingContainer}를 테이블에 묶어 {@code existing}으로 자동 매핑한다(이름, 머리글이 없으면 순서).
 *     테이블에 맞는 열이 없는 원본 열은 모델 기본값(새 열 만들기 = ALTER TABLE)이 아니라 건너뛰기로 둔다. SSMS처럼 기존 테이블의 구조는 바꾸지 않기 위해서다.
 *     사용자는 열마다 대상 열 또는 {@code [skip]}을 고른다.</li>
 *     <li>넣기 설정은 {@link DatabaseConsumerSettings}(테이블 비우기, 트랜잭션과 커밋 간격 = 배치 크기, 여러 행 INSERT, 배치 끄기, 새 연결)다.
 *     오류 처리는 모델 그대로: 넣다가 오류가 나면 Stop / Retry / Skip / Skip All을 묻는다({@link SwingPlatformUI#showErrorStopRetryIgnore}).</li>
 *     <li>Swing 제품에는 객체 편집기가 없어 끝난 뒤 테이블 열기({@code openTableOnFinish})는 끈다.</li>
 *     <li>데이터베이스 노드에서 열면 첫 단계에서 대상(스키마와 기존 테이블, 또는 새 테이블 이름)을 고른다({@link DataImportTargetPage}).
 *     소비자는 SWT {@code DataImportHandler}가 컨테이너에 하듯 {@code new DatabaseTransferConsumer(컨테이너)}다. 새 테이블은 파일의 열을 읽은 뒤
 *     테이블 복사와 같은 {@link DatabaseCopyMappings#setTableTarget}으로 매핑(create)을 만들어 모델이 실행 때 테이블을 만든다(직접 DDL을 쓰지 않음).
 *     새 테이블의 열 단계에서는 열 이름·형식을 고치고(형식 목록은 {@link DatabaseCopyMappings#getTargetTypes}) 대상 DDL을 미리 본다.
 *     이름을 비워 두면 SWT {@code StreamProducerPageSettings.generateTableName}처럼 파일 이름에서 만든다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 데이터 전송: 최초 작성 (docs/chromite/swing-data-transfer.md)
 * - 2026-11-06 화면 문구를 ToolsMessages로 옮김(OS 언어를 따름)
 * - 2026-11-06 데이터베이스 노드에서 Import Data…: 대상 테이블 고르기, 새 테이블로 가져오기 (docs/chromite/swing-data-transfer.md "데이터베이스 노드")
 * - 2026-11-07 오류 문구를 {@link ErrorText}로 만듦(클래스 이름이 화면에 나오지 않게, docs/chromite/swing-frontend.md "오류 문구")
 */
final class DataImportWizard extends DataTransferWizard {

    private static final int PREVIEW_LINES = 20;
    private static final String PROP_ENCODING = "encoding"; //$NON-NLS-1$
    private static final String PROP_EXTENSION = "extension"; //$NON-NLS-1$

    /**
     * File format properties edited in this session, by processor id.
     */
    private static final Map<String, Map<String, Object>> processorPropertiesHistory = new HashMap<>();
    @Nullable
    private static Path lastDirectory;

    /**
     * Result of reading the file: its producer, the mapping to the table, and the table's columns.
     */
    private record LoadedMapping(
        @NotNull StreamTransferProducer producer,
        @NotNull DatabaseMappingContainer mapping,
        @NotNull List<DatabaseMappingAttribute> attributes,
        @NotNull List<DBSEntityAttribute> columns
    ) {
    }

    /**
     * The table that receives the rows; null for a new table (opened for a database, see {@link DataImportTargetPage}).
     */
    @Nullable
    private DBSDataManipulator table;
    /**
     * Opened for a database: the chosen schema (or database) of the destination table.
     */
    @Nullable
    private DBSObjectContainer targetContainer;
    /**
     * Opened for a database: the typed name of a new table, empty for the file's name.
     */
    private String newTableName = ""; //$NON-NLS-1$
    private final DataTransferSettings settings;
    private final DataTransferNodeDescriptor producerNode;
    private final DatabaseConsumerSettings consumerSettings;
    private final StreamProducerSettings producerSettings;
    private final List<DataTransferProcessorDescriptor> importers;
    private DataTransferProcessorDescriptor importer;
    private PropertySourceCustom importerProperties;
    @Nullable
    private DatabaseMappingContainer mapping;
    private final List<DBSEntityAttribute> targetAttributes = new ArrayList<>();
    private final List<DatabaseMappingAttribute> attributeMappings = new ArrayList<>();
    /**
     * Was the last read mapped to a table that exists (existing columns), or to a new table (create)?
     */
    private boolean creating;

    // Source
    private final JTextField fileField = new JTextField(30);
    private final JComboBox<DataTransferProcessorDescriptor> importerCombo = new JComboBox<>();
    private final ConnectionPropertiesTable propertiesTable = new ConnectionPropertiesTable(false);
    private final JTextArea previewArea = new JTextArea(8, 40);

    // Load settings
    private final JCheckBox truncateCheck = new JCheckBox(ToolsMessages.import_load_truncate);
    private final JCheckBox newConnectionCheck = new JCheckBox(ToolsMessages.import_load_new_connection, true);
    private final JCheckBox transactionsCheck = new JCheckBox(ToolsMessages.import_load_transactions, true);
    private final JSpinner commitSpinner = new JSpinner(new SpinnerNumberModel(10000, 1, Integer.MAX_VALUE, 1000));
    private final JCheckBox multiRowCheck = new JCheckBox(ToolsMessages.import_load_multi_row);
    private final JSpinner multiRowSpinner = new JSpinner(new SpinnerNumberModel(500, 2, 100000, 100));
    private final JCheckBox disableBatchesCheck = new JCheckBox(ToolsMessages.import_load_disable_batches);

    private DataImportWizard(
        @Nullable Window owner,
        @Nullable DBSDataManipulator table,
        @Nullable DataImportTargetPage.Targets targets,
        @NotNull DataTransferNodeDescriptor producerNode,
        @NotNull List<DataTransferProcessorDescriptor> importers
    ) throws DBException {
        super(owner, ToolsMessages.import_title);
        this.table = table;
        this.producerNode = producerNode;
        this.importers = importers;
        // A container consumer as SWT DataImportHandler makes for a schema: the mapping gives the table (existing or new)
        DatabaseTransferConsumer consumer = table != null ? new DatabaseTransferConsumer(table)
            : new DatabaseTransferConsumer(targets == null ? null : targets.database());
        settings = createSettings(null, List.of(consumer), false);
        DataTransferNodeDescriptor consumerNode = DataTransferRegistry.getInstance().getNodeByType(DatabaseTransferConsumer.class);
        if (consumerNode == null || !(settings.getNodeSettings(consumerNode) instanceof DatabaseConsumerSettings dcs)
            || !(settings.getNodeSettings(producerNode) instanceof StreamProducerSettings sps)) {
            throw new DBException("Data import is not available");
        }
        consumerSettings = dcs;
        producerSettings = sps;
        if (table != null && table.getParentObject() instanceof DBSObjectContainer container) {
            consumerSettings.setContainer(container);
        }
        consumerSettings.setOpenTableOnFinish(false);
        setImporter(importers.getFirst());
        List<Page> pages = new ArrayList<>();
        if (targets != null) {
            pages.add(new DataImportTargetPage(this, targets, this::setTarget));
        }
        pages.addAll(List.of(new SourcePage(), new MappingPage(), new LoadSettingsPage(), new SummaryPage()));
        initWizard(pages);
    }

    /**
     * Whether a table can receive imported data (SWT {@code DataImportHandler}: a {@link DBSDataManipulator}), or a
     * database whose tables (or a new table) can be chosen on the first page.
     */
    static boolean canImport(@Nullable DBSObject object) {
        return object instanceof DBSDataManipulator && object instanceof DBSEntity || DataTransferObjects.isDatabase(object);
    }

    static void open(@NotNull Component parent, @NotNull DBSObject object) {
        DBSDataManipulator table = object instanceof DBSDataManipulator manipulator && object instanceof DBSEntity ? manipulator : null;
        if (table == null && !(DataTransferObjects.isDatabase(object) && object instanceof DBSObjectContainer)) {
            return;
        }
        DataTransferNodeDescriptor producerNode = DataTransferRegistry.getInstance().getNodeByType(StreamTransferProducer.class);
        List<DataTransferProcessorDescriptor> importers = new ArrayList<>();
        if (producerNode != null) {
            for (DataTransferProcessorDescriptor descriptor : producerNode.getProcessors()) {
                importers.add(descriptor);
            }
        }
        if (producerNode == null || importers.isEmpty()) {
            DBWorkbench.getPlatformUI().showError(ToolsMessages.import_title, ToolsMessages.import_error_no_importers);
            return;
        }
        DataImportTargetPage.Targets targets = null;
        if (table == null) {
            DBSObjectContainer database = (DBSObjectContainer) object;
            try {
                targets = ProgressDialog.runTask(ToolsMessages.import_title, monitor -> DataImportTargetPage.readTargets(monitor, database));
            } catch (DBInterruptedException e) {
                return;
            } catch (DBException e) {
                DBWorkbench.getPlatformUI().showError(ToolsMessages.import_title, NLS.bind(DbCopyMessages.error_read, database.getName()), e);
                return;
            }
            if (targets.containers().isEmpty()) {
                JOptionPane.showMessageDialog(parent, NLS.bind(DbCopyMessages.dbnode_import_error_no_schemas, database.getName()),
                    ToolsMessages.import_title, JOptionPane.INFORMATION_MESSAGE);
                return;
            }
        }
        try {
            new DataImportWizard(SwingUtilities.getWindowAncestor(parent), table, targets, producerNode, importers).open();
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(ToolsMessages.import_title, ToolsMessages.import_error_start, e);
        }
    }

    private void setImporter(@NotNull DataTransferProcessorDescriptor descriptor) {
        if (importer != null && importerProperties != null) {
            propertiesTable.stopEditing();
            processorPropertiesHistory.put(importer.getId(), new HashMap<>(importerProperties.getPropertyValues()));
        }
        importer = descriptor;
        importerProperties = new PropertySourceCustom(descriptor.getProperties(),
            processorPropertiesHistory.getOrDefault(descriptor.getId(), Map.of()));
        // Same as the SWT wizard's pipe page: the producer node and its processor
        settings.selectProducer(producerNode, descriptor, true);
        mapping = null;
    }

    /**
     * The destination chosen on the first page (opened for a database); the file is read again for it.
     */
    private void setTarget(@NotNull DBSObjectContainer container, @Nullable DBSDataManipulator existingTable, @NotNull String name) {
        targetContainer = container;
        table = existingTable;
        newTableName = name;
        consumerSettings.setContainer(container);
        mapping = null;
    }

    @NotNull
    private String getTableName() {
        if (table != null) {
            return DBUtils.getObjectFullName(table, DBPEvaluationContext.UI);
        }
        if (mapping != null && mapping.getTarget() != null) {
            return DBUtils.getObjectFullName(mapping.getTarget(), DBPEvaluationContext.UI);
        }
        String containerName = targetContainer == null ? "?" : DBUtils.getObjectFullName(targetContainer, DBPEvaluationContext.UI); //$NON-NLS-1$
        String name = mapping != null ? mapping.getTargetName() : newTableName;
        return CommonUtils.isEmpty(name) ? NLS.bind(DbCopyMessages.dbnode_import_new_table_in, containerName) : containerName + "." + name; //$NON-NLS-1$
    }

    /**
     * A table name made of a file name without its extension, other characters than letters and digits replaced by
     * {@code _} (SWT {@code StreamProducerPageSettings.generateTableName}).
     */
    @NotNull
    private static String getTableNameFromFile(@NotNull Path file) {
        String name = file.getFileName() == null ? "" : file.getFileName().toString(); //$NON-NLS-1$
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        name = name.replaceAll("[^\\p{L}\\p{Nd}]+", "_").replaceAll("^_+|_+$", ""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        return name.isEmpty() ? "import" : name; //$NON-NLS-1$
    }

    //////////////////////////////////////////////////////////
    // Transfer

    @Nullable
    @Override
    protected DataTransferSettings prepareTransfer() {
        if (mapping == null) {
            return null;
        }
        if (truncateCheck.isSelected() && JOptionPane.showConfirmDialog(this,
            NLS.bind(ToolsMessages.import_confirm_truncate, getTableName()),
            getTitle(), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return null;
        }
        consumerSettings.setTruncateBeforeLoad(truncateCheck.isSelected());
        consumerSettings.setOpenNewConnections(newConnectionCheck.isSelected());
        consumerSettings.setUseTransactions(transactionsCheck.isSelected());
        consumerSettings.setCommitAfterRows(((Number) commitSpinner.getValue()).intValue());
        consumerSettings.setUseMultiRowInsert(multiRowCheck.isSelected());
        consumerSettings.setMultiRowInsertBatch(((Number) multiRowSpinner.getValue()).intValue());
        consumerSettings.setDisableUsingBatches(disableBatchesCheck.isSelected());
        processorPropertiesHistory.put(importer.getId(), new HashMap<>(importerProperties.getPropertyValues()));
        return settings;
    }

    @NotNull
    @Override
    protected String describeSource(@NotNull DataTransferPipe pipe) {
        return fileField.getText().trim();
    }

    @NotNull
    @Override
    protected String describeTarget(@NotNull DataTransferPipe pipe) {
        return getTableName();
    }

    /**
     * Reads the file's columns with the chosen settings and maps them to the table (in a job; SWT
     * {@code StreamProducerPageSettings.updateSingleConsumer} and {@code DatabaseConsumerPageMapping}).
     */
    @Nullable
    private String loadMapping() {
        Path file = Path.of(fileField.getText().trim());
        Map<String, Object> properties = importerProperties.getPropertiesWithDefaults();
        try {
            LoadedMapping loaded = ProgressDialog.runTask(NLS.bind(ToolsMessages.import_progress_read, file.getFileName()), monitor -> {
                StreamEntityMapping entityMapping = new StreamEntityMapping(file);
                StreamTransferProducer producer = new StreamTransferProducer(entityMapping, importer);
                producerSettings.setProcessorProperties(properties);
                producerSettings.updateProducerSettingsFromStream(monitor, producer, importer.getInstance(), properties);
                if (entityMapping.getStreamColumns().isEmpty()) {
                    throw new DBException(NLS.bind(ToolsMessages.import_error_no_columns, file.getFileName()));
                }

                consumerSettings.getDataMappings().clear();
                DatabaseMappingContainer newMapping = new DatabaseMappingContainer(consumerSettings, entityMapping);
                DBSDataManipulator target = table;
                if (target == null) {
                    // A new table: the same lookup as Copy Tables (a table of that name that exists receives the rows)
                    String name = newTableName.isEmpty() ? getTableNameFromFile(file) : newTableName;
                    String error = DatabaseCopyMappings.setTableTarget(monitor, consumerSettings, newMapping, name, true, false);
                    if (error != null) {
                        throw new DBException(error);
                    }
                    target = newMapping.getMappingType() == DatabaseMappingType.existing ? newMapping.getTarget() : null;
                } else {
                    newMapping.setTarget(table);
                    newMapping.refreshMappingType(monitor, DatabaseMappingType.existing, false, false);
                }
                List<DatabaseMappingAttribute> attributes = new ArrayList<>(newMapping.getAttributeMappings(monitor));
                List<DBSEntityAttribute> columns = new ArrayList<>();
                if (target != null) {
                    for (DatabaseMappingAttribute attribute : attributes) {
                        if (attribute.getMappingType() != DatabaseMappingType.existing || attribute.getTarget() == null) {
                            // Don't add columns to an existing table (the model would ALTER it): skip unmatched columns
                            attribute.setMappingType(DatabaseMappingType.skip);
                        }
                    }
                    for (DBSEntityAttribute attribute : CommonUtils.safeCollection(((DBSEntity) target).getAttributes(monitor))) {
                        if (!DBUtils.isHiddenObject(attribute) && !DBUtils.isPseudoAttribute(attribute)) {
                            columns.add(attribute);
                        }
                    }
                }
                consumerSettings.addDataMappings(new MonitorRunnableContext(monitor), entityMapping, newMapping);
                return new LoadedMapping(producer, newMapping, attributes, columns);
            });
            for (DataTransferPipe pipe : settings.getDataPipes()) {
                pipe.setProducer(loaded.producer());
            }
            settings.setProcessorProperties(properties);
            mapping = loaded.mapping();
            creating = mapping.getMappingType() == DatabaseMappingType.create;
            attributeMappings.clear();
            attributeMappings.addAll(loaded.attributes());
            targetAttributes.clear();
            targetAttributes.addAll(loaded.columns());
        } catch (DBInterruptedException e) {
            return ToolsMessages.import_error_read_canceled;
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(ToolsMessages.import_title, NLS.bind(ToolsMessages.import_error_read_columns_of, file), e);
            return ToolsMessages.import_error_read_columns;
        }
        return null;
    }

    //////////////////////////////////////////////////////////
    // Pages

    /**
     * File, its format and the format settings, with the first lines of the file (SSMS "Choose a Data Source").
     */
    private final class SourcePage implements Page {
        private final JPanel panel = new JPanel(new BorderLayout(0, 10));

        SourcePage() {
            for (DataTransferProcessorDescriptor descriptor : importers) {
                importerCombo.addItem(descriptor);
            }
            importerCombo.setSelectedItem(importer);
            importerCombo.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    if (value instanceof DataTransferProcessorDescriptor descriptor) {
                        setText(descriptor.getName());
                        setIcon(NodeIcons.getIcon(descriptor.getIcon()));
                    }
                    return this;
                }
            });
            importerCombo.addActionListener(e -> {
                if (importerCombo.getSelectedItem() instanceof DataTransferProcessorDescriptor descriptor && descriptor != importer) {
                    setImporter(descriptor);
                    propertiesTable.setSource(importerProperties, null);
                }
            });
            JButton browse = new JButton(ToolsMessages.button_browse);
            browse.addActionListener(e -> chooseFile());
            JPanel filePanel = new JPanel(new BorderLayout(6, 0));
            filePanel.add(fileField, BorderLayout.CENTER);
            filePanel.add(browse, BorderLayout.EAST);
            JButton refresh = new JButton(ToolsMessages.import_source_refresh_preview);
            refresh.addActionListener(e -> updatePreview());

            JPanel form = new JPanel(new GridBagLayout());
            int row = 0;
            row = DataTransferObjects.addRow(form, row, ToolsMessages.import_source_file, filePanel, null);
            DataTransferObjects.addRow(form, row, ToolsMessages.import_source_format, importerCombo, null);

            previewArea.setEditable(false);
            previewArea.putClientProperty(FlatClientProperties.STYLE_CLASS, "monospaced"); //$NON-NLS-1$
            JPanel previewPanel = new JPanel(new BorderLayout(0, 4));
            JPanel previewHeader = new JPanel(new BorderLayout());
            previewHeader.add(new JLabel(NLS.bind(ToolsMessages.import_source_preview, PREVIEW_LINES)), BorderLayout.CENTER);
            previewHeader.add(refresh, BorderLayout.EAST);
            previewPanel.add(previewHeader, BorderLayout.NORTH);
            previewPanel.add(new JScrollPane(previewArea), BorderLayout.CENTER);

            JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, propertiesTable, previewPanel);
            split.setResizeWeight(0.55);
            split.setBorder(BorderFactory.createEmptyBorder());
            panel.add(form, BorderLayout.NORTH);
            panel.add(split, BorderLayout.CENTER);
        }

        private void chooseFile() {
            JFileChooser chooser = new JFileChooser(lastDirectory == null ? null : lastDirectory.toFile());
            chooser.setDialogTitle(ToolsMessages.import_source_choose_title);
            String extensions = CommonUtils.toString(importerProperties.getPropertiesWithDefaults().get(PROP_EXTENSION));
            String[] extensionList = CommonUtils.isEmpty(extensions) ? new String[0] : extensions.split(","); //$NON-NLS-1$
            if (extensionList.length > 0) {
                chooser.setFileFilter(new FileNameExtensionFilter(importer.getName() + " (" + extensions + ")", extensionList));
            }
            if (chooser.showOpenDialog(DataImportWizard.this) == JFileChooser.APPROVE_OPTION) {
                Path file = chooser.getSelectedFile().toPath();
                lastDirectory = file.toAbsolutePath().getParent();
                fileField.setText(file.toString());
                updatePreview();
            }
        }

        private void updatePreview() {
            propertiesTable.stopEditing();
            String path = fileField.getText().trim();
            if (path.isEmpty()) {
                previewArea.setText("");
                return;
            }
            String encoding = CommonUtils.toString(importerProperties.getPropertiesWithDefaults().get(PROP_ENCODING), "UTF-8"); //$NON-NLS-1$
            StringBuilder text = new StringBuilder();
            try (BufferedReader reader = Files.newBufferedReader(Path.of(path),
                Charset.isSupported(encoding) ? Charset.forName(encoding) : Charset.defaultCharset())) {
                String line;
                for (int i = 0; i < PREVIEW_LINES && (line = reader.readLine()) != null; i++) {
                    text.append(line).append('\n');
                }
            } catch (IOException | RuntimeException e) {
                text.append(NLS.bind(ToolsMessages.import_source_preview_error, ErrorText.describeLine(e)));
            }
            previewArea.setText(text.toString());
            previewArea.setCaretPosition(0);
        }

        @NotNull
        @Override
        public String getTitle() {
            return ToolsMessages.import_source_title;
        }

        @NotNull
        @Override
        public String getDescription() {
            return NLS.bind(ToolsMessages.import_source_description, getTableName());
        }

        @NotNull
        @Override
        public JComponent getComponent() {
            return panel;
        }

        @Override
        public void activate() {
            propertiesTable.setSource(importerProperties, null);
        }

        @Nullable
        @Override
        public String leave() {
            propertiesTable.stopEditing();
            String path = fileField.getText().trim();
            if (path.isEmpty()) {
                return ToolsMessages.import_source_error_none;
            }
            if (!Files.isRegularFile(Path.of(path))) {
                return NLS.bind(ToolsMessages.import_source_error_missing, path);
            }
            return loadMapping();
        }
    }

    /**
     * Source columns and the table columns they go to (SSMS "Column Mappings").
     */
    private final class MappingPage implements Page {
        private final JPanel panel = new JPanel(new BorderLayout(0, 8));
        private final MappingTableModel model = new MappingTableModel();
        private final JTable mappingTable = new JTable(model);
        private final JScrollPane tableScroll = new JScrollPane(mappingTable);
        private final JLabel existingNote = DataTransferObjects.createNote(ToolsMessages.import_mapping_note);
        private final JLabel newNote = DataTransferObjects.createNote(DbCopyMessages.dbnode_import_mapping_note_new);
        /**
         * A new table: its columns and the DDL that creates it (as the Copy Tables mapping page).
         */
        private final JTabbedPane tabs = new JTabbedPane();
        private final JTextArea ddlArea = new JTextArea();

        MappingPage() {
            mappingTable.setFillsViewportHeight(true);
            mappingTable.getTableHeader().setReorderingAllowed(false);
            mappingTable.setRowHeight(Math.max(mappingTable.getRowHeight(), new JComboBox<>().getPreferredSize().height));
            mappingTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE); //$NON-NLS-1$
            ddlArea.setEditable(false);
            ddlArea.putClientProperty(FlatClientProperties.STYLE_CLASS, "monospaced"); //$NON-NLS-1$
            tabs.addChangeListener(e -> {
                if (tabs.getSelectedIndex() == 1) {
                    updateDdl();
                }
            });
            panel.add(existingNote, BorderLayout.NORTH);
            panel.add(tableScroll, BorderLayout.CENTER);
        }

        /**
         * The DDL of the new table, generated when its tab is shown (the model makes the same statements when the import runs).
         */
        private void updateDdl() {
            DatabaseMappingContainer newMapping = mapping;
            if (newMapping == null) {
                return;
            }
            if (mappingTable.isEditing()) {
                mappingTable.getCellEditor().stopCellEditing();
            }
            String text;
            try {
                String ddl = ProgressDialog.runTask(DbCopyMessages.progress_ddl,
                    monitor -> DatabaseCopyMappings.generateTableDdl(monitor, consumerSettings, newMapping));
                text = ddl == null ? DbCopyMessages.ddl_no_changes : ddl;
            } catch (DBException e) {
                text = NLS.bind(DbCopyMessages.ddl_error, ErrorText.describe(e));
            }
            ddlArea.setText(text);
            ddlArea.setCaretPosition(0);
        }

        /**
         * Shows the column table alone (an existing table) or with the DDL tab (a new table).
         */
        private void showLayout() {
            BorderLayout layout = (BorderLayout) panel.getLayout();
            for (Object constraint : new Object[]{BorderLayout.NORTH, BorderLayout.CENTER}) {
                Component component = layout.getLayoutComponent(constraint);
                if (component != null) {
                    panel.remove(component);
                }
            }
            panel.add(creating ? newNote : existingNote, BorderLayout.NORTH);
            if (creating) {
                tabs.removeAll();
                tabs.addTab(DbCopyMessages.mapping_tab_columns, tableScroll);
                tabs.addTab(DbCopyMessages.mapping_tab_ddl, new JScrollPane(ddlArea));
                panel.add(tabs, BorderLayout.CENTER);
            } else {
                panel.add(tableScroll, BorderLayout.CENTER);
            }
            panel.revalidate();
            panel.repaint();
        }

        @NotNull
        @Override
        public String getTitle() {
            return ToolsMessages.import_mapping_title;
        }

        @NotNull
        @Override
        public String getDescription() {
            return NLS.bind(creating ? DbCopyMessages.dbnode_import_mapping_description_new : ToolsMessages.import_mapping_description, getTableName());
        }

        @NotNull
        @Override
        public JComponent getComponent() {
            return panel;
        }

        @Override
        public void activate() {
            showLayout();
            if (creating) {
                mappingTable.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(new JTextField()));
                JComboBox<String> typeCombo = new JComboBox<>();
                typeCombo.setEditable(true);
                mappingTable.getColumnModel().getColumn(3).setCellEditor(new DefaultCellEditor(typeCombo) {
                    @Override
                    public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
                        // The destination database's types (as the Copy Tables column editor)
                        typeCombo.removeAllItems();
                        for (String type : DatabaseCopyMappings.getTargetTypes(consumerSettings, attributeMappings.get(row))) {
                            typeCombo.addItem(type);
                        }
                        return super.getTableCellEditorComponent(table, value, isSelected, row, column);
                    }
                });
                model.fireTableDataChanged();
                return;
            }
            JComboBox<Object> targetCombo = new JComboBox<>();
            targetCombo.addItem(DatabaseMappingAttribute.TARGET_NAME_SKIP);
            for (DBSEntityAttribute attribute : targetAttributes) {
                targetCombo.addItem(attribute);
            }
            targetCombo.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    if (value instanceof DBSEntityAttribute attribute) {
                        setText(attribute.getName() + "  (" + attribute.getFullTypeName() + ")");
                    }
                    return this;
                }
            });
            mappingTable.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(targetCombo));
            model.fireTableDataChanged();
        }

        @Nullable
        @Override
        public String leave() {
            if (mappingTable.isEditing()) {
                mappingTable.getCellEditor().stopCellEditing();
            }
            if (creating) {
                Set<String> names = new HashSet<>();
                for (DatabaseMappingAttribute attribute : attributeMappings) {
                    if (attribute.getMappingType() == DatabaseMappingType.skip) {
                        continue;
                    }
                    String name = CommonUtils.notEmpty(attribute.getTargetName());
                    if (!names.add(name.toLowerCase(Locale.ROOT))) {
                        return NLS.bind(DbCopyMessages.dbnode_import_mapping_error_same_column, name);
                    }
                }
                return names.isEmpty() ? DbCopyMessages.dbnode_import_mapping_error_none : null;
            }
            Set<DBSEntityAttribute> used = new HashSet<>();
            for (DatabaseMappingAttribute attribute : attributeMappings) {
                if (attribute.getMappingType() == DatabaseMappingType.existing && attribute.getTarget() != null
                    && !used.add(attribute.getTarget())) {
                    return NLS.bind(ToolsMessages.import_mapping_error_duplicate, attribute.getTarget().getName());
                }
            }
            return used.isEmpty() ? ToolsMessages.import_mapping_error_none : null;
        }
    }

    private final class MappingTableModel extends AbstractTableModel {
        private final String[] columns = {ToolsMessages.import_mapping_column_source, ToolsMessages.import_mapping_column_source_type,
            ToolsMessages.import_mapping_column_target, ToolsMessages.import_mapping_column_target_type};

        @Override
        public int getRowCount() {
            return attributeMappings.size();
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
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 2
                || creating && columnIndex == 3 && attributeMappings.get(rowIndex).getMappingType() == DatabaseMappingType.create;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            DatabaseMappingAttribute attribute = attributeMappings.get(rowIndex);
            if (creating) {
                // A new table: the column name (or [skip]) and the destination type of the new column
                DBSAttributeBase source = attribute.getSource();
                boolean created = attribute.getMappingType() == DatabaseMappingType.create;
                return switch (columnIndex) {
                    case 0 -> source == null ? "" : source.getName();
                    case 1 -> CommonUtils.notEmpty(attribute.getSourceType());
                    case 2 -> created ? CommonUtils.notEmpty(attribute.getTargetName()) : DatabaseMappingAttribute.TARGET_NAME_SKIP;
                    default -> created ? attribute.getTargetType(consumerSettings.getTargetDataSource(attribute), true) : "";
                };
            }
            boolean mapped = attribute.getMappingType() == DatabaseMappingType.existing && attribute.getTarget() != null;
            DBSAttributeBase source = attribute.getSource();
            return switch (columnIndex) {
                case 0 -> source == null ? "" : source.getName();
                case 1 -> CommonUtils.notEmpty(attribute.getSourceType());
                case 2 -> mapped ? attribute.getTarget().getName() : DatabaseMappingAttribute.TARGET_NAME_SKIP;
                default -> mapped ? attribute.getTarget().getFullTypeName() : "";
            };
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            DatabaseMappingAttribute attribute = attributeMappings.get(rowIndex);
            if (creating) {
                String text = CommonUtils.toString(value).trim();
                if (text.isEmpty()) {
                    return;
                }
                if (columnIndex == 2) {
                    try {
                        // No metadata is read for a new table (Copy Tables does the same for its new columns)
                        DatabaseCopyMappings.setColumnTarget(new VoidProgressMonitor(), consumerSettings, attribute,
                            DatabaseCopyMappings.normalizeTypedName(consumerSettings, attribute, text));
                    } catch (DBException e) {
                        DBWorkbench.getPlatformUI().showError(getTitle(), DbCopyMessages.error_map, e);
                    }
                } else if (columnIndex == 3) {
                    attribute.setTargetType(text);
                }
                fireTableRowsUpdated(rowIndex, rowIndex);
                return;
            }
            if (value instanceof DBSEntityAttribute target) {
                attribute.setTarget(target);
                attribute.setTargetName(target.getName());
                attribute.setMappingType(DatabaseMappingType.existing);
            } else {
                attribute.setTarget(null);
                attribute.setMappingType(DatabaseMappingType.skip);
            }
            fireTableRowsUpdated(rowIndex, rowIndex);
        }
    }

    /**
     * How rows are inserted (SWT "Data load settings").
     */
    private final class LoadSettingsPage implements Page {
        private final JPanel panel = new JPanel(new BorderLayout());

        LoadSettingsPage() {
            transactionsCheck.addActionListener(e -> updateEnablement());
            multiRowCheck.addActionListener(e -> updateEnablement());
            disableBatchesCheck.addActionListener(e -> updateEnablement());
            JPanel form = new JPanel(new GridBagLayout());
            int row = 0;
            row = DataTransferObjects.addRow(form, row, null, truncateCheck, null);
            row = DataTransferObjects.addRow(form, row, null, newConnectionCheck, null);
            row = DataTransferObjects.addRow(form, row, null, transactionsCheck, null);
            row = DataTransferObjects.addRow(form, row, ToolsMessages.import_load_commit, commitSpinner, ToolsMessages.import_load_commit_note);
            row = DataTransferObjects.addRow(form, row, null, multiRowCheck, null);
            row = DataTransferObjects.addRow(form, row, ToolsMessages.import_load_rows_per_insert, multiRowSpinner, null);
            DataTransferObjects.addRow(form, row, null, disableBatchesCheck, null);
            JPanel content = new JPanel(new BorderLayout(0, 12));
            content.add(form, BorderLayout.NORTH);
            content.add(DataTransferObjects.createNote(
                ToolsMessages.import_load_note), BorderLayout.CENTER);
            panel.add(content, BorderLayout.NORTH);
            updateEnablement();
        }

        @Override
        public void activate() {
            // A new table is empty
            truncateCheck.setEnabled(!creating);
            if (creating) {
                truncateCheck.setSelected(false);
            }
        }

        private void updateEnablement() {
            commitSpinner.setEnabled(transactionsCheck.isSelected());
            multiRowSpinner.setEnabled(multiRowCheck.isSelected() && !disableBatchesCheck.isSelected());
            multiRowCheck.setEnabled(!disableBatchesCheck.isSelected());
        }

        @NotNull
        @Override
        public String getTitle() {
            return ToolsMessages.import_load_title;
        }

        @NotNull
        @Override
        public String getDescription() {
            return NLS.bind(ToolsMessages.import_load_description, getTableName());
        }

        @NotNull
        @Override
        public JComponent getComponent() {
            return panel;
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
            return ToolsMessages.import_summary_description;
        }

        @NotNull
        @Override
        public JComponent getComponent() {
            return panel;
        }

        @Override
        public void activate() {
            StringBuilder summary = new StringBuilder();
            summary.append(NLS.bind(ToolsMessages.import_summary_source, fileField.getText().trim(), importer.getName())).append('\n');
            for (Map.Entry<String, Object> entry : importerProperties.getPropertyValues().entrySet()) {
                summary.append("    ").append(entry.getKey()).append(" = ").append(entry.getValue()).append('\n');
            }
            summary.append('\n').append(NLS.bind(creating ? DbCopyMessages.dbnode_import_summary_new_table : ToolsMessages.import_summary_destination,
                getTableName())).append('\n');
            for (DatabaseMappingAttribute attribute : attributeMappings) {
                DBSAttributeBase source = attribute.getSource();
                summary.append("    ").append(source == null ? "?" : source.getName()).append(" -> ");
                if (attribute.getMappingType() == DatabaseMappingType.create) {
                    summary.append(attribute.getTargetName()).append(" (") //$NON-NLS-1$
                        .append(attribute.getTargetType(consumerSettings.getTargetDataSource(attribute), true)).append(')');
                } else {
                    summary.append(attribute.getMappingType() == DatabaseMappingType.existing && attribute.getTarget() != null
                        ? attribute.getTarget().getName() : DatabaseMappingAttribute.TARGET_NAME_SKIP);
                }
                summary.append('\n');
            }
            summary.append('\n');
            if (truncateCheck.isSelected()) {
                summary.append(ToolsMessages.import_summary_truncate).append('\n');
            }
            summary.append(transactionsCheck.isSelected() ? NLS.bind(ToolsMessages.import_summary_commit, commitSpinner.getValue())
                : ToolsMessages.import_summary_no_transactions).append('\n');
            if (multiRowCheck.isSelected() && multiRowCheck.isEnabled()) {
                summary.append(NLS.bind(ToolsMessages.import_summary_multi_row, multiRowSpinner.getValue())).append('\n');
            }
            if (disableBatchesCheck.isSelected()) {
                summary.append(ToolsMessages.import_summary_one_by_one).append('\n');
            }
            text.setText(summary.toString());
            text.setCaretPosition(0);
        }
    }
}
