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

import com.seizia.chromite.ui.swing.internal.GridSqlMessages;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.data.DBDAttributeBinding;
import com.seizia.chromite.model.data.DBDDataFilter;
import com.seizia.chromite.model.data.DBDDataReceiver;
import com.seizia.chromite.model.data.DBDResultSetDataProvider;
import com.seizia.chromite.model.data.DBDRowIdentifier;
import com.seizia.chromite.model.data.DBDValueRow;
import com.seizia.chromite.model.data.ResultSetValuePath;
import com.seizia.chromite.model.data.hints.DBDValueHintContext;
import com.seizia.chromite.model.exec.DBCAttributeMetaData;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionContextDefaults;
import com.seizia.chromite.model.exec.DBCExecutionSource;
import com.seizia.chromite.model.exec.DBCResultSetMetaData;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBCStatistics;
import com.seizia.chromite.model.exec.DBExecUtils;
import com.seizia.chromite.model.impl.data.ResultSetHintContext;
import com.seizia.chromite.model.impl.local.LocalResultSet;
import com.seizia.chromite.model.impl.local.LocalResultSetMeta;
import com.seizia.chromite.model.impl.local.LocalStatement;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLDialectInsertReplaceMethod;
import com.seizia.chromite.model.sql.generator.SQLGenerator;
import com.seizia.chromite.model.sql.generator.resultset.SQLGeneratorInsertReplaceFromData;
import com.seizia.chromite.model.sql.generator.resultset.SQLGeneratorMergeFromData;
import com.seizia.chromite.model.sql.generator.resultset.SQLGeneratorResultSet;
import com.seizia.chromite.model.sql.registry.SQLGeneratorConfigurationRegistry;
import com.seizia.chromite.model.sql.registry.SQLGeneratorDescriptor;
import com.seizia.chromite.model.struct.DBSDataContainer;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectContainer;
import com.seizia.chromite.runtime.DBInterruptedException;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.tools.transfer.database.DatabaseConsumerSettings;
import com.seizia.chromite.tools.transfer.database.DatabaseMappingContainer;
import com.seizia.chromite.tools.transfer.database.DatabaseMappingType;
import com.seizia.chromite.tools.transfer.database.DatabaseTransferUtils;
import org.jkiss.utils.CommonUtils;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 결과 표의 "SQL 생성" 메뉴: 고른 행에서 SELECT … WHERE 키, INSERT, UPDATE, DELETE, MERGE, 중복 키 처리 INSERT를, 결과 전체에서 그 결과를 담을
 * CREATE TABLE을 만든다(SWT {@code SQLGeneratorContributor}의 결과 집합 항목, {@code SQLGeneratorHandlerResultSetDDL}).
 *
 * 설계:
 * <ul>
 *     <li>문장은 모델의 결과 집합 생성기({@link SQLGeneratorResultSet}, 확장점 {@code com.seizia.chromite.sqlGenerator}에서 결과용으로
 *     등록된 것)가 만든다. SWT 결과 모델 대신 고른 행의 값을 담은 {@link DBDResultSetDataProvider}({@link RowsProvider})를 넘긴다.
 *     값은 결과 뷰어가 가진 칸 값(편집할 수 있는 결과는 저장하지 않은 변경을 포함한 지금 값)이고, LOB 칸은 받은 내용이나 남긴 값 객체다
 *     ({@link ResultSetCellContent#toStatementValue}). 그래서 쿼리를 다시 실행하지 않는다.</li>
 *     <li>MERGE는 방언이 MERGE가 있다고 할 때만({@code SQLDialect.supportsMergeStatement}), 중복 키 처리 INSERT는 방언이 데이터 전송용으로
 *     등록한 방식({@code insertMethods}: PostgreSQL ON CONFLICT, MySQL·MariaDB ON DUPLICATE KEY UPDATE·REPLACE·INSERT IGNORE 등)마다 보인다.</li>
 *     <li>행 문장은 SWT처럼 결과가 테이블 하나에서 왔을 때만 켜진다. 키가 꼭 있어야 하는 문장({@link SQLGeneratorResultSet#isUniqueKeyRequired}:
 *     UPDATE, SELECT … IN, MERGE)은 결과에 그 테이블의 완전한 고유 키(가상 키 포함)가 없으면 꺼지고 도구 설명에 이유를 쓴다.
 *     키가 없을 때 SWT UPDATE는 WHERE 없이 테이블 전체를 바꾸는 문장을 만들었다.</li>
 *     <li>CREATE TABLE은 데이터 전송의 대상 테이블 DDL({@link DatabaseTransferUtils#generateTargetTableDDL}, 테이블 복사 미리 보기와 같은
 *     {@link DatabaseCopyMappings#generateTableDdl})로 만든다. 원본은 결과의 열 정보를 그대로 돌려주는 데이터 컨테이너({@link ResultColumns})다.
 *     SWT는 결과 모델을 감싼 컨테이너를 썼고, 데이터 전송의 기본 경로는 쿼리를 다시 실행해 열을 읽으므로 쓰지 않는다. 테이블은 쿼리 창의
 *     기본 스키마(없으면 데이터베이스)에 만들고, 이름은 결과의 원본 테이블 이름(없으면 {@code new_table})에 그곳에 이미 있으면 번호를 붙인다.</li>
 *     <li>만든 문장은 실행하지 않는다. 대화상자({@link ScriptPreviewDialog#showGenerated})에서 복사하거나 새 쿼리 창으로 연다(실행 단추 없음).</li>
 *     <li>메뉴는 열릴 때마다 지금 선택과 결과로 다시 채운다(EDT, 메모리의 정보만). 생성은 키·제약 조건을 읽을 수 있으므로 진행 대화상자의 Job에서 한다.</li>
 * </ul>
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성: 결과 행에서 SQL 만들기(SWT 대조 B-S41) (docs/chromite/swing-result-generate-sql.md)
 */
final class ResultSetGenerateSql {

    private static final Log log = Log.getLog(ResultSetGenerateSql.class);

    private static final String MERGE_LABEL = "MERGE"; //$NON-NLS-1$
    private static final String CREATE_TABLE_LABEL = "CREATE TABLE"; //$NON-NLS-1$
    /**
     * Table name of a result whose columns come from no table (expressions only).
     */
    private static final String DEFAULT_TABLE_NAME = "new_table"; //$NON-NLS-1$
    private static final int MAX_NAME_SUFFIX = 99;

    /**
     * The result viewer side: the result, its selection and where generated statements go.
     */
    interface Source {
        @NotNull
        Component getOwner();

        @NotNull
        DBDAttributeBinding[] getBindings();

        @NotNull
        DBSDataContainer getDataContainer();

        /**
         * The context the result was read in, or null if unknown.
         */
        @Nullable
        DBCExecutionContext getContext();

        /**
         * Selected rows (grid row indexes).
         */
        @NotNull
        int[] getSelectedRows();

        /**
         * The value of a cell as the viewer has it: the current value of an editable result, the kept value, the LOB
         * content ({@link ResultSetCellContent}) or the geometry; null for NULL and values that are not kept.
         */
        @Nullable
        Object getCellValue(int row, int column);

        @NotNull
        ResultSetGrid.Host getHost();
    }

    /**
     * Statement maker of one menu item, run in the job.
     */
    @FunctionalInterface
    private interface Maker {
        @NotNull
        String make(@NotNull DBRProgressMonitor monitor) throws DBException;
    }

    private ResultSetGenerateSql() {
    }

    //////////////////////////////////////////////////////////
    // Menu

    /**
     * Fills the Generate SQL submenu for the current result and selection (EDT, model data in memory only).
     */
    static void fillMenu(@NotNull JMenu menu, @NotNull Source source) {
        menu.removeAll();
        menu.setToolTipText(null);
        DBCExecutionContext context = source.getContext();
        if (context == null || !context.isConnected()) {
            menu.setEnabled(false);
            menu.setToolTipText(GridSqlMessages.gridsql_no_connection);
            return;
        }
        menu.setEnabled(true);
        DBPDataSource dataSource = context.getDataSource();
        DBDAttributeBinding[] bindings = source.getBindings();
        DBSEntity table = DBExecUtils.detectSingleSourceTable(bindings);
        boolean hasKey = findUniqueKey(bindings) != null;
        String rowsBlocker = source.getSelectedRows().length == 0
            ? GridSqlMessages.gridsql_no_rows
            : table == null ? GridSqlMessages.gridsql_no_table : null;

        // The result generators of the extension point, in their order (SWT menu)
        for (SQLGeneratorDescriptor descriptor : getRowGenerators()) {
            SQLGeneratorResultSet generator;
            try {
                generator = (SQLGeneratorResultSet) descriptor.<DBDResultSetDataProvider>createGenerator(new ArrayList<>());
            } catch (DBException e) {
                log.debug("Can't create SQL generator " + descriptor.getId(), e);
                continue;
            }
            addRowItem(menu, source, descriptor.getLabel(), getBlocker(rowsBlocker, generator, hasKey), generator);
        }
        if (dataSource.getSQLDialect().supportsMergeStatement()) {
            SQLGeneratorMergeFromData merge = new SQLGeneratorMergeFromData();
            addRowItem(menu, source, MERGE_LABEL, getBlocker(rowsBlocker, merge, hasKey), merge);
        }
        List<SQLDialectInsertReplaceMethod> methods = dataSource.getContainer().getScriptDialect().getSupportedInsertReplaceMethodsDescriptors();
        if (!methods.isEmpty()) {
            JMenu methodsMenu = new JMenu(GridSqlMessages.gridsql_insert_methods);
            for (SQLDialectInsertReplaceMethod method : methods) {
                SQLGeneratorInsertReplaceFromData generator;
                try {
                    generator = new SQLGeneratorInsertReplaceFromData(method.createInsertMethod());
                } catch (DBException e) {
                    log.debug("Can't create insert method " + method.getId(), e);
                    continue;
                }
                addRowItem(methodsMenu, source, method.getLabel(), getBlocker(rowsBlocker, generator, hasKey), generator);
            }
            if (methodsMenu.getItemCount() > 0) {
                menu.add(methodsMenu);
            }
        }

        menu.addSeparator();
        JMenuItem ddlItem = new JMenuItem(GridSqlMessages.gridsql_ddl);
        if (bindings.length == 0) {
            ddlItem.setEnabled(false);
            ddlItem.setToolTipText(GridSqlMessages.gridsql_no_columns);
        } else {
            ddlItem.setToolTipText(GridSqlMessages.gridsql_ddl_tip);
            ddlItem.addActionListener(e -> generateTableDdl(source));
        }
        menu.add(ddlItem);
    }

    /**
     * The registered generators of statements from result rows, by order.
     */
    @NotNull
    private static List<SQLGeneratorDescriptor> getRowGenerators() {
        List<SQLGeneratorDescriptor> result = new ArrayList<>();
        for (SQLGeneratorDescriptor descriptor : SQLGeneratorConfigurationRegistry.getInstance().getAllGenerators()) {
            Class<?> implClass = descriptor.getGeneratorImplClass().getObjectClass();
            if (implClass != null && SQLGeneratorResultSet.class.isAssignableFrom(implClass)) {
                result.add(descriptor);
            }
        }
        result.sort(Comparator.comparingInt(SQLGeneratorDescriptor::getOrder));
        return result;
    }

    @Nullable
    private static String getBlocker(@Nullable String rowsBlocker, @NotNull SQLGeneratorResultSet generator, boolean hasKey) {
        if (rowsBlocker != null) {
            return rowsBlocker;
        }
        return generator.isUniqueKeyRequired() && !hasKey ? GridSqlMessages.gridsql_no_key : null;
    }

    private static void addRowItem(
        @NotNull JMenu menu,
        @NotNull Source source,
        @NotNull String label,
        @Nullable String blocker,
        @NotNull SQLGeneratorResultSet generator
    ) {
        JMenuItem item = new JMenuItem(label);
        if (blocker != null) {
            item.setEnabled(false);
            item.setToolTipText(blocker);
        } else {
            item.addActionListener(e -> generateFromRows(source, label, generator));
        }
        menu.add(item);
    }

    /**
     * The result's unique key (the first column's row identifier, as the grid's edit model and SWT
     * {@code ResultSetModel.getDefaultRowIdentifier}) when it is complete, otherwise null.
     */
    @Nullable
    private static DBDRowIdentifier findUniqueKey(@NotNull DBDAttributeBinding[] bindings) {
        for (DBDAttributeBinding binding : bindings) {
            DBDRowIdentifier identifier = binding.getRowIdentifier();
            if (identifier != null) {
                return identifier.isIncomplete() || !identifier.isValidIdentifier() ? null : identifier;
            }
        }
        return null;
    }

    //////////////////////////////////////////////////////////
    // Statements from rows

    private static void generateFromRows(@NotNull Source source, @NotNull String label, @NotNull SQLGeneratorResultSet generator) {
        DBCExecutionContext context = source.getContext();
        if (context == null) {
            return;
        }
        DBDAttributeBinding[] bindings = source.getBindings();
        // The cell values now, on the EDT; LOB contents are made into values in the job
        int[] selected = source.getSelectedRows();
        List<Object[]> values = new ArrayList<>(selected.length);
        for (int row : selected) {
            Object[] rowValues = new Object[bindings.length];
            for (int column = 0; column < bindings.length; column++) {
                rowValues[column] = source.getCellValue(row, column);
            }
            values.add(rowValues);
        }
        DBSEntity table = DBExecUtils.detectSingleSourceTable(bindings);
        String tableName = table == null ? "" : DBUtils.getObjectFullName(table, DBPEvaluationContext.UI); //$NON-NLS-1$
        String header = NLS.bind(GridSqlMessages.gridsql_header_rows, new Object[]{label, selected.length, tableName});
        run(source, context, label, header, monitor -> {
            List<RowValues> rows = new ArrayList<>(values.size());
            for (int i = 0; i < values.size(); i++) {
                Object[] rowValues = values.get(i);
                for (int column = 0; column < rowValues.length; column++) {
                    if (rowValues[column] instanceof ResultSetCellContent content) {
                        rowValues[column] = content.toStatementValue(monitor, context);
                    }
                }
                rows.add(new RowValues(selected[i], rowValues));
            }
            RowsProvider provider = new RowsProvider(source.getDataContainer(), bindings, rows);
            generator.initGenerator(List.of(provider));
            return runGenerator(monitor, generator);
        });
    }

    @NotNull
    private static String runGenerator(@NotNull DBRProgressMonitor monitor, @NotNull SQLGenerator<?> generator) throws DBException {
        try {
            generator.run(monitor);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getTargetException();
            throw cause instanceof DBException dbException ? dbException : new DBException(GridSqlMessages.gridsql_error, cause);
        } catch (InterruptedException e) {
            throw new DBInterruptedException("SQL generation canceled");
        }
        String result = generator.getResult();
        if (CommonUtils.isEmpty(result)) {
            throw new DBException(GridSqlMessages.gridsql_error);
        }
        return result;
    }

    //////////////////////////////////////////////////////////
    // CREATE TABLE for the result

    private static void generateTableDdl(@NotNull Source source) {
        DBCExecutionContext context = source.getContext();
        if (context == null) {
            return;
        }
        DBDAttributeBinding[] bindings = source.getBindings();
        String[] target = new String[1];
        String script = runForScript(CREATE_TABLE_LABEL, monitor -> {
            DBPDataSource dataSource = context.getDataSource();
            if (dataSource.getInfo().isDynamicMetadata()) {
                throw new DBException(GridSqlMessages.gridsql_dynamic_metadata);
            }
            DBSObjectContainer schema = getTargetContainer(monitor, context);
            if (schema == null) {
                throw new DBException(GridSqlMessages.gridsql_no_schema);
            }
            target[0] = DBUtils.getObjectFullName(schema, DBPEvaluationContext.UI);
            DatabaseConsumerSettings settings = new DatabaseConsumerSettings();
            settings.setContainer(schema);
            ResultColumns columns = new ResultColumns(dataSource, bindings, getTableName(monitor, schema, bindings));
            DatabaseMappingContainer mapping = new DatabaseMappingContainer(settings, columns);
            mapping.refreshMappingType(monitor, DatabaseMappingType.create, true, true);
            String ddl = DatabaseCopyMappings.generateTableDdl(monitor, settings, mapping);
            if (CommonUtils.isEmpty(ddl)) {
                throw new DBException(GridSqlMessages.gridsql_no_ddl);
            }
            return ddl;
        });
        if (script != null) {
            show(source, context, CREATE_TABLE_LABEL, NLS.bind(GridSqlMessages.gridsql_header_ddl, target[0], bindings.length), script);
        }
    }

    /**
     * The container the table is created in: the default schema of the query window's context, else its default
     * database (MySQL, MariaDB), else the connection itself when it holds tables (SQLite); null if none can.
     */
    @Nullable
    private static DBSObjectContainer getTargetContainer(@NotNull DBRProgressMonitor monitor, @NotNull DBCExecutionContext context)
        throws DBException {
        DBCExecutionContextDefaults<?, ?> defaults = context.getContextDefaults();
        if (defaults != null) {
            if (defaults.getDefaultSchema() instanceof DBSObjectContainer schema && DatabaseCopyMappings.isTableContainer(monitor, schema)) {
                return schema;
            }
            if (defaults.getDefaultCatalog() instanceof DBSObjectContainer catalog && DatabaseCopyMappings.isTableContainer(monitor, catalog)) {
                return catalog;
            }
        }
        if (context.getDataSource() instanceof DBSObjectContainer root && DatabaseCopyMappings.isTableContainer(monitor, root)) {
            return root;
        }
        return null;
    }

    /**
     * The new table's name: the result's table (the first table of a join), else {@value #DEFAULT_TABLE_NAME}, with a
     * number when the container already has a table of that name (the statement would fail as it is).
     */
    @NotNull
    private static String getTableName(@NotNull DBRProgressMonitor monitor, @NotNull DBSObjectContainer schema, @NotNull DBDAttributeBinding[] bindings)
        throws DBException {
        DBSEntity table = DBExecUtils.detectSingleSourceTable(bindings);
        if (table == null) {
            for (DBDAttributeBinding binding : bindings) {
                DBSEntityAttribute attribute = binding.getEntityAttribute();
                if (attribute != null) {
                    table = attribute.getParentObject();
                    break;
                }
            }
        }
        String base = table != null ? table.getName() : DEFAULT_TABLE_NAME;
        List<String> existing = new ArrayList<>();
        for (DBSObject child : CommonUtils.safeCollection(schema.getChildren(monitor))) {
            existing.add(child.getName().toLowerCase(Locale.ROOT));
        }
        String name = base;
        for (int suffix = 1; existing.contains(name.toLowerCase(Locale.ROOT)) && suffix <= MAX_NAME_SUFFIX; suffix++) {
            name = base + "_" + suffix; //$NON-NLS-1$
        }
        return name;
    }

    //////////////////////////////////////////////////////////
    // Running and showing

    private static void run(@NotNull Source source, @NotNull DBCExecutionContext context, @NotNull String label, @NotNull String header,
                            @NotNull Maker maker) {
        String script = runForScript(label, maker);
        if (script != null) {
            show(source, context, label, header, script);
        }
    }

    /**
     * Makes the statements in a job with a progress dialog; null if it was canceled or failed (the error was shown).
     */
    @Nullable
    private static String runForScript(@NotNull String label, @NotNull Maker maker) {
        try {
            return ProgressDialog.runTask(GridSqlMessages.gridsql_job + " - " + label, maker::make); //$NON-NLS-1$
        } catch (DBInterruptedException e) {
            return null;
        } catch (DBException e) {
            DBWorkbench.getPlatformUI().showError(NLS.bind(GridSqlMessages.gridsql_title, label), GridSqlMessages.gridsql_error, e);
            return null;
        }
    }

    private static void show(
        @NotNull Source source,
        @NotNull DBCExecutionContext context,
        @NotNull String label,
        @NotNull String header,
        @NotNull String script
    ) {
        ResultSetGrid.Host host = source.getHost();
        ScriptPreviewDialog.Result result = ScriptPreviewDialog.showGenerated(
            source.getOwner(), NLS.bind(GridSqlMessages.gridsql_title, label), header, script, host.canOpenScript());
        if (result == ScriptPreviewDialog.Result.OPEN_IN_EDITOR) {
            DBPDataSourceContainer container = context.getDataSource().getContainer();
            host.openScript(container, script);
        }
    }

    //////////////////////////////////////////////////////////
    // Model adapters

    /**
     * A selected row: its grid row and its cell values.
     */
    private record RowValues(int rowNumber, @NotNull Object[] values) implements DBDValueRow {
        @Override
        public int getRowNumber() {
            return rowNumber;
        }

        @NotNull
        @Override
        public Object[] getValues() {
            return values;
        }
    }

    /**
     * The selected rows of a result as the generators read them (SWT passed its result set viewer). Only the selected
     * rows are copied: they are all the generators read.
     */
    private static final class RowsProvider implements DBDResultSetDataProvider {
        private final DBDAttributeBinding[] bindings;
        private final List<DBDAttributeBinding> columns;
        private final List<RowValues> rows;
        @Nullable
        private final DBDRowIdentifier uniqueKey;
        @Nullable
        private final DBSEntity singleSource;
        private final ResultSetHintContext hintContext;

        RowsProvider(@NotNull DBSDataContainer dataContainer, @NotNull DBDAttributeBinding[] bindings, @NotNull List<RowValues> rows) {
            this.bindings = bindings;
            this.columns = List.of(bindings);
            this.rows = rows;
            this.uniqueKey = findUniqueKey(bindings);
            this.singleSource = DBExecUtils.detectSingleSourceTable(bindings);
            this.hintContext = new ResultSetHintContext(() -> dataContainer, () -> singleSource);
        }

        @NotNull
        @Override
        public List<RowValues> getSelectedRows() {
            return rows;
        }

        @NotNull
        @Override
        public DBDAttributeBinding[] getAttributes() {
            return bindings;
        }

        /**
         * All columns of the result, also those hidden in the grid (Columns menu): the statements are on the table.
         */
        @NotNull
        @Override
        public List<DBDAttributeBinding> getVisibleAttributes() {
            return columns;
        }

        @NotNull
        @Override
        public List<RowValues> getAllRows() {
            return rows;
        }

        @Nullable
        @Override
        public DBDRowIdentifier getDefaultRowIdentifier() {
            return uniqueKey;
        }

        @Nullable
        @Override
        public Object getCellValue(
            @NotNull DBDAttributeBinding attribute,
            @NotNull DBDValueRow row,
            @Nullable int[] rowIndexes,
            @Nullable ResultSetValuePath valuePath,
            boolean retrieveDeepestCollectionElement
        ) {
            if (valuePath != null) {
                return DBUtils.getRowValueByPath(row, valuePath);
            }
            return DBUtils.getAttributeValue(attribute, bindings, row.getValues(), rowIndexes, retrieveDeepestCollectionElement);
        }

        @Nullable
        @Override
        public Object getCellValue(@NotNull DBDAttributeBinding attribute, @NotNull DBDValueRow row) {
            return getCellValue(attribute, row, null, null, false);
        }

        @NotNull
        @Override
        public DBDValueHintContext getHintContext() {
            return hintContext;
        }

        @Nullable
        @Override
        public String getReadOnlyStatus(@Nullable DBPDataSourceContainer dataSourceContainer) {
            return DBExecUtils.getResultSetReadOnlyStatus(dataSourceContainer);
        }

        @Nullable
        @Override
        public DBSEntity getSingleSource() {
            return singleSource;
        }
    }

    /**
     * The columns of a fetched result as a data container whose "read" gives their metadata without running anything:
     * the data transfer mapping reads the columns of a query by running it ({@code DTUtils.getAttributes}). The mapping
     * opens a metadata session of the connection's default context for the read.
     */
    private static final class ResultColumns implements DBSDataContainer {
        private static final String STATEMENT_TEXT = "Result columns"; //$NON-NLS-1$

        private final DBPDataSource dataSource;
        private final List<DBCAttributeMetaData> columns = new ArrayList<>();
        private final String name;

        /**
         * @param name the name of the new table (the mapping takes it as the source name)
         */
        ResultColumns(@NotNull DBPDataSource dataSource, @NotNull DBDAttributeBinding[] bindings, @NotNull String name) {
            this.dataSource = dataSource;
            this.name = name;
            for (DBDAttributeBinding binding : bindings) {
                DBCAttributeMetaData meta = binding.getMetaAttribute();
                if (meta != null) {
                    columns.add(meta);
                }
            }
        }

        @NotNull
        @Override
        public DBPDataSource getDataSource() {
            return dataSource;
        }

        @NotNull
        @Override
        public String[] getSupportedFeatures() {
            return new String[0];
        }

        @NotNull
        @Override
        public DBCStatistics readData(
            @Nullable DBCExecutionSource source,
            @NotNull DBCSession session,
            @NotNull DBDDataReceiver dataReceiver,
            @Nullable DBDDataFilter dataFilter,
            long firstRow,
            long maxRows,
            long flags,
            int fetchSize
        ) throws DBException {
            // A local statement and result set that only carry the column metadata: no query runs
            try (LocalStatement statement = new LocalStatement(session, STATEMENT_TEXT)) {
                LocalResultSet<LocalStatement> resultSet = new LocalResultSet<>(session, statement) {
                    @NotNull
                    @Override
                    public DBCResultSetMetaData getMeta() {
                        return new LocalResultSetMeta(columns);
                    }
                };
                dataReceiver.fetchStart(session, resultSet, firstRow, maxRows);
                dataReceiver.fetchEnd(session, resultSet);
            }
            return new DBCStatistics();
        }

        @Override
        public long countData(
            @NotNull DBCExecutionSource source,
            @NotNull DBCSession session,
            @Nullable DBDDataFilter dataFilter,
            long flags
        ) throws DBCException {
            return 0;
        }

        @Nullable
        @Override
        public String getDescription() {
            return null;
        }

        @Nullable
        @Override
        public DBSObject getParentObject() {
            return null;
        }

        @NotNull
        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isPersisted() {
            return false;
        }
    }
}
