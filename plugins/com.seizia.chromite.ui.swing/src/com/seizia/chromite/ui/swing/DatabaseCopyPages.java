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
import com.seizia.chromite.ui.swing.internal.TransferOptionsMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBPReferentialIntegrityController;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.DBValueFormatting;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionContextDefaults;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.LoggingProgressMonitor;
import com.seizia.chromite.model.sql.SQLDialectInsertReplaceMethod;
import com.seizia.chromite.model.struct.DBSAttributeBase;
import com.seizia.chromite.model.struct.DBSDataBulkLoader;
import com.seizia.chromite.model.struct.DBSDataContainer;
import com.seizia.chromite.model.struct.DBSDataManipulator;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectContainer;
import com.seizia.chromite.runtime.DBInterruptedException;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.tools.transfer.DataTransferPipe;
import com.seizia.chromite.tools.transfer.DataTransferSettings;
import com.seizia.chromite.tools.transfer.database.DatabaseConsumerSettings;
import com.seizia.chromite.tools.transfer.database.DatabaseMappingAttribute;
import com.seizia.chromite.tools.transfer.database.DatabaseMappingContainer;
import com.seizia.chromite.tools.transfer.database.DatabaseMappingType;
import com.seizia.chromite.tools.transfer.database.DatabaseTransferConsumer;
import com.seizia.chromite.tools.transfer.database.DatabaseTransferProducer;
import com.seizia.chromite.tools.transfer.registry.DataTransferNodeDescriptor;
import com.seizia.chromite.tools.transfer.registry.DataTransferRegistry;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 테이블 복사(데이터 내보내기의 "Database" 대상): 대상 선택, 테이블·열 매핑, 넣기 설정 단계와 그 실행 설정.
 *
 * 설계 (SWT {@code data.transfer.ui}의 {@code DatabaseConsumerPageMapping}·{@code DatabaseConsumerPageLoadSettings}를 Swing으로 옮김):
 * <ul>
 *     <li>이 단계들은 {@link DataExportWizard}에 들어간다. Object Explorer의 Tasks ▸ Copy Tables…는 형식 단계 없이 열고,
 *     Export Data…에서는 형식 목록의 "Database"를 고르면 파일 단계 대신 이 단계가 보인다. 원본 고르기, 요약, 진행 페이지는 내보내기와 같다.</li>
 *     <li>모델 흐름은 SWT와 같다: 원본마다 {@link DatabaseTransferProducer}, 대상 노드는 {@link DatabaseTransferConsumer}
 *     ({@code selectConsumer(노드, null, true)}), 설정은 노드의 {@link DatabaseConsumerSettings}. 매핑은 SWT가 페이지에서 하는 일을
 *     {@link DatabaseCopyMappings}로 옮겨 부르므로 이름 대소문자, 기존 테이블 찾기, 새 테이블의 형식 매핑({@code DBStructUtils.mapTargetDataType})이
 *     SWT와 같다. 매핑이 설정 객체에 묶이므로 설정은 대상 단계를 떠날 때 만들고, 원본 선택이 바뀌면 다시 만든다.</li>
 *     <li>대상: SWT는 탐색기 트리 대화상자로 컨테이너를 고르지만, 여기서는 연결 → 데이터베이스 → 스키마 콤보로 고른다(SSMS "Choose a Destination").
 *     단계 수는 모델의 주 자식 형식으로 정한다(테이블을 담는 컨테이너에서 멈춤: MySQL은 데이터베이스, PostgreSQL·SQL Server는 스키마).</li>
 *     <li>매핑 단계 아래에는 고른 테이블의 열 매핑(대상 열 이름, 새 열의 형식)과 대상 DDL 미리 보기(SWT "Target DDL")를 둔다. 원본 테이블 자신을 대상으로
 *     고르면 다음으로 가지 않는다. 비우기·다시 만들기가 원본을 지우기 때문이다(SWT에는 이 검사가 없다).</li>
 *     <li>건너뛴 테이블의 파이프는 실행 전에 뺀다. 모델은 건너뛴 매핑의 파이프도 원본을 읽고 대상 연결을 열기 때문이다.</li>
 *     <li>대상 컨테이너는 하나다(모델의 {@link DatabaseConsumerSettings}가 컨테이너를 하나만 가짐). 데이터베이스 노드에서 여러 스키마의 테이블을
 *     복사하면 같은 대상 스키마로 가므로, 두 원본이 같은 대상 테이블로 가면 다음으로 가지 않는다.</li>
 *     <li>끝난 뒤 테이블 열기: Swing에는 객체 편집기가 없어 모델의 {@code openTableOnFinish}는 끄고, 성공하면 대상 테이블마다
 *     "상위 1000개 행 선택" 쿼리 창을 연다(최대 {@link #MAX_OPENED_TABLES}개).</li>
 *     <li>고급 옵션(SWT {@code DatabaseConsumerPageLoadSettings}, {@code MappingRulesDialog}, {@code AttributeTransformerSettingsDialog}):
 *     중복 키일 때의 INSERT 변형, 고유 키를 어기는 행 건너뛰기, 값을 문장에 직접 쓰기, 쿼리 관리자 기록, 매핑 규칙, 열 값(NULL·상수·식).
 *     모두 모델의 {@link DatabaseConsumerSettings}와 열 매핑에 넣으므로 작업으로 저장하면 함께 저장된다. 원본 읽기 설정은
 *     {@link DataTransferExtractPage}가 원본 설정에 넣는다(파일 내보내기와 같은 페이지).</li>
 *     <li>행을 지우는 작업(비우기, 삭제 후 다시 만들기)은 대상 DDL 탭과 요약에 보이고, Finish 때 한 번 묻는다(취소가 기본).
 *     다시 만들기는 고를 때도 묻는다. Finish에서 다시 묻는 것은 그 뒤에 바뀐 매핑이나 작업에서 불러온 설정도 실행 전에 확인하게 하기 위해서다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-06 테이블 복사: 최초 작성 (docs/chromite/swing-data-transfer.md)
 * - 2026-11-06 데이터베이스 노드: 여러 스키마의 원본이 같은 대상 테이블로 가는 매핑을 막음 (docs/chromite/swing-data-transfer.md "데이터베이스 노드")
 * - 2026-11-07 오류 문구를 {@link ErrorText}로 만듦(클래스 이름이 화면에 나오지 않게, docs/chromite/swing-frontend.md "오류 문구")
 * - 2026-11-08 TRUNCATE 확인은 잘라내기 / 취소, 다시 만들기 확인은 삭제 후 다시 만들기 / 취소 단추(취소가 기본) (docs/chromite/swing-frontend.md "실행 확인의 기본 단추")
 * - 2026-11-08 고급 옵션: 중복 키 처리, 고유 키 위반 행 건너뛰기, 값 직접 쓰기, 쿼리 관리자 기록, 매핑 규칙, 열 값. 비우기를 대상 DDL 탭에 보이고,
 *   Finish 확인에 비울 테이블과 다시 만들 테이블을 함께 묻는다. 원본 설정은 원본 읽기 설정 페이지로 옮김
 *   (docs/chromite/swing-data-transfer.md "고급 옵션")
 */
final class DatabaseCopyPages {

    private static final Log log = Log.getLog(DatabaseCopyPages.class);

    private static final int SELECT_TOP_ROWS = 1000;
    private static final int MAX_OPENED_TABLES = 5;

    private final DataTransferWizard wizard;
    private final Supplier<List<DBSDataContainer>> sources;
    private final BooleanSupplier applicable;
    @Nullable
    private final ObjectExplorerPanel.QueryWindows queryWindows;
    private final DataTransferNodeDescriptor consumerNode;

    @Nullable
    private DataTransferSettings settings;
    @Nullable
    private DatabaseConsumerSettings consumerSettings;
    private List<DBSDataContainer> settingsSources = List.of();
    /**
     * The container the mappings were made for (they are made again when another one is chosen).
     */
    @Nullable
    private DBSObjectContainer mappedContainer;
    private final List<DatabaseMappingContainer> mappings = new ArrayList<>();
    private final List<String> mappingErrors = new ArrayList<>();

    // Destination
    private final JComboBox<DBPDataSourceContainer> connectionCombo = new JComboBox<>();
    private final JComboBox<DBSObjectContainer> databaseCombo = new JComboBox<>();
    private final JComboBox<DBSObjectContainer> schemaCombo = new JComboBox<>();
    private final JLabel destinationNote = DataTransferObjects.createNote(" ");
    /**
     * The connection's object container when it holds the tables itself (no database or schema level), else null.
     */
    @Nullable
    private DBSObjectContainer rootContainer;
    /**
     * The chosen database when it holds the tables itself (MySQL, MariaDB), else null.
     */
    @Nullable
    private DBSObjectContainer tableDatabase;
    private boolean updatingCombos;

    // Mapping
    private final TableMappingModel tableModel = new TableMappingModel();
    private final JTable tableMappings = new JTable(tableModel);
    private final ColumnMappingModel columnModel = new ColumnMappingModel();
    private final JTable columnMappings = new JTable(columnModel);
    private final JTextArea ddlArea = new JTextArea();
    private final JTabbedPane details = new JTabbedPane();
    private final JComboBox<String> columnNameCombo = new JComboBox<>();
    private final JLabel mappingStatus = new JLabel(" ");
    private final JCheckBox truncateCheck = new JCheckBox(DbCopyMessages.mapping_truncate);
    private final JButton columnValueButton = new JButton(TransferOptionsMessages.transform_button);
    private final List<String> targetTableNames = new ArrayList<>();
    private final List<DBSEntityAttribute> targetColumns = new ArrayList<>();
    @Nullable
    private DatabaseMappingContainer selectedMapping;
    /**
     * Counts {@link #showMapping} calls, to know whether a selection change already showed the mapping.
     */
    private int shownVersion;

    // Load settings
    private final JCheckBox newConnectionCheck = new JCheckBox(DbCopyMessages.load_new_connection, true);
    private final JCheckBox transactionsCheck = new JCheckBox(DbCopyMessages.load_transactions, true);
    private final JSpinner commitSpinner = new JSpinner(new SpinnerNumberModel(10000, 1, Integer.MAX_VALUE, 1000));
    private final JCheckBox multiRowCheck = new JCheckBox(DbCopyMessages.load_multi_row);
    private final JSpinner multiRowSpinner = new JSpinner(new SpinnerNumberModel(500, 2, 100000, 100));
    private final JCheckBox disableBatchesCheck = new JCheckBox(DbCopyMessages.load_disable_batches);
    private final JCheckBox autoGeneratedCheck = new JCheckBox(DbCopyMessages.load_auto_generated, true);
    private final JCheckBox referentialIntegrityCheck = new JCheckBox(DbCopyMessages.load_disable_integrity);
    private final JCheckBox bulkLoadCheck = new JCheckBox(DbCopyMessages.load_bulk_load);
    private final JCheckBox openTablesCheck = new JCheckBox(DbCopyMessages.load_open_tables);
    /**
     * {@link DBSDataManipulator#INSERT_NONE_METHOD} or a {@link SQLDialectInsertReplaceMethod} of the destination dialect.
     */
    private final JComboBox<Object> onDuplicateCombo = new JComboBox<>();
    private final JCheckBox ignoreDuplicatesCheck = new JCheckBox(TransferOptionsMessages.load_ignore_duplicates);
    private final JCheckBox skipBindCheck = new JCheckBox(TransferOptionsMessages.load_skip_bind);
    private final JCheckBox qmLoggingCheck = new JCheckBox(TransferOptionsMessages.load_qm_logging);
    /**
     * Whether the destination binds statement values (only then the values can be written into the statement text instead).
     */
    private boolean destinationBindsValues;

    /**
     * @param sources      the checked source tables and views
     * @param applicable   whether the copy pages are shown (the "Database" destination is chosen)
     * @param queryWindows opens the copied tables when done; null hides that option
     */
    DatabaseCopyPages(
        @NotNull DataTransferWizard wizard,
        @NotNull DataTransferNodeDescriptor consumerNode,
        @NotNull Supplier<List<DBSDataContainer>> sources,
        @NotNull BooleanSupplier applicable,
        @Nullable ObjectExplorerPanel.QueryWindows queryWindows,
        @Nullable DBPDataSourceContainer defaultConnection
    ) {
        this.wizard = wizard;
        this.consumerNode = consumerNode;
        this.sources = sources;
        this.applicable = applicable;
        this.queryWindows = queryWindows;
        for (DBPDataSourceContainer connection : QueryLibraryEditor.getConnections()) {
            connectionCombo.addItem(connection);
        }
        connectionCombo.setSelectedItem(defaultConnection);
    }

    /**
     * The database consumer node, or null if the model has none (then there is no "Database" destination).
     */
    @Nullable
    static DataTransferNodeDescriptor findConsumerNode(@NotNull List<? extends DBSObject> sourceObjects) {
        DataTransferRegistry registry = DataTransferRegistry.getInstance();
        DataTransferNodeDescriptor node = registry.getNodeByType(DatabaseTransferConsumer.class);
        return node != null && registry.getAvailableConsumers(new ArrayList<>(sourceObjects)).contains(node) ? node : null;
    }

    @NotNull
    List<DataTransferWizard.Page> createPages() {
        return List.of(new DestinationPage(), new MappingPage(), new LoadSettingsPage());
    }

    //////////////////////////////////////////////////////////
    // Transfer

    /**
     * Applies the load settings to the transfer prepared by the pages (on Finish), or null if it can't start.
     */
    @Nullable
    DataTransferSettings prepareTransfer() {
        stopEditing();
        if (settings == null || consumerSettings == null) {
            return null;
        }
        if (!confirmDeletions()) {
            return null;
        }
        consumerSettings.setTruncateBeforeLoad(truncateCheck.isSelected());
        consumerSettings.setOpenNewConnections(newConnectionCheck.isSelected());
        consumerSettings.setUseTransactions(transactionsCheck.isSelected());
        consumerSettings.setCommitAfterRows(((Number) commitSpinner.getValue()).intValue());
        consumerSettings.setUseMultiRowInsert(multiRowCheck.isSelected() && multiRowCheck.isEnabled());
        consumerSettings.setMultiRowInsertBatch(((Number) multiRowSpinner.getValue()).intValue());
        consumerSettings.setDisableUsingBatches(disableBatchesCheck.isSelected());
        consumerSettings.setTransferAutoGeneratedColumns(autoGeneratedCheck.isSelected());
        consumerSettings.setDisableReferentialIntegrity(referentialIntegrityCheck.isSelected() && referentialIntegrityCheck.isEnabled());
        consumerSettings.setUseBulkLoad(bulkLoadCheck.isSelected() && bulkLoadCheck.isEnabled());
        consumerSettings.setOnDuplicateKeyInsertMethodId(getOnDuplicateMethodId());
        consumerSettings.setIgnoreDuplicateRows(ignoreDuplicatesCheck.isSelected() && ignoreDuplicatesCheck.isEnabled());
        consumerSettings.setSkipBindValues(skipBindCheck.isSelected() && skipBindCheck.isEnabled());
        consumerSettings.setEnableQmLogging(qmLoggingCheck.isSelected());
        // There is no object editor to open (see the class comment)
        consumerSettings.setOpenTableOnFinish(false);
        // Skipped tables are not run (see the class comment)
        settings.getDataPipes().removeIf(pipe -> {
            DatabaseMappingContainer mapping = getMapping(pipe);
            return mapping == null || mapping.getMappingType() == DatabaseMappingType.skip;
        });
        return settings;
    }

    /**
     * Asks once before the copy deletes rows: the existing tables that are emptied (truncate) and the ones that are
     * dropped and created again. Cancel is the default. Both are also in the destination DDL tab and the summary.
     *
     * @return whether the copy may run
     */
    private boolean confirmDeletions() {
        List<String> truncated = getTruncatedTables();
        List<String> recreated = getRecreatedTables();
        if (truncated.isEmpty() && recreated.isEmpty()) {
            return true;
        }
        StringBuilder message = new StringBuilder();
        if (!truncated.isEmpty()) {
            message.append(NLS.bind(TransferOptionsMessages.confirm_truncate, String.join("\n", truncated))).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!recreated.isEmpty()) {
            message.append(NLS.bind(TransferOptionsMessages.confirm_recreate, String.join("\n", recreated))).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        message.append(TransferOptionsMessages.confirm_question);
        return SwingPlatformUI.confirmChange(wizard, message.toString(), TransferOptionsMessages.confirm_title,
            TransferOptionsMessages.confirm_button, JOptionPane.WARNING_MESSAGE);
    }

    /**
     * Existing destination tables whose rows are deleted before copying (truncate is checked).
     */
    @NotNull
    private List<String> getTruncatedTables() {
        List<String> truncated = new ArrayList<>();
        if (truncateCheck.isSelected()) {
            for (DatabaseMappingContainer mapping : mappings) {
                if (mapping.getMappingType() == DatabaseMappingType.existing && mapping.getTarget() != null) {
                    truncated.add(DBUtils.getObjectFullName(mapping.getTarget(), DBPEvaluationContext.UI));
                }
            }
        }
        return truncated;
    }

    /**
     * Existing destination tables that are dropped and created again.
     */
    @NotNull
    private List<String> getRecreatedTables() {
        List<String> recreated = new ArrayList<>();
        for (DatabaseMappingContainer mapping : mappings) {
            if (mapping.getMappingType() == DatabaseMappingType.recreate && mapping.getTarget() != null) {
                recreated.add(DBUtils.getObjectFullName(mapping.getTarget(), DBPEvaluationContext.UI));
            }
        }
        return recreated;
    }

    /**
     * The chosen INSERT variant for duplicate keys, or null for a plain INSERT (also with bulk load, which ignores it).
     */
    @Nullable
    private String getOnDuplicateMethodId() {
        return onDuplicateCombo.isEnabled() && onDuplicateCombo.getSelectedItem() instanceof SQLDialectInsertReplaceMethod method
            ? method.getId() : null;
    }

    @NotNull
    String describeTarget(@NotNull DataTransferPipe pipe) {
        DatabaseMappingContainer mapping = getMapping(pipe);
        if (mapping == null) {
            return "?";
        }
        if (mapping.getTarget() != null) {
            return DBUtils.getObjectFullName(mapping.getTarget(), DBPEvaluationContext.UI);
        }
        DBSObjectContainer container = consumerSettings == null ? null : consumerSettings.getContainer();
        return container == null ? mapping.getTargetName()
            : DBUtils.getObjectFullName(container, DBPEvaluationContext.UI) + "." + mapping.getTargetName(); //$NON-NLS-1$
    }

    /**
     * Opens the copied tables in query windows ("Select Top 1000 Rows") if that was chosen; called on the EDT after a
     * successful transfer.
     */
    void transferSucceeded() {
        if (queryWindows == null || !openTablesCheck.isSelected()) {
            return;
        }
        List<DBSEntity> tables = new ArrayList<>();
        for (DatabaseMappingContainer mapping : mappings) {
            if (mapping.getMappingType() != DatabaseMappingType.skip && mapping.getTarget() instanceof DBSEntity entity
                && tables.size() < MAX_OPENED_TABLES) {
                tables.add(entity);
            }
        }
        for (DBSEntity table : tables) {
            DBPDataSource dataSource = table.getDataSource();
            if (dataSource == null) {
                continue;
            }
            try {
                String script = ProgressDialog.runTask(DbCopyMessages.load_open_tables,
                    monitor -> ObjectScripts.generateSelect(monitor, table, SELECT_TOP_ROWS));
                queryWindows.openScript(dataSource.getContainer(), script, SELECT_TOP_ROWS, true);
            } catch (DBException e) {
                log.debug("Can't open the copied table " + table.getName(), e);
            }
        }
    }

    /**
     * Destination, tables and options for the wizard summary.
     */
    void appendSummary(@NotNull StringBuilder summary) {
        DBSObjectContainer container = consumerSettings == null ? null : consumerSettings.getContainer();
        if (container == null) {
            return;
        }
        summary.append(NLS.bind(DbCopyMessages.summary_destination, DBUtils.getObjectFullName(container, DBPEvaluationContext.UI),
            container.getDataSource() == null ? "?" : container.getDataSource().getContainer().getName())).append('\n'); //$NON-NLS-1$
        for (DatabaseMappingContainer mapping : mappings) {
            DBSDataContainer source = mapping.getSource();
            summary.append("    ").append(source == null ? "?" : DBUtils.getObjectFullName(source, DBPEvaluationContext.UI)) //$NON-NLS-1$ //$NON-NLS-2$
                .append(" -> ").append(mapping.getTargetName()).append("  (").append(getMappingLabel(mapping.getMappingType())).append(")\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        for (DatabaseMappingContainer mapping : mappings) {
            if (mapping.getMappingType() == DatabaseMappingType.skip) {
                continue;
            }
            for (DatabaseMappingAttribute attribute : mapping.getAttributeMappings(new LoggingProgressMonitor(log))) {
                String value = DataTransferOptionDialogs.describeColumnValue(attribute);
                if (!value.isEmpty() && attribute.getMappingType() != DatabaseMappingType.skip) {
                    summary.append("    ").append(NLS.bind(TransferOptionsMessages.summary_transform, //$NON-NLS-1$
                        mapping.getTargetName(), attribute.getTargetName(), value)).append('\n');
                }
            }
        }
        summary.append('\n');
        List<String> truncated = getTruncatedTables();
        if (!truncated.isEmpty()) {
            summary.append(NLS.bind(TransferOptionsMessages.summary_truncate, String.join(", ", truncated))).append('\n'); //$NON-NLS-1$
        }
        List<String> recreated = getRecreatedTables();
        if (!recreated.isEmpty()) {
            summary.append(NLS.bind(TransferOptionsMessages.summary_recreate, String.join(", ", recreated))).append('\n'); //$NON-NLS-1$
        }
        summary.append(transactionsCheck.isSelected() ? NLS.bind(DbCopyMessages.summary_commit, commitSpinner.getValue())
            : DbCopyMessages.summary_no_transactions).append('\n');
        if (multiRowCheck.isSelected() && multiRowCheck.isEnabled()) {
            summary.append(NLS.bind(DbCopyMessages.summary_multi_row, multiRowSpinner.getValue())).append('\n');
        }
        if (disableBatchesCheck.isSelected()) {
            summary.append(DbCopyMessages.summary_one_by_one).append('\n');
        }
        if (!autoGeneratedCheck.isSelected()) {
            summary.append(DbCopyMessages.summary_no_auto_generated).append('\n');
        }
        if (referentialIntegrityCheck.isSelected() && referentialIntegrityCheck.isEnabled()) {
            summary.append(DbCopyMessages.summary_integrity).append('\n');
        }
        if (bulkLoadCheck.isSelected() && bulkLoadCheck.isEnabled()) {
            summary.append(DbCopyMessages.summary_bulk_load).append('\n');
        }
        if (getOnDuplicateMethodId() != null && onDuplicateCombo.getSelectedItem() instanceof SQLDialectInsertReplaceMethod method) {
            summary.append(NLS.bind(TransferOptionsMessages.summary_on_duplicate, method.getLabel())).append('\n');
        }
        if (ignoreDuplicatesCheck.isSelected() && ignoreDuplicatesCheck.isEnabled()) {
            summary.append(TransferOptionsMessages.summary_ignore_duplicates).append('\n');
        }
        if (skipBindCheck.isSelected() && skipBindCheck.isEnabled()) {
            summary.append(TransferOptionsMessages.summary_skip_bind).append('\n');
        }
        if (qmLoggingCheck.isSelected()) {
            summary.append(TransferOptionsMessages.summary_qm_logging).append('\n');
        }
        if (openTablesCheck.isSelected() && queryWindows != null) {
            summary.append(DbCopyMessages.summary_open_tables).append('\n');
        }
    }

    @Nullable
    private DatabaseMappingContainer getMapping(@NotNull DataTransferPipe pipe) {
        if (consumerSettings != null && pipe.getProducer() != null && pipe.getProducer().getDatabaseObject() instanceof DBSDataContainer source) {
            return consumerSettings.getDataMapping(source);
        }
        return null;
    }

    private void stopEditing() {
        for (JTable table : new JTable[]{tableMappings, columnMappings}) {
            if (table.isEditing()) {
                table.getCellEditor().stopCellEditing();
            }
        }
    }

    @NotNull
    private static String getMappingLabel(@NotNull DatabaseMappingType type) {
        return switch (type) {
            case existing -> DbCopyMessages.mapping_type_existing;
            case create -> DbCopyMessages.mapping_type_create;
            case recreate -> DbCopyMessages.mapping_type_recreate;
            case skip -> DbCopyMessages.mapping_type_skip;
            default -> DbCopyMessages.mapping_type_unspecified;
        };
    }

    //////////////////////////////////////////////////////////
    // Settings and mappings

    /**
     * Makes the transfer settings for the checked sources (again if they changed) and maps them to the chosen
     * container (again if it changed).
     *
     * @return why the destination can't be used, or null
     */
    @Nullable
    private String prepareMappings(@NotNull DBSObjectContainer container) {
        List<DBSDataContainer> current = sources.get();
        if (settings == null || !current.equals(settingsSources)) {
            List<DatabaseTransferProducer> producers = new ArrayList<>();
            for (DBSDataContainer source : current) {
                producers.add(new DatabaseTransferProducer(source, null));
            }
            DataTransferSettings newSettings = wizard.createSettings(producers, null, true);
            newSettings.selectConsumer(consumerNode, null, true);
            if (!(newSettings.getNodeSettings(consumerNode) instanceof DatabaseConsumerSettings newConsumerSettings)) {
                return DbCopyMessages.error_configure;
            }
            // The source settings are set from the wizard's source settings page when the copy runs (DataTransferExtractPage)
            settings = newSettings;
            consumerSettings = newConsumerSettings;
            settingsSources = List.copyOf(current);
            mappedContainer = null;
        }
        if (container == mappedContainer || consumerSettings == null) {
            return null;
        }
        boolean containerChanged = mappedContainer != null;
        DatabaseConsumerSettings target = consumerSettings;
        DataTransferSettings transfer = settings;
        target.setContainer(container);
        try {
            List<String> errors = ProgressDialog.runTask(DbCopyMessages.progress_map,
                monitor -> DatabaseCopyMappings.createMappings(monitor, transfer, target, containerChanged));
            mappingErrors.clear();
            mappingErrors.addAll(errors);
            mappedContainer = container;
        } catch (DBInterruptedException e) {
            return DbCopyMessages.error_canceled;
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(wizard.getTitle(), DbCopyMessages.error_map, e);
            return DbCopyMessages.error_map;
        }
        return null;
    }

    /**
     * Runs a mapping change in a task; the error is shown, and the mapping tables are refreshed.
     */
    private void changeMapping(@NotNull DBRunnable change) {
        try {
            ProgressDialog.runTask(DbCopyMessages.progress_map, monitor -> {
                change.run(monitor);
                return null;
            });
        } catch (DBInterruptedException e) {
            // Keep the mapping as it is
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(wizard.getTitle(), DbCopyMessages.error_map, e);
        }
        // After the cell editor is removed: refreshing during editingStopped would stop the editing again
        SwingUtilities.invokeLater(DatabaseCopyPages.this::refreshMappings);
    }

    private interface DBRunnable {
        void run(@NotNull DBRProgressMonitor monitor) throws DBException;
    }

    //////////////////////////////////////////////////////////
    // Destination page

    /**
     * Connection, database and schema of the copied tables (SSMS "Choose a Destination").
     */
    private final class DestinationPage implements DataTransferWizard.Page {
        private final JPanel panel = new JPanel(new BorderLayout(0, 12));
        private boolean loaded;

        DestinationPage() {
            connectionCombo.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    if (value instanceof DBPDataSourceContainer connection) {
                        setText(connection.getName());
                        setIcon(NodeIcons.getIcon(connection.getDriver().getIcon()));
                    }
                    return this;
                }
            });
            DefaultListCellRenderer containerRenderer = new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    if (value instanceof DBSObject object) {
                        setText(object.getName());
                        setIcon(NodeIcons.getIcon(DBValueFormatting.getObjectImage(object)));
                    } else {
                        setText(" ");
                        setIcon(null);
                    }
                    return this;
                }
            };
            databaseCombo.setRenderer(containerRenderer);
            schemaCombo.setRenderer(containerRenderer);
            connectionCombo.addActionListener(e -> {
                if (!updatingCombos) {
                    loadDatabases();
                }
            });
            databaseCombo.addActionListener(e -> {
                if (!updatingCombos) {
                    loadSchemas();
                }
            });
            schemaCombo.addActionListener(e -> {
                if (!updatingCombos) {
                    updateNote();
                }
            });

            JPanel form = new JPanel(new GridBagLayout());
            int row = 0;
            row = DataTransferObjects.addRow(form, row, DbCopyMessages.destination_connection, connectionCombo, null);
            row = DataTransferObjects.addRow(form, row, DbCopyMessages.destination_database, databaseCombo, null);
            DataTransferObjects.addRow(form, row, DbCopyMessages.destination_schema, schemaCombo, null);
            JPanel content = new JPanel(new BorderLayout(0, 12));
            content.add(form, BorderLayout.NORTH);
            content.add(destinationNote, BorderLayout.CENTER);
            panel.add(content, BorderLayout.NORTH);
        }

        /**
         * Connects if needed and lists the databases (or schemas) of the chosen connection.
         */
        private void loadDatabases() {
            DBPDataSourceContainer connection = (DBPDataSourceContainer) connectionCombo.getSelectedItem();
            rootContainer = null;
            tableDatabase = null;
            setItems(databaseCombo, List.of(), null);
            setItems(schemaCombo, List.of(), null);
            databaseCombo.setEnabled(false);
            if (connection != null) {
                try {
                    DatabaseLevel level = ProgressDialog.runTask(NLS.bind(DbCopyMessages.progress_connect, connection.getName()), monitor -> {
                        DBUtils.initDataSource(monitor, connection, null);
                        DBPDataSource dataSource = connection.getDataSource();
                        DBSObjectContainer root = DBUtils.getAdapter(DBSObjectContainer.class, dataSource);
                        if (root == null) {
                            throw new DBException(NLS.bind(DbCopyMessages.error_no_containers, connection.getName()));
                        }
                        boolean tables = DatabaseCopyMappings.isTableContainer(monitor, root);
                        return new DatabaseLevel(root, tables, tables ? List.of() : DatabaseCopyMappings.readChildContainers(monitor, root));
                    });
                    rootContainer = level.tables() ? level.root() : null;
                    setItems(databaseCombo, level.children(), null);
                    databaseCombo.setEnabled(!level.tables());
                } catch (DBInterruptedException e) {
                    connectionCombo.setSelectedItem(null);
                } catch (DBException e) {
                    DBWorkbench.getPlatformUI().showError(wizard.getTitle(), NLS.bind(DbCopyMessages.error_connect, connection.getName()), e);
                }
            }
            schemaCombo.setEnabled(false);
            updateNote();
        }

        /**
         * Lists the schemas of the chosen database, if it has schemas, and picks the source schema's name or the
         * database's default schema.
         */
        private void loadSchemas() {
            DBSObjectContainer database = (DBSObjectContainer) databaseCombo.getSelectedItem();
            tableDatabase = null;
            setItems(schemaCombo, List.of(), null);
            schemaCombo.setEnabled(false);
            if (database != null) {
                String sourceSchema = getSourceSchemaName();
                try {
                    DatabaseLevel level = ProgressDialog.runTask(NLS.bind(DbCopyMessages.progress_read, database.getName()), monitor -> {
                        boolean tables = DatabaseCopyMappings.isTableContainer(monitor, database);
                        return new DatabaseLevel(database, tables, tables ? List.of() : DatabaseCopyMappings.readChildContainers(monitor, database));
                    });
                    if (level.tables()) {
                        tableDatabase = database;
                    } else {
                        DBSObjectContainer preferred = null;
                        for (DBSObjectContainer schema : level.children()) {
                            if (schema.getName().equals(sourceSchema)) {
                                preferred = schema;
                            }
                        }
                        if (preferred == null) {
                            DBCExecutionContext context = DBUtils.getDefaultContext(database, true);
                            DBCExecutionContextDefaults<?, ?> defaults = context == null ? null : context.getContextDefaults();
                            if (defaults != null && level.children().contains(defaults.getDefaultSchema())) {
                                preferred = (DBSObjectContainer) defaults.getDefaultSchema();
                            }
                        }
                        if (preferred == null && !level.children().isEmpty()) {
                            preferred = level.children().getFirst();
                        }
                        setItems(schemaCombo, level.children(), preferred);
                        schemaCombo.setEnabled(true);
                    }
                } catch (DBInterruptedException e) {
                    databaseCombo.setSelectedItem(null);
                } catch (DBException e) {
                    DBWorkbench.getPlatformUI().showError(wizard.getTitle(), NLS.bind(DbCopyMessages.error_read, database.getName()), e);
                }
            }
            updateNote();
        }

        @Nullable
        private String getSourceSchemaName() {
            List<DBSDataContainer> checked = sources.get();
            DBSObject parent = checked.isEmpty() ? null : checked.getFirst().getParentObject();
            return parent == null ? null : parent.getName();
        }

        private void setItems(@NotNull JComboBox<DBSObjectContainer> combo, @NotNull List<DBSObjectContainer> items, @Nullable DBSObjectContainer selected) {
            updatingCombos = true;
            try {
                combo.removeAllItems();
                for (DBSObjectContainer item : items) {
                    combo.addItem(item);
                }
                combo.setSelectedItem(selected);
            } finally {
                updatingCombos = false;
            }
        }

        private void updateNote() {
            DBSObjectContainer container = getChosenContainer();
            destinationNote.setText("<html>" + (container == null ? DbCopyMessages.destination_note_none //$NON-NLS-1$
                : NLS.bind(DbCopyMessages.destination_note, escape(DBUtils.getObjectFullName(container, DBPEvaluationContext.UI)))) + "</html>"); //$NON-NLS-1$
        }

        @NotNull
        @Override
        public String getTitle() {
            return DbCopyMessages.destination_title;
        }

        @NotNull
        @Override
        public String getDescription() {
            return DbCopyMessages.destination_description;
        }

        @NotNull
        @Override
        public JComponent getComponent() {
            return panel;
        }

        @Override
        public boolean isApplicable() {
            return applicable.getAsBoolean();
        }

        @Override
        public void activate() {
            if (!loaded) {
                loaded = true;
                // The source connection is preselected; its databases are listed without asking (it is connected)
                if (connectionCombo.getSelectedItem() instanceof DBPDataSourceContainer connection && connection.isConnected()) {
                    loadDatabases();
                } else {
                    updateNote();
                }
            }
        }

        @Nullable
        @Override
        public String leave() {
            DBSObjectContainer container = getChosenContainer();
            if (container == null) {
                return DbCopyMessages.destination_error_none;
            }
            DBPDataSource dataSource = container.getDataSource();
            // SWT DataImportPropertyTester.supportsImport
            if (dataSource != null && Boolean.FALSE.equals(dataSource.getDataSourceFeature(DBPDataSource.FEATURE_DATA_IMPORT))) {
                return NLS.bind(DbCopyMessages.destination_error_not_supported, dataSource.getContainer().getName());
            }
            return prepareMappings(container);
        }
    }

    private record DatabaseLevel(@NotNull DBSObjectContainer root, boolean tables, @NotNull List<DBSObjectContainer> children) {
    }

    /**
     * The container the tables go to: the chosen schema, else the chosen database, else the connection when its
     * tables have no database or schema level.
     */
    @Nullable
    private DBSObjectContainer getChosenContainer() {
        if (schemaCombo.isEnabled()) {
            return schemaCombo.getSelectedItem() instanceof DBSObjectContainer schema ? schema : null;
        }
        if (databaseCombo.isEnabled()) {
            Object database = databaseCombo.getSelectedItem();
            return database != null && database == tableDatabase ? tableDatabase : null;
        }
        return rootContainer;
    }

    @NotNull
    private static String escape(@NotNull String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
    }

    //////////////////////////////////////////////////////////
    // Mapping page

    /**
     * Where each source table goes (existing, new, recreated, skipped), its columns and the destination DDL
     * (SSMS "Select Source Tables and Views" destination column and "Edit Mappings").
     */
    private final class MappingPage implements DataTransferWizard.Page {
        private final JPanel panel = new JPanel(new BorderLayout(0, 8));
        private final JComboBox<String> targetNameCombo = new JComboBox<>();
        private final JComboBox<DatabaseMappingType> mappingTypeCombo = new JComboBox<>();
        private final JComboBox<String> columnTypeCombo = new JComboBox<>();

        MappingPage() {
            tableMappings.setFillsViewportHeight(true);
            tableMappings.getTableHeader().setReorderingAllowed(false);
            tableMappings.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            tableMappings.setRowHeight(Math.max(tableMappings.getRowHeight(), new JComboBox<>().getPreferredSize().height));
            tableMappings.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE); //$NON-NLS-1$
            tableMappings.getColumnModel().getColumn(0).setPreferredWidth(260);
            tableMappings.getColumnModel().getColumn(0).setCellRenderer(new DataTransferObjects.ObjectRenderer());
            tableMappings.getColumnModel().getColumn(1).setPreferredWidth(220);
            tableMappings.getColumnModel().getColumn(2).setPreferredWidth(120);
            tableMappings.getColumnModel().getColumn(2).setCellRenderer(new MappingTypeRenderer());
            targetNameCombo.setEditable(true);
            tableMappings.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(targetNameCombo));
            mappingTypeCombo.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    if (value instanceof DatabaseMappingType type) {
                        setText(getMappingLabel(type));
                    }
                    return this;
                }
            });
            tableMappings.getColumnModel().getColumn(2).setCellEditor(new MappingTypeEditor(mappingTypeCombo));
            tableMappings.getSelectionModel().addListSelectionListener(e -> {
                int row = tableMappings.getSelectedRow();
                DatabaseMappingContainer mapping = row >= 0 && row < mappings.size() ? mappings.get(row) : null;
                if (!e.getValueIsAdjusting() && mapping != selectedMapping) {
                    showMapping(mapping);
                }
            });

            columnMappings.setFillsViewportHeight(true);
            columnMappings.getTableHeader().setReorderingAllowed(false);
            columnMappings.setRowHeight(tableMappings.getRowHeight());
            columnMappings.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE); //$NON-NLS-1$
            columnNameCombo.setEditable(true);
            columnTypeCombo.setEditable(true);
            columnMappings.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(columnNameCombo));
            columnMappings.getColumnModel().getColumn(3).setCellEditor(new ColumnTypeEditor(columnTypeCombo));
            columnMappings.getColumnModel().getColumn(4).setCellRenderer(new MappingTypeRenderer());
            columnMappings.getColumnModel().getColumn(4).setPreferredWidth(110);
            columnMappings.getColumnModel().getColumn(5).setPreferredWidth(160);
            columnMappings.getSelectionModel().addListSelectionListener(e -> updateColumnValueButton());
            columnValueButton.addActionListener(e -> editColumnValue());

            ddlArea.setEditable(false);
            ddlArea.putClientProperty(FlatClientProperties.STYLE_CLASS, "monospaced"); //$NON-NLS-1$
            JPanel columnsPanel = new JPanel(new BorderLayout(0, 6));
            columnsPanel.add(new JScrollPane(columnMappings), BorderLayout.CENTER);
            JPanel columnButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            columnButtons.add(columnValueButton);
            columnsPanel.add(columnButtons, BorderLayout.SOUTH);
            details.addTab(DbCopyMessages.mapping_tab_columns, columnsPanel);
            details.addTab(DbCopyMessages.mapping_tab_ddl, new JScrollPane(ddlArea));
            details.addChangeListener(e -> updateDdl());

            mappingStatus.putClientProperty(FlatClientProperties.STYLE, "foreground: $Component.error.focusedBorderColor"); //$NON-NLS-1$
            JPanel tablePanel = new JPanel(new BorderLayout(0, 6));
            tablePanel.add(new JScrollPane(tableMappings), BorderLayout.CENTER);
            // The destination DDL tab shows the truncate before the copy
            truncateCheck.addActionListener(e -> updateDdl());
            JButton rulesButton = new JButton(TransferOptionsMessages.rules_button);
            rulesButton.addActionListener(e -> editMappingRules());
            JPanel optionsRow = new JPanel(new BorderLayout(12, 0));
            optionsRow.add(truncateCheck, BorderLayout.CENTER);
            optionsRow.add(rulesButton, BorderLayout.EAST);
            JPanel options = new JPanel(new BorderLayout(0, 4));
            options.add(optionsRow, BorderLayout.NORTH);
            options.add(mappingStatus, BorderLayout.SOUTH);
            tablePanel.add(options, BorderLayout.SOUTH);

            JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tablePanel, details);
            split.setResizeWeight(0.45);
            split.setBorder(BorderFactory.createEmptyBorder());
            panel.add(DataTransferObjects.createNote(DbCopyMessages.mapping_note), BorderLayout.NORTH);
            panel.add(split, BorderLayout.CENTER);
        }

        @NotNull
        @Override
        public String getTitle() {
            return DbCopyMessages.mapping_title;
        }

        @NotNull
        @Override
        public String getDescription() {
            DBSObjectContainer container = consumerSettings == null ? null : consumerSettings.getContainer();
            return container == null ? DbCopyMessages.mapping_description_none
                : NLS.bind(DbCopyMessages.mapping_description, DBUtils.getObjectFullName(container, DBPEvaluationContext.UI));
        }

        @NotNull
        @Override
        public JComponent getComponent() {
            return panel;
        }

        @Override
        public boolean isApplicable() {
            return applicable.getAsBoolean();
        }

        @Override
        public void activate() {
            DatabaseMappingContainer previous = selectedMapping;
            mappings.clear();
            targetTableNames.clear();
            if (settings != null && consumerSettings != null) {
                mappings.addAll(DatabaseCopyMappings.getMappings(settings, consumerSettings));
                DatabaseConsumerSettings target = consumerSettings;
                try {
                    targetTableNames.addAll(ProgressDialog.runTask(DbCopyMessages.progress_map,
                        monitor -> DatabaseCopyMappings.readTargetTableNames(monitor, target)));
                } catch (DBException e) {
                    log.debug("Can't read the destination tables", e);
                }
            }
            targetNameCombo.removeAllItems();
            for (String name : targetTableNames) {
                targetNameCombo.addItem(name);
            }
            tableModel.fireTableDataChanged();
            updateStatus();
            selectTableRow(mappings.contains(previous) ? previous : mappings.isEmpty() ? null : mappings.getFirst());
        }

        @Nullable
        @Override
        public String leave() {
            stopEditing();
            if (settings == null || consumerSettings == null) {
                return DbCopyMessages.destination_error_none;
            }
            boolean any = false;
            for (DatabaseMappingContainer mapping : mappings) {
                DatabaseMappingType type = mapping.getMappingType();
                if (type == DatabaseMappingType.skip) {
                    continue;
                }
                if (type == DatabaseMappingType.unspecified || !mapping.isCompleted()) {
                    DBSDataContainer source = mapping.getSource();
                    return NLS.bind(DbCopyMessages.mapping_error_incomplete,
                        source == null ? mapping.getTargetName() : DBUtils.getObjectFullName(source, DBPEvaluationContext.UI));
                }
                if (mapping.getTarget() != null && mapping.getTarget().equals(mapping.getSource())) {
                    return NLS.bind(DbCopyMessages.mapping_error_same_table, DBUtils.getObjectFullName(mapping.getTarget(), DBPEvaluationContext.UI));
                }
                any = true;
            }
            String duplicate = findDuplicateTarget();
            if (duplicate != null) {
                return NLS.bind(DbCopyMessages.dbnode_mapping_error_same_target, duplicate);
            }
            return any ? null : DbCopyMessages.mapping_error_nothing;
        }
    }

    /**
     * Edits the name and type rules of new tables and columns, then makes the mappings again with them (as when another
     * destination container is chosen, so the names of new tables and columns follow the rules). A new table's name is
     * made again from its source name, as SWT {@code MappingRulesDialog} does: the current name is already quoted for the
     * destination (a source name with spaces), and the rules would only change the text inside the quotes.
     */
    private void editMappingRules() {
        stopEditing();
        DBSObjectContainer container = consumerSettings == null ? null : consumerSettings.getContainer();
        DBPDataSource dataSource = container == null ? null : container.getDataSource();
        if (settings == null || dataSource == null || !DataTransferOptionDialogs.openMappingRules(wizard, dataSource.getContainer())) {
            return;
        }
        for (DatabaseMappingContainer mapping : mappings) {
            if (mapping.getMappingType() == DatabaseMappingType.create && mapping.getSource() != null) {
                mapping.setTargetName(mapping.getSource().getName());
            }
        }
        DataTransferSettings transfer = settings;
        DatabaseConsumerSettings target = consumerSettings;
        try {
            List<String> errors = ProgressDialog.runTask(DbCopyMessages.progress_map,
                monitor -> DatabaseCopyMappings.createMappings(monitor, transfer, target, true));
            mappingErrors.clear();
            mappingErrors.addAll(errors);
        } catch (DBInterruptedException e) {
            // Keep the mappings as they are
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(wizard.getTitle(), DbCopyMessages.error_map, e);
        }
        refreshMappings();
    }

    /**
     * Chooses what the selected destination column receives (the source value, NULL, a constant or an expression).
     */
    private void editColumnValue() {
        DatabaseMappingAttribute attribute = getSelectedColumn();
        if (attribute != null && DataTransferOptionDialogs.openColumnValue(wizard, attribute)) {
            int row = columnMappings.getSelectedRow();
            columnModel.fireTableRowsUpdated(row, row);
        }
    }

    @Nullable
    private DatabaseMappingAttribute getSelectedColumn() {
        int row = columnMappings.getSelectedRow();
        List<DatabaseMappingAttribute> attributes = columnModel.getAttributes();
        return row >= 0 && row < attributes.size() ? attributes.get(row) : null;
    }

    /**
     * A column value can be set for a column that is copied.
     */
    private void updateColumnValueButton() {
        DatabaseMappingAttribute attribute = getSelectedColumn();
        columnValueButton.setEnabled(attribute != null && selectedMapping != null
            && selectedMapping.getMappingType() != DatabaseMappingType.skip && attribute.getMappingType() != DatabaseMappingType.skip);
    }

    /**
     * A destination table that two copied tables go to, or null. Tables of several schemas of a database go to one
     * destination schema, so tables with the same name meet there (the second would fail to create it, or a TRUNCATE
     * would remove the first one's rows).
     */
    @Nullable
    private String findDuplicateTarget() {
        Set<String> targets = new HashSet<>();
        for (DatabaseMappingContainer mapping : mappings) {
            if (mapping.getMappingType() == DatabaseMappingType.skip) {
                continue;
            }
            String target = mapping.getTarget() != null ? DBUtils.getObjectFullName(mapping.getTarget(), DBPEvaluationContext.UI)
                : mapping.getTargetName();
            if (target != null && !targets.add(target.toLowerCase(Locale.ROOT))) {
                return target;
            }
        }
        return null;
    }

    /**
     * Shows changed mappings without changing the selected table (a data change event would clear the selection).
     */
    private void refreshMappings() {
        if (!mappings.isEmpty()) {
            tableModel.fireTableRowsUpdated(0, mappings.size() - 1);
        }
        showMapping(selectedMapping);
        updateStatus();
    }

    private void selectTableRow(@Nullable DatabaseMappingContainer mapping) {
        int row = mapping == null ? -1 : mappings.indexOf(mapping);
        int version = shownVersion;
        if (row >= 0) {
            tableMappings.setRowSelectionInterval(row, row);
        } else {
            tableMappings.clearSelection();
        }
        // The selection listener shows a newly selected mapping; a row that stayed selected (no event) is shown here
        if (shownVersion == version) {
            showMapping(row >= 0 ? mapping : null);
        }
        updateStatus();
    }

    /**
     * Shows the columns and the DDL of a table mapping; the destination column list offers the columns of an
     * existing destination table.
     */
    private void showMapping(@Nullable DatabaseMappingContainer mapping) {
        stopColumnEditing();
        selectedMapping = mapping;
        shownVersion++;
        targetColumns.clear();
        if (mapping != null && mapping.getTarget() != null) {
            try {
                targetColumns.addAll(ProgressDialog.runTask(DbCopyMessages.progress_map,
                    monitor -> DatabaseCopyMappings.readTargetColumns(monitor, mapping)));
            } catch (DBException e) {
                log.debug("Can't read the destination columns", e);
            }
        }
        columnNameCombo.removeAllItems();
        columnNameCombo.addItem(DatabaseMappingAttribute.TARGET_NAME_SKIP);
        for (DBSEntityAttribute column : targetColumns) {
            columnNameCombo.addItem(column.getName());
        }
        columnModel.fireTableDataChanged();
        updateColumnValueButton();
        updateDdl();
    }

    private void stopColumnEditing() {
        if (columnMappings.isEditing()) {
            columnMappings.getCellEditor().stopCellEditing();
        }
    }

    /**
     * The destination DDL of the selected table, generated when its tab is shown.
     */
    private void updateDdl() {
        if (details.getSelectedIndex() != 1) {
            return;
        }
        DatabaseMappingContainer mapping = selectedMapping;
        DatabaseConsumerSettings target = consumerSettings;
        if (mapping == null || target == null) {
            ddlArea.setText("");
            return;
        }
        String text;
        if (mapping.getMappingType() == DatabaseMappingType.skip) {
            text = DbCopyMessages.ddl_skipped;
        } else if (mapping.getMappingType() == DatabaseMappingType.existing) {
            List<String> added = new ArrayList<>();
            for (DatabaseMappingAttribute attribute : DatabaseCopyMappings.getAddedColumns(new LoggingProgressMonitor(log), mapping)) {
                added.add(attribute.getTargetName() + " " + attribute.getTargetType(target.getTargetDataSource(attribute), true)); //$NON-NLS-1$
            }
            text = added.isEmpty() ? DbCopyMessages.ddl_no_changes
                : NLS.bind(DbCopyMessages.ddl_added_columns, mapping.getTargetName(), String.join("\n    ", added)); //$NON-NLS-1$
            if (truncateCheck.isSelected() && mapping.getTarget() != null) {
                text = NLS.bind(TransferOptionsMessages.ddl_truncate, DBUtils.getObjectFullName(mapping.getTarget(), DBPEvaluationContext.UI))
                    + "\n\n" + text; //$NON-NLS-1$
            }
        } else {
            try {
                String ddl = ProgressDialog.runTask(DbCopyMessages.progress_ddl,
                    monitor -> DatabaseCopyMappings.generateTableDdl(monitor, target, mapping));
                text = ddl == null ? DbCopyMessages.ddl_no_changes : ddl;
            } catch (DBException e) {
                text = NLS.bind(DbCopyMessages.ddl_error, ErrorText.describe(e));
            }
        }
        ddlArea.setText(text);
        ddlArea.setCaretPosition(0);
    }

    /**
     * Name conflicts and unmapped tables under the mapping table.
     */
    private void updateStatus() {
        Set<String> problems = new LinkedHashSet<>(mappingErrors);
        for (DatabaseMappingContainer mapping : mappings) {
            if (mapping.getTarget() != null && mapping.getMappingType() != DatabaseMappingType.skip && mapping.getTarget().equals(mapping.getSource())) {
                problems.add(NLS.bind(DbCopyMessages.mapping_error_same_table, DBUtils.getObjectFullName(mapping.getTarget(), DBPEvaluationContext.UI)));
            }
        }
        String duplicate = findDuplicateTarget();
        if (duplicate != null) {
            problems.add(NLS.bind(DbCopyMessages.dbnode_mapping_error_same_target, duplicate));
        }
        List<String> lines = new ArrayList<>();
        for (String problem : problems) {
            lines.add(escape(problem));
        }
        // One problem per line, wrapped (several tables can have one)
        mappingStatus.setText(problems.isEmpty() ? " " : "<html>" + String.join("<br>", lines) + "</html>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    private final class TableMappingModel extends AbstractTableModel {
        private final String[] columns = {DbCopyMessages.mapping_column_source, DbCopyMessages.mapping_column_destination,
            DbCopyMessages.mapping_column_mapping};

        @Override
        public int getRowCount() {
            return mappings.size();
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
            return columnIndex == 2 || columnIndex == 1 && mappings.get(rowIndex).getMappingType() != DatabaseMappingType.skip;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            DatabaseMappingContainer mapping = mappings.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> mapping.getSource();
                case 1 -> mapping.getTargetName();
                default -> mapping.getMappingType();
            };
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            DatabaseMappingContainer mapping = mappings.get(rowIndex);
            DatabaseConsumerSettings target = consumerSettings;
            if (target == null) {
                return;
            }
            if (columnIndex == 1) {
                String name = CommonUtils.toString(value).trim();
                if (name.isEmpty() || name.equals(mapping.getTargetName())) {
                    return;
                }
                mappingErrors.clear();
                changeMapping(monitor -> {
                    String error = DatabaseCopyMappings.setTableTarget(monitor, target, mapping, name, false, false);
                    if (error != null) {
                        mappingErrors.add(error);
                    }
                });
            } else if (columnIndex == 2 && value instanceof DatabaseMappingType type && type != mapping.getMappingType()) {
                if (type == DatabaseMappingType.recreate && !SwingPlatformUI.confirmChange(wizard,
                    NLS.bind(DbCopyMessages.confirm_recreate, mapping.getTargetName()),
                    wizard.getTitle(), DbCopyMessages.mapping_type_recreate, JOptionPane.WARNING_MESSAGE)) {
                    return;
                }
                changeMapping(monitor -> DatabaseCopyMappings.setTableMappingType(monitor, mapping, type));
            }
        }
    }

    /**
     * Mapping type of a table: the types it can be switched to.
     */
    private final class MappingTypeEditor extends DefaultCellEditor {
        private final JComboBox<DatabaseMappingType> combo;

        MappingTypeEditor(@NotNull JComboBox<DatabaseMappingType> combo) {
            super(combo);
            this.combo = combo;
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
            combo.removeAllItems();
            if (row < mappings.size()) {
                for (DatabaseMappingType type : DatabaseCopyMappings.getTableMappingTypes(mappings.get(row))) {
                    combo.addItem(type);
                }
            }
            return super.getTableCellEditorComponent(table, value, isSelected, row, column);
        }
    }

    //////////////////////////////////////////////////////////
    // Column mappings

    private final class ColumnMappingModel extends AbstractTableModel {
        private final String[] columns = {DbCopyMessages.columns_source, DbCopyMessages.columns_source_type,
            DbCopyMessages.columns_destination, DbCopyMessages.columns_destination_type, DbCopyMessages.columns_mapping,
            TransferOptionsMessages.columns_value};

        @NotNull
        private List<DatabaseMappingAttribute> getAttributes() {
            return selectedMapping == null ? List.of() : new ArrayList<>(selectedMapping.getAttributeMappings(new LoggingProgressMonitor(log)));
        }

        @Override
        public int getRowCount() {
            return getAttributes().size();
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
            if (selectedMapping == null || selectedMapping.getMappingType() == DatabaseMappingType.skip) {
                return false;
            }
            return columnIndex == 2 || columnIndex == 3 && getAttributes().get(rowIndex).getMappingType() == DatabaseMappingType.create;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            DatabaseMappingAttribute attribute = getAttributes().get(rowIndex);
            DBSAttributeBase source = attribute.getSource();
            DatabaseMappingType type = attribute.getMappingType();
            return switch (columnIndex) {
                case 0 -> source == null ? "" : source.getName();
                case 1 -> CommonUtils.notEmpty(attribute.getSourceType());
                case 2 -> type == DatabaseMappingType.existing && attribute.getTarget() != null ? attribute.getTarget().getName() : attribute.getTargetName();
                case 3 -> switch (type) {
                    case existing -> attribute.getTarget() == null ? "" : attribute.getTarget().getFullTypeName();
                    case create -> consumerSettings == null ? "" : attribute.getTargetType(consumerSettings.getTargetDataSource(attribute), true);
                    default -> "";
                };
                case 4 -> type;
                default -> type == DatabaseMappingType.skip ? "" : DataTransferOptionDialogs.describeColumnValue(attribute);
            };
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            DatabaseMappingAttribute attribute = getAttributes().get(rowIndex);
            DatabaseConsumerSettings target = consumerSettings;
            String text = CommonUtils.toString(value).trim();
            if (target == null || text.isEmpty()) {
                return;
            }
            if (columnIndex == 2) {
                String name = DatabaseCopyMappings.normalizeTypedName(target, attribute, text);
                try {
                    // Metadata of the target table is cached by now (SWT also runs this on the UI thread)
                    DatabaseCopyMappings.setColumnTarget(new LoggingProgressMonitor(log), target, attribute, name);
                } catch (DBException e) {
                    DBWorkbench.getPlatformUI().showError(wizard.getTitle(), DbCopyMessages.error_map, e);
                }
            } else if (columnIndex == 3) {
                attribute.setTargetType(text);
            }
            SwingUtilities.invokeLater(DatabaseCopyPages.this::refreshMappings);
        }
    }

    /**
     * Target type of a new column: the target database's types, or any typed type.
     */
    private final class ColumnTypeEditor extends DefaultCellEditor {
        private final JComboBox<String> combo;

        ColumnTypeEditor(@NotNull JComboBox<String> combo) {
            super(combo);
            this.combo = combo;
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
            combo.removeAllItems();
            List<DatabaseMappingAttribute> attributes = columnModel.getAttributes();
            if (consumerSettings != null && row < attributes.size()) {
                for (String type : DatabaseCopyMappings.getTargetTypes(consumerSettings, attributes.get(row))) {
                    combo.addItem(type);
                }
            }
            return super.getTableCellEditorComponent(table, value, isSelected, row, column);
        }
    }

    /**
     * Mapping type in words; a table or column that is not mapped in the error color.
     */
    private static final class MappingTypeRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value instanceof DatabaseMappingType type ? getMappingLabel(type) : value,
                isSelected, hasFocus, row, column);
            boolean problem = value == DatabaseMappingType.unspecified;
            setForeground(problem ? UIManager.getColor("Component.error.focusedBorderColor") //$NON-NLS-1$
                : value == DatabaseMappingType.skip ? UIManager.getColor("Label.disabledForeground") //$NON-NLS-1$
                : isSelected ? table.getSelectionForeground() : table.getForeground());
            return this;
        }
    }

    //////////////////////////////////////////////////////////
    // Load settings

    /**
     * How the rows are written (SWT "Data load settings").
     */
    private final class LoadSettingsPage implements DataTransferWizard.Page {
        private final JPanel panel = new JPanel(new BorderLayout());

        LoadSettingsPage() {
            transactionsCheck.addActionListener(e -> updateEnablement());
            multiRowCheck.addActionListener(e -> updateEnablement());
            disableBatchesCheck.addActionListener(e -> updateEnablement());
            bulkLoadCheck.addActionListener(e -> updateEnablement());
            onDuplicateCombo.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    setText(value instanceof SQLDialectInsertReplaceMethod method ? method.getLabel() : TransferOptionsMessages.load_on_duplicate_none);
                    return this;
                }
            });
            JPanel form = new JPanel(new GridBagLayout());
            int row = 0;
            row = DataTransferObjects.addRow(form, row, null, newConnectionCheck, null);
            row = DataTransferObjects.addRow(form, row, null, transactionsCheck, null);
            row = DataTransferObjects.addRow(form, row, DbCopyMessages.load_commit, commitSpinner, DbCopyMessages.load_commit_note);
            row = DataTransferObjects.addRow(form, row, null, multiRowCheck, null);
            row = DataTransferObjects.addRow(form, row, DbCopyMessages.load_rows_per_insert, multiRowSpinner, null);
            row = DataTransferObjects.addRow(form, row, null, skipBindCheck, null);
            row = DataTransferObjects.addRow(form, row, null, disableBatchesCheck, null);
            row = DataTransferObjects.addRow(form, row, null, ignoreDuplicatesCheck, null);
            // The note is the tooltip: a hint column would push the hints of the spinners away from them
            row = DataTransferObjects.addRow(form, row, TransferOptionsMessages.load_on_duplicate, onDuplicateCombo, null);
            row = DataTransferObjects.addRow(form, row, null, autoGeneratedCheck, null);
            row = DataTransferObjects.addRow(form, row, null, referentialIntegrityCheck, null);
            row = DataTransferObjects.addRow(form, row, null, bulkLoadCheck, null);
            row = DataTransferObjects.addRow(form, row, null, qmLoggingCheck, null);
            if (queryWindows != null) {
                DataTransferObjects.addRow(form, row, null, openTablesCheck, null);
            }
            JPanel content = new JPanel(new BorderLayout(0, 12));
            content.add(form, BorderLayout.NORTH);
            content.add(DataTransferObjects.createNote(DbCopyMessages.load_note), BorderLayout.CENTER);
            panel.add(content, BorderLayout.NORTH);
            updateEnablement();
        }

        /**
         * Option dependencies as in SWT {@code DatabaseConsumerPageLoadSettings}: duplicate rows can be skipped only when rows are
         * inserted one by one (a batch fails as a whole), and bulk load ignores the INSERT variant.
         */
        private void updateEnablement() {
            boolean bulk = bulkLoadCheck.isSelected() && bulkLoadCheck.isEnabled();
            commitSpinner.setEnabled(transactionsCheck.isSelected());
            multiRowCheck.setEnabled(!disableBatchesCheck.isSelected() && !bulk);
            multiRowSpinner.setEnabled(multiRowCheck.isSelected() && multiRowCheck.isEnabled());
            skipBindCheck.setEnabled(destinationBindsValues && multiRowCheck.isSelected() && multiRowCheck.isEnabled());
            ignoreDuplicatesCheck.setEnabled(disableBatchesCheck.isSelected() && !bulk);
            onDuplicateCombo.setEnabled(onDuplicateCombo.getItemCount() > 1 && !bulk);
        }

        /**
         * The INSERT variants for duplicate keys of the destination dialect (SWT {@code loadInsertMethods}); the chosen one stays
         * when the destination keeps it.
         */
        private void loadInsertMethods(@Nullable DBPDataSource dataSource) {
            Object selected = onDuplicateCombo.getSelectedItem();
            String selectedId = selected instanceof SQLDialectInsertReplaceMethod method ? method.getId() : null;
            onDuplicateCombo.removeAllItems();
            onDuplicateCombo.addItem(DBSDataManipulator.INSERT_NONE_METHOD);
            List<SQLDialectInsertReplaceMethod> methods = dataSource == null ? List.of()
                : dataSource.getContainer().getScriptDialect().getSupportedInsertReplaceMethodsDescriptors();
            for (SQLDialectInsertReplaceMethod method : methods) {
                onDuplicateCombo.addItem(method);
                if (method.getId().equals(selectedId)) {
                    onDuplicateCombo.setSelectedItem(method);
                }
            }
            onDuplicateCombo.setToolTipText(methods.isEmpty() ? TransferOptionsMessages.load_on_duplicate_unsupported
                : TransferOptionsMessages.load_on_duplicate_note);
        }

        /**
         * Whether the existing target tables can switch off their foreign key checks, and with which statements
         * (SWT {@code loadUISettingsForDisableReferentialIntegrityCheckbox}; new tables have no target yet).
         */
        private void updateReferentialIntegrity() {
            List<DBPReferentialIntegrityController> controllers = new ArrayList<>();
            for (DatabaseMappingContainer mapping : mappings) {
                if (mapping.getMappingType() != DatabaseMappingType.skip && mapping.getTarget() instanceof DBPReferentialIntegrityController controller) {
                    controllers.add(controller);
                }
            }
            Set<String> statements = new LinkedHashSet<>();
            if (!controllers.isEmpty()) {
                try {
                    statements.addAll(ProgressDialog.runTask(DbCopyMessages.progress_map, monitor -> {
                        Set<String> result = new LinkedHashSet<>();
                        for (DBPReferentialIntegrityController controller : controllers) {
                            try {
                                if (controller.supportsChangingReferentialIntegrity(monitor)) {
                                    result.add(controller.getChangeReferentialIntegrityStatement(monitor, false));
                                    result.add(controller.getChangeReferentialIntegrityStatement(monitor, true));
                                }
                            } catch (DBException e) {
                                log.debug("Can't check referential integrity support", e);
                            }
                        }
                        return result;
                    }));
                } catch (DBException e) {
                    log.debug("Can't check referential integrity support", e);
                }
            }
            referentialIntegrityCheck.setEnabled(!statements.isEmpty());
            if (statements.isEmpty()) {
                referentialIntegrityCheck.setSelected(false);
                referentialIntegrityCheck.setToolTipText(DbCopyMessages.load_disable_integrity_unsupported);
            } else {
                referentialIntegrityCheck.setToolTipText("<html>" + escape(DbCopyMessages.load_disable_integrity_tip) + "<br>" //$NON-NLS-1$ //$NON-NLS-2$
                    + escape(String.join("\n", statements)).replace("\n", "<br>") + "</html>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            }
        }

        @NotNull
        @Override
        public String getTitle() {
            return DbCopyMessages.load_title;
        }

        @NotNull
        @Override
        public String getDescription() {
            return DbCopyMessages.load_description;
        }

        @NotNull
        @Override
        public JComponent getComponent() {
            return panel;
        }

        @Override
        public boolean isApplicable() {
            return applicable.getAsBoolean();
        }

        @Override
        public void activate() {
            updateReferentialIntegrity();
            DBSObjectContainer container = consumerSettings == null ? null : consumerSettings.getContainer();
            DBPDataSource dataSource = container == null ? null : container.getDataSource();
            boolean bulk = DBUtils.getAdapter(DBSDataBulkLoader.class, dataSource) != null;
            bulkLoadCheck.setEnabled(bulk);
            if (!bulk) {
                bulkLoadCheck.setSelected(false);
            }
            // SWT offers this for statement-binding drivers only: it may break the others
            destinationBindsValues = dataSource != null && dataSource.getInfo().supportsStatementBinding();
            loadInsertMethods(dataSource);
            updateEnablement();
        }
    }
}
