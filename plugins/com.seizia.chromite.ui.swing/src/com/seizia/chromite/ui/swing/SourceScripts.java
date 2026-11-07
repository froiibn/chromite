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

import com.seizia.chromite.ui.swing.internal.SourceEditMessages;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.ext.mssql.model.SQLServerDataSource;
import com.seizia.chromite.ext.mssql.model.SQLServerDatabaseTrigger;
import com.seizia.chromite.ext.mysql.model.MySQLDataSource;
import com.seizia.chromite.ext.oracle.model.OracleDataSource;
import com.seizia.chromite.ext.oracle.model.OracleQueue;
import com.seizia.chromite.ext.oracle.model.OracleSchemaTrigger;
import com.seizia.chromite.ext.postgresql.model.PostgreDataSource;
import com.seizia.chromite.ext.postgresql.model.PostgreDatabase;
import com.seizia.chromite.ext.postgresql.model.PostgreEventTrigger;
import com.seizia.chromite.ext.postgresql.model.PostgreMaterializedView;
import com.seizia.chromite.ext.postgresql.model.PostgreTablePolicy;
import com.seizia.chromite.model.DBConstants;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBPScriptObject;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.edit.DBECommand;
import com.seizia.chromite.model.edit.DBEObjectEditor;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.edit.prop.DBECommandProperty;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionContextDefaults;
import com.seizia.chromite.model.exec.DBExecUtils;
import com.seizia.chromite.model.impl.edit.AbstractCommandContext;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.model.navigator.DBNDatabaseFolder;
import com.seizia.chromite.model.navigator.DBNDatabaseNode;
import com.seizia.chromite.model.navigator.DBNEvent;
import com.seizia.chromite.model.navigator.meta.DBXTreeItem;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLDialect;
import com.seizia.chromite.model.sql.SQLUtils;
import com.seizia.chromite.model.struct.DBSDataType;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.rdb.DBSCatalog;
import com.seizia.chromite.model.struct.rdb.DBSPackage;
import com.seizia.chromite.model.struct.rdb.DBSProcedure;
import com.seizia.chromite.model.struct.rdb.DBSSchema;
import com.seizia.chromite.model.struct.rdb.DBSSequence;
import com.seizia.chromite.model.struct.rdb.DBSTable;
import com.seizia.chromite.model.struct.rdb.DBSTrigger;
import com.seizia.chromite.model.struct.rdb.DBSView;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.properties.ObjectAttributeDescriptor;
import com.seizia.chromite.runtime.properties.ObjectPropertyDescriptor;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Object Explorer의 "Modify", "Script as &gt; ALTER To", "New View… / New Procedure… / New Function…"이 쓰는 스크립트 만들기
 * (UI 없는 부분, 메뉴는 {@link SourceEditCommands}).
 *
 * 설계:
 * <ul>
 *     <li>ALTER 스크립트(Modify): SWT 소스 편집기의 저장 경로를 UI 없이 그대로 따른다. SWT는 소스 탭
 *     ({@code SQLSourceViewer}·{@code SQLServerSourceViewEditor}·{@code PostgreSourceViewEditor}·{@code MySQLSourceViewEditor}·
 *     {@code OracleSourceDeclarationEditor})이 고친 글을 속성 {@code objectDefinitionText}로 명령 묶음에 넣고, 저장할 때 객체 관리자
 *     ({@code SQLObjectEditor}의 {@code ObjectChangeCommand} → 드라이버의 {@code addObjectModifyActions})가 실행 동작을 만든다.
 *     여기서는 같은 속성의 변경 명령({@link DBECommandProperty})을 읽은 정의 그대로 넣어 같은 실행 동작을 얻고, 실행하지 않고 글로 바꿔
 *     쿼리 창에 넣는다. 그래서 방언별 문장은 드라이버가 정한다: SQL Server {@code ALTER PROCEDURE/FUNCTION/VIEW/TRIGGER}
 *     ({@code SQLServerUtils.changeCreateToAlterDDL}), PostgreSQL {@code CREATE OR REPLACE}(트리거는 DROP + CREATE),
 *     MySQL/MariaDB {@code DROP … IF EXISTS} + {@code CREATE}, Oracle {@code ALTER SESSION SET CURRENT_SCHEMA} + {@code CREATE OR REPLACE}.
 *     명령 묶음은 저장하지 않고 버리며, 객체의 값을 쓰지 않는다(속성 쓰기 없이 명령만 만든다).</li>
 *     <li>스크립트 글: {@code SQLUtils.generateScript}처럼 동작을 차례로 잇되, 쿼리 창이 실행할 수 있게 구분자를 정한다.
 *     배치 구분자가 있는 방언(SQL Server의 {@code GO})은 동작마다 {@code GO} 줄을 둔다({@code CREATE/ALTER PROCEDURE}는 배치의 첫 문장이어야 한다).
 *     그 밖에는 {@code generateScript}와 같이 기본 구분자({@code ;})를 붙이고, 복합 동작(루틴 본문)은 구분자를 바꾸는 방언(MySQL)에서
 *     {@code DELIMITER $$} … {@code DELIMITER ;} 사이에 둔다. MySQL 방언에는 {@code BEGIN … END} 블록이 없어(MySQLDialect#getBlockBoundStrings)
 *     쿼리 창의 구문 분석기가 본문 안의 {@code ;}에서 나누기 때문이다. 쿼리 창은 {@code DELIMITER} 줄을 처리한다.</li>
 *     <li>DB/스키마 맞추기: 새 쿼리 창은 연결의 기본 실행 문맥에서 실행하거나 자기 세션을 열 때 그 문맥의 기본값을 복사하므로
 *     ({@link QueryWindowContext}), 스크립트를 만들기 전에 그 문맥의 기본 카탈로그·스키마를
 *     객체가 있는 곳으로 바꾼다(편집기의 기본 DB/스키마 콤보와 같은 {@link DBExecUtils#setExecutionContextDefaults}). PostgreSQL은 DB를
 *     바꾸면 기본 인스턴스가 바뀌므로 다른 DB의 객체도 그 DB에서 실행된다. SQL Server는 SSMS처럼 맨 앞에 {@code USE} 배치를 넣어,
 *     나중에 기본 DB를 바꿔도 객체의 DB에서 실행되게 한다(문장은 {@code SQLServerObjectManager.addDatabaseSwitchAction1}과 같음).</li>
 *     <li>새 객체 템플릿(New View/Procedure/Function): 객체를 모델에 미리 만들지 않고(탐색기에 저장 안 된 노드가 생기지 않게) 바로 실행할 수 있는
 *     CREATE 문을 만든다. 모델에 같은 기본 본문이 있는 것은 그것을 따랐다({@code MySQLProcedure#getDeclaration}의 새 객체 본문,
 *     SWT 구성기 {@code PostgreProcedureConfigurator}·{@code OracleProcedureConfigurator}의 본문). 이름은 폴더에 없는 기본 이름이다.
 *     시퀀스·트리거·패키지·형식·이벤트 트리거·행 보안 정책·큐({@link #getTemplateKind})도 같은 방식이다. SWT는 이것들을 구성기 대화상자
 *     ({@code PostgreSequenceConfigurator}, {@code OraclePackageConfigurator} 등)로 이름만 받아 모델 기본값으로 만들었지만, 본문(트리거 함수,
 *     패키지 본문, 형식의 속성)은 어차피 사용자가 써야 하므로 실행할 수 있는 틀을 쿼리 창에 연다. 틀은 실행해도 해가 없게 만든다:
 *     같은 이름을 덮어쓰는 {@code OR REPLACE}를 쓰지 않고(이름 확인은 폴더 안만 본다), 트리거 본문은 아무것도 바꾸지 않으며,
 *     뷰의 트리거(INSTEAD OF, 빈 본문이면 행을 버림)는 만들지 않는다. 표를 고를 수 없는 스키마 수준 트리거 폴더는 자리 표시 이름의
 *     표를 쓰므로 고치지 않고 실행하면 실패한다.</li>
 *     <li>실행 뒤 새로 고침: Modify는 객체 노드를, New는 폴더 노드를 다시 읽는다({@code DBNNode#refreshNode}, Object Explorer가
 *     모델 이벤트로 트리에 반영). 폴더를 다시 읽으면 모델이 폴더의 부모(스키마)를 다시 읽는다(Object Explorer의 새로 고침과 같음).</li>
 * </ul>
 * 스크립트를 만드는 메서드는 DB를 읽으므로 작업 스레드에서 부른다. {@code can*}·{@link #getNewKinds}는 메모리만 보므로 EDT에서 부른다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-source-editor.md)
 * - 2026-11-07 주석만: 쿼리 창마다 별도 연결이면 새 창이 주 문맥의 기본값을 복사함 (docs/chromite/transactions.md "쿼리 창마다 별도 연결")
 * - 2026-11-08 {@link #bindDefaultContext}를 패키지 안에 열어 Execute Procedure 창도 루틴의 DB·스키마에서 실행되게 함 (docs/chromite/swing-routines.md)
 * - 2026-11-08 {@link #generateContainerScript}: 스키마 비교 스크립트도 같은 구분자·DB 맞추기로 만든다 (docs/chromite/schema-compare.md)
 * - 2026-11-08 시퀀스·트리거·DDL 트리거·이벤트 트리거·정책·패키지·형식·큐 틀({@link #getTemplateKind}): Object Explorer의 "New …"
 *   (docs/chromite/swing-create-objects.md)
 */
final class SourceScripts {

    private static final Log log = Log.getLog(SourceScripts.class);

    /**
     * Kinds of "New …" templates: the explorer's New View/Procedure/Function items ({@link #getNewKinds}) and the
     * template kinds of the generic "New …" item ({@link #getTemplateKind}).
     */
    enum NewObjectKind {
        VIEW,
        PROCEDURE,
        FUNCTION,
        SEQUENCE,
        /** A DML trigger of a table (or of a table to fill in, in a schema-level triggers folder). */
        TRIGGER,
        /** SQL Server DDL trigger ON DATABASE, Oracle schema trigger. */
        DATABASE_TRIGGER,
        /** PostgreSQL event trigger. */
        EVENT_TRIGGER,
        /** PostgreSQL row security policy. */
        POLICY,
        PACKAGE,
        TYPE,
        /** Oracle Advanced Queuing queue. */
        QUEUE
    }

    /**
     * Dialects that have templates. Drivers outside these get no "New …" items; "Modify" does not depend on it.
     */
    private enum Dialect {
        SQL_SERVER,
        POSTGRESQL,
        MYSQL,
        ORACLE
    }

    /**
     * The source property of each object class (null: none), read once per class from the {@code @Property} annotations.
     */
    private static final Map<Class<?>, Optional<ObjectPropertyDescriptor>> SOURCE_PROPERTIES = new ConcurrentHashMap<>();

    /**
     * Delimiter of complex statements between {@code DELIMITER} lines, as {@code SQLUtils.generateScript} uses.
     */
    private static final String BLOCK_DELIMITER = "$$"; //$NON-NLS-1$

    private SourceScripts() {
    }

    //////////////////////////////////////////////////////////
    // Modify / Script as ALTER

    /**
     * True if the object's source can be edited as an ALTER script: a saved procedure, function, view, trigger or package
     * whose driver edits its definition ({@code objectDefinitionText}, as the SWT source editors) with an object editor.
     */
    static boolean canModify(@Nullable DBSObject object) {
        if (!(object instanceof DBPScriptObject) || !object.isPersisted()
            || !(object instanceof DBSProcedure || object instanceof DBSView || object instanceof DBSTrigger || object instanceof DBSPackage)) {
            return false;
        }
        DBEObjectEditor<DBSObject> editor = getObjectEditor(object);
        return editor != null && editor.canEditObject(object) && getSourceProperty(object.getClass()) != null;
    }

    /**
     * Makes the ALTER script of the object (Modify, Script as ALTER) and binds the connection's default context to the
     * object's database and schema first.
     */
    @NotNull
    static String generateAlter(@NotNull DBRProgressMonitor monitor, @NotNull DBSObject object) throws DBException {
        DBPDataSource dataSource = object.getDataSource();
        DBEObjectEditor<DBSObject> editor = getObjectEditor(object);
        ObjectPropertyDescriptor property = getSourceProperty(object.getClass());
        if (dataSource == null || editor == null || property == null) {
            throw new DBException(NLS.bind(SourceEditMessages.source_error_not_modifiable, DBUtils.getObjectFullName(object, DBPEvaluationContext.UI)));
        }
        DBCExecutionContext context = bindDefaultContext(monitor, object.getParentObject() != null ? object.getParentObject() : object);
        // Reads the definition as the SWT source tab shows it (SQLSourceViewer#getSourceOptions); the object managers build
        // their statements from the definition read here
        String source = ((DBPScriptObject) object).getObjectDefinitionText(monitor, Map.of(DBPScriptObject.OPTION_DDL_SOURCE, true));

        AbstractCommandContext commandContext = new AbstractCommandContext(context, false) {
        };
        commandContext.addCommand(new DBECommandProperty<>(object, editor.makePropertyHandler(object, property), source, source), null);
        List<DBEPersistAction> actions = new ArrayList<>();
        Map<String, Object> validateOptions = new HashMap<>();
        for (DBECommand<?> command : commandContext.getFinalCommands()) {
            command.validateCommand(monitor, validateOptions);
            Map<String, Object> options = new HashMap<>();
            options.put(DBPScriptObject.OPTION_OBJECT_SAVE, true);
            DBEPersistAction[] commandActions = command.getPersistActions(monitor, context, options);
            if (commandActions != null) {
                actions.addAll(List.of(commandActions));
            }
        }
        if (actions.isEmpty()) {
            throw new DBException(NLS.bind(SourceEditMessages.source_error_no_script, DBUtils.getObjectFullName(object, DBPEvaluationContext.UI)));
        }
        return renderScript(dataSource, getDatabaseSwitch(object), actions);
    }

    /**
     * Joins actions made for objects of a container (Schema Compare's script) into a query window script, as the
     * Modify script: the connection's default context is bound to the container first, and SQL Server scripts start
     * with {@code USE <database>}.
     */
    @NotNull
    static String generateContainerScript(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBSObject container,
        @NotNull List<DBEPersistAction> actions
    ) throws DBException {
        DBPDataSource dataSource = container.getDataSource();
        if (dataSource == null) {
            throw new DBException(NLS.bind(SourceEditMessages.source_error_not_connected, container.getName()));
        }
        bindDefaultContext(monitor, container);
        return renderScript(dataSource, getDatabaseSwitch(container), actions);
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static DBEObjectEditor<DBSObject> getObjectEditor(@NotNull DBSObject object) {
        return DBWorkbench.getPlatform().getEditorsRegistry().getObjectManager(object.getClass(), DBEObjectEditor.class);
    }

    @Nullable
    private static ObjectPropertyDescriptor getSourceProperty(@NotNull Class<?> objectClass) {
        return SOURCE_PROPERTIES.computeIfAbsent(objectClass, c -> {
            for (ObjectPropertyDescriptor property : ObjectAttributeDescriptor.extractAnnotations(null, c, null, null)) {
                if (DBConstants.PARAM_OBJECT_DEFINITION_TEXT.equals(property.getId()) && property.isEditPossible()) {
                    return Optional.of(property);
                }
            }
            return Optional.empty();
        }).orElse(null);
    }

    /**
     * Joins the actions into a script the query window runs, as {@code SQLUtils.generateScript}: the statement delimiter after
     * each action, and a complex action (a routine body) between {@code DELIMITER $$} and {@code DELIMITER ;} lines for
     * dialects that redefine the delimiter (MySQL: its dialect has no {@code BEGIN … END} blocks, so the parser would split
     * the body at {@code ;}). Dialects with a batch delimiter (SQL Server {@code GO}) get a batch delimiter line after each
     * action instead, since {@code CREATE/ALTER PROCEDURE} must start its batch. Unlike {@code generateScript}, no empty
     * {@code $$} statement follows the {@code DELIMITER} line, and lines end with LF (the editor's line separator).
     *
     * @param prefix a first statement (SQL Server {@code USE}), or null
     */
    @NotNull
    private static String renderScript(@NotNull DBPDataSource dataSource, @Nullable String prefix, @NotNull List<DBEPersistAction> actions) {
        SQLDialect dialect = dataSource.getSQLDialect();
        String[] batchDelimiters = dialect.getBatchDelimiters();
        String batchDelimiter = batchDelimiters.length > 0 ? batchDelimiters[0] : null;
        String delimiter = SQLUtils.getScriptLineDelimiter(dialect);
        String redefiner = dialect.getScriptDelimiterRedefiner();
        StringBuilder script = new StringBuilder();
        if (prefix != null) {
            appendStatement(script, prefix, batchDelimiter, delimiter);
        }
        for (DBEPersistAction action : actions) {
            String text = action.getScript();
            if (CommonUtils.isEmptyTrimmed(text)) {
                continue;
            }
            text = text.strip().replace("\r\n", "\n"); //$NON-NLS-1$ //$NON-NLS-2$
            if (action.getType() == DBEPersistAction.ActionType.COMMENT) {
                script.append(text).append('\n');
            } else if (batchDelimiter == null && action.isComplex() && redefiner != null && !redefiner.equals(delimiter.strip())) {
                script.append(redefiner).append(' ').append(BLOCK_DELIMITER).append('\n');
                script.append(text);
                if (!text.endsWith(BLOCK_DELIMITER)) {
                    script.append(BLOCK_DELIMITER);
                }
                script.append('\n').append(redefiner).append(' ').append(delimiter.strip()).append("\n\n"); //$NON-NLS-1$
            } else {
                appendStatement(script, text, batchDelimiter, delimiter);
            }
        }
        return script.toString();
    }

    /**
     * Appends a statement and its delimiter (or a batch delimiter line), then an empty line.
     */
    private static void appendStatement(
        @NotNull StringBuilder script,
        @NotNull String statement,
        @Nullable String batchDelimiter,
        @NotNull String delimiter
    ) {
        script.append(statement);
        if (batchDelimiter != null) {
            script.append('\n').append(batchDelimiter);
        } else if (!statement.endsWith(delimiter.strip())) {
            script.append(delimiter);
        }
        script.append("\n\n"); //$NON-NLS-1$
    }

    /**
     * SQL Server: {@code USE <database>} of the object (SSMS "Modify" and its templates start with it), else null.
     */
    @Nullable
    private static String getDatabaseSwitch(@NotNull DBSObject object) {
        if (!(object.getDataSource() instanceof SQLServerDataSource)) {
            return null;
        }
        DBSCatalog database = getSelfOrParent(DBSCatalog.class, object);
        return database == null ? null : "USE " + DBUtils.getQuotedIdentifier(database); //$NON-NLS-1$
    }

    /**
     * Makes the object's catalog and schema the defaults of the connection's main context, which new query windows use
     * or copy into their own session when they open it ({@link QueryWindowContext}), with the same call as the editor's
     * default database/schema combo. Failing to do so is not fatal: the scripts name their schema.
     *
     * @return the main context after the change (PostgreSQL: the context of the object's database)
     */
    @NotNull
    static DBCExecutionContext bindDefaultContext(@NotNull DBRProgressMonitor monitor, @NotNull DBSObject container) throws DBException {
        DBPDataSource dataSource = container.getDataSource();
        DBCExecutionContext context = dataSource == null ? null : DBUtils.getDefaultContext(dataSource.getDefaultInstance(), false);
        if (dataSource == null || context == null) {
            throw new DBException(NLS.bind(SourceEditMessages.source_error_not_connected, container.getName()));
        }
        DBCExecutionContextDefaults<?, ?> defaults = context.getContextDefaults();
        if (defaults != null && (defaults.supportsCatalogChange() || defaults.supportsSchemaChange())) {
            DBSCatalog catalog = getSelfOrParent(DBSCatalog.class, container);
            DBSSchema schema = getSelfOrParent(DBSSchema.class, container);
            DBSCatalog currentCatalog = defaults.getDefaultCatalog();
            DBSSchema currentSchema = defaults.getDefaultSchema();
            boolean catalogChange = catalog != null && defaults.supportsCatalogChange() && catalog != currentCatalog;
            boolean schemaChange = schema != null && defaults.supportsSchemaChange() && schema != currentSchema;
            if (catalogChange || schemaChange) {
                try {
                    // The same arguments as the editor's combo (SelectActiveSchemaHandler): the catalog, and the schema in it
                    DBExecUtils.setExecutionContextDefaults(monitor, dataSource, context,
                        catalog != null && defaults.supportsCatalogChange() ? catalog.getName() : null,
                        currentCatalog != null ? currentCatalog.getName() : null,
                        schema != null && defaults.supportsSchemaChange() ? schema.getName() : null);
                } catch (DBException e) {
                    log.warn("Can't make '" + container.getName() + "' the default of the connection", e);
                }
                DBCExecutionContext changed = DBUtils.getDefaultContext(dataSource.getDefaultInstance(), false);
                if (changed != null) {
                    context = changed;
                }
            }
        }
        return context;
    }

    @Nullable
    private static <T> T getSelfOrParent(@NotNull Class<T> type, @NotNull DBSObject object) {
        return type.isInstance(object) ? type.cast(object) : DBUtils.getParentOfType(type, object);
    }

    //////////////////////////////////////////////////////////
    // New View / Procedure / Function

    /**
     * The "New …" templates for a folder node: views of a views folder, procedures and/or functions of a routines folder
     * of a schema or database. The folder's item property tells routine folders apart (Oracle {@code proceduresOnly} /
     * {@code functionsOnly}); folders listing both offer both, as their drivers keep them in one list.
     */
    @NotNull
    static Set<NewObjectKind> getNewKinds(@Nullable Object node) {
        Set<NewObjectKind> kinds = EnumSet.noneOf(NewObjectKind.class);
        if (!(node instanceof DBNDatabaseFolder folder) || !(folder.getValueObject() instanceof DBSObject container)
            || getDialect(container.getDataSource()) == null || !(container instanceof DBSSchema || container instanceof DBSCatalog)) {
            return kinds;
        }
        Class<? extends DBSObject> childClass = folder.getChildrenClass();
        if (childClass == null) {
            return kinds;
        }
        if (DBSView.class.isAssignableFrom(childClass) && !PostgreMaterializedView.class.isAssignableFrom(childClass)) {
            kinds.add(NewObjectKind.VIEW);
        } else if (DBSProcedure.class.isAssignableFrom(childClass)) {
            DBXTreeItem items = folder.getItemsMeta();
            String property = items == null || items.getPropertyName() == null ? "" : items.getPropertyName().toLowerCase(Locale.ENGLISH);
            boolean functionsOnly = property.contains("function") && !property.contains("procedure"); //$NON-NLS-1$ //$NON-NLS-2$
            boolean proceduresOnly = property.contains("procedure") && property.contains("only"); //$NON-NLS-1$ //$NON-NLS-2$
            if (!functionsOnly) {
                kinds.add(NewObjectKind.PROCEDURE);
            }
            if (!proceduresOnly) {
                kinds.add(NewObjectKind.FUNCTION);
            }
        }
        return kinds;
    }

    /**
     * The CREATE template kind the generic "New …" command offers for a folder: sequences, triggers, packages, types,
     * PostgreSQL event triggers and row security policies, Oracle queues; null for other folders and drivers. A
     * triggers folder of a table gets a trigger of that table, one of a schema (all triggers of the schema) a trigger
     * of a table to fill in; views get none (see the class comment).
     */
    @Nullable
    static NewObjectKind getTemplateKind(@Nullable Object node) {
        if (!(node instanceof DBNDatabaseFolder folder) || !(folder.getValueObject() instanceof DBSObject container)
            || getDialect(container.getDataSource()) == null) {
            return null;
        }
        Class<? extends DBSObject> childClass = folder.getChildrenClass();
        if (childClass == null) {
            return null;
        }
        boolean schemaLevel = container instanceof DBSSchema || container instanceof DBSCatalog;
        boolean table = container instanceof DBSTable dbTable && !dbTable.isView() && !(container instanceof DBSView);
        if (DBSSequence.class.isAssignableFrom(childClass)) {
            return schemaLevel ? NewObjectKind.SEQUENCE : null;
        } else if (PostgreEventTrigger.class.isAssignableFrom(childClass)) {
            return container instanceof PostgreDatabase ? NewObjectKind.EVENT_TRIGGER : null;
        } else if (SQLServerDatabaseTrigger.class.isAssignableFrom(childClass) || OracleSchemaTrigger.class.isAssignableFrom(childClass)) {
            return schemaLevel ? NewObjectKind.DATABASE_TRIGGER : null;
        } else if (DBSTrigger.class.isAssignableFrom(childClass)) {
            return schemaLevel || table ? NewObjectKind.TRIGGER : null;
        } else if (PostgreTablePolicy.class.isAssignableFrom(childClass)) {
            return table ? NewObjectKind.POLICY : null;
        } else if (DBSPackage.class.isAssignableFrom(childClass)) {
            return schemaLevel ? NewObjectKind.PACKAGE : null;
        } else if (DBSDataType.class.isAssignableFrom(childClass)) {
            return container instanceof DBSSchema ? NewObjectKind.TYPE : null;
        } else if (OracleQueue.class.isAssignableFrom(childClass)) {
            return container instanceof DBSSchema ? NewObjectKind.QUEUE : null;
        }
        return null;
    }

    /**
     * Makes the CREATE template of a new object in the folder and binds the connection's default context to the
     * folder's database and schema.
     */
    @NotNull
    static String generateNew(@NotNull DBRProgressMonitor monitor, @NotNull DBNDatabaseFolder folder, @NotNull NewObjectKind kind) throws DBException {
        if (!(folder.getValueObject() instanceof DBSObject container) || container.getDataSource() == null) {
            throw new DBException(NLS.bind(SourceEditMessages.source_error_not_connected, folder.getNodeDisplayName()));
        }
        DBPDataSource dataSource = container.getDataSource();
        Dialect dialect = getDialect(dataSource);
        if (dialect == null) {
            throw new DBException(NLS.bind(SourceEditMessages.source_error_no_template, dataSource.getContainer().getDriver().getName()));
        }
        bindDefaultContext(monitor, container);

        Set<String> existingNames = new HashSet<>();
        for (DBSObject child : CommonUtils.safeCollection(folder.getChildrenObjects(monitor))) {
            existingNames.add(child.getName().toLowerCase(Locale.ENGLISH));
        }
        String baseName = getBaseName(dialect, kind);
        String name = baseName;
        for (int i = 1; existingNames.contains(name.toLowerCase(Locale.ENGLISH)); i++) {
            name = baseName + i;
        }
        if (kind != NewObjectKind.VIEW && kind != NewObjectKind.PROCEDURE && kind != NewObjectKind.FUNCTION) {
            return renderScript(dataSource, getDatabaseSwitch(container), getObjectTemplate(dialect, kind, container, name));
        }
        String quotedName = DBUtils.getQuotedIdentifier(dataSource, name);
        String qualifiedName = DBUtils.getQuotedIdentifier(container) + dataSource.getSQLDialect().getStructSeparator() + quotedName;

        String body = switch (dialect) {
            case SQL_SERVER -> switch (kind) {
                case VIEW -> "CREATE VIEW " + qualifiedName + "\nAS\nSELECT 1 AS Column1\n";
                case PROCEDURE -> "CREATE PROCEDURE " + qualifiedName + "\n    @Param1 int = 0\nAS\nBEGIN\n    SET NOCOUNT ON;\n\n"
                    + "    SELECT @Param1 AS Param1;\nEND\n";
                case FUNCTION -> "CREATE FUNCTION " + qualifiedName + "\n(\n    @Param1 int\n)\nRETURNS int\nAS\nBEGIN\n    RETURN @Param1;\nEND\n";
                default -> throw new IllegalArgumentException(kind.name());
            };
            case POSTGRESQL -> switch (kind) {
                case VIEW -> "CREATE OR REPLACE VIEW " + qualifiedName + " AS\nSELECT 1 AS column1;\n";
                case PROCEDURE -> "CREATE OR REPLACE PROCEDURE " + qualifiedName + "(param1 integer)\n    LANGUAGE plpgsql\nAS $procedure$\n"
                    + "BEGIN\n    RAISE NOTICE 'param1: %', param1;\nEND;\n$procedure$;\n";
                case FUNCTION -> "CREATE OR REPLACE FUNCTION " + qualifiedName + "(param1 integer)\n    RETURNS integer\n    LANGUAGE plpgsql\n"
                    + "AS $function$\nBEGIN\n    RETURN param1;\nEND;\n$function$;\n";
                default -> throw new IllegalArgumentException(kind.name());
            };
            case MYSQL -> switch (kind) {
                case VIEW -> "CREATE VIEW " + qualifiedName + " AS\nSELECT 1 AS column1;\n";
                case PROCEDURE -> "CREATE PROCEDURE " + qualifiedName + "(IN param1 INT)\nBEGIN\n    SELECT param1;\nEND\n";
                case FUNCTION -> "CREATE FUNCTION " + qualifiedName + "(param1 INT)\nRETURNS INT\nDETERMINISTIC\nBEGIN\n    RETURN param1;\nEND\n";
                default -> throw new IllegalArgumentException(kind.name());
            };
            case ORACLE -> switch (kind) {
                case VIEW -> "CREATE OR REPLACE VIEW " + qualifiedName + " AS\nSELECT 1 AS COLUMN1 FROM DUAL;\n";
                case PROCEDURE -> "CREATE OR REPLACE PROCEDURE " + qualifiedName + "(P_PARAM1 IN NUMBER) IS\nBEGIN\n    NULL;\nEND " + quotedName + ";\n";
                case FUNCTION -> "CREATE OR REPLACE FUNCTION " + qualifiedName + "(P_PARAM1 IN NUMBER) RETURN NUMBER IS\nBEGIN\n"
                    + "    RETURN P_PARAM1;\nEND " + quotedName + ";\n";
                default -> throw new IllegalArgumentException(kind.name());
            };
        };
        // A routine body is a complex statement (as the drivers' managers mark theirs): MySQL needs it between DELIMITER lines
        DBEPersistAction action = new SQLDatabasePersistAction(kind.name(), body, kind != NewObjectKind.VIEW);
        return renderScript(dataSource, getDatabaseSwitch(container), List.of(action));
    }

    @Nullable
    private static Dialect getDialect(@Nullable DBPDataSource dataSource) {
        if (dataSource instanceof SQLServerDataSource) {
            return Dialect.SQL_SERVER;
        } else if (dataSource instanceof PostgreDataSource) {
            return Dialect.POSTGRESQL;
        } else if (dataSource instanceof MySQLDataSource) {
            return Dialect.MYSQL;
        } else if (dataSource instanceof OracleDataSource) {
            return Dialect.ORACLE;
        }
        return null;
    }

    /**
     * Default name of a new object in the naming style of the dialect (SSMS-like for SQL Server, the folded case for Oracle).
     */
    @NotNull
    private static String getBaseName(@NotNull Dialect dialect, @NotNull NewObjectKind kind) {
        String name = switch (dialect) {
            case SQL_SERVER -> switch (kind) {
                case VIEW -> "NewView"; //$NON-NLS-1$
                case PROCEDURE -> "NewProcedure"; //$NON-NLS-1$
                case FUNCTION -> "NewFunction"; //$NON-NLS-1$
                case SEQUENCE -> "NewSequence"; //$NON-NLS-1$
                case TRIGGER -> "NewTrigger"; //$NON-NLS-1$
                case DATABASE_TRIGGER -> "NewDatabaseTrigger"; //$NON-NLS-1$
                case TYPE -> "NewType"; //$NON-NLS-1$
                default -> "NewObject"; //$NON-NLS-1$
            };
            default -> switch (kind) {
                case VIEW -> "new_view"; //$NON-NLS-1$
                case PROCEDURE -> "new_procedure"; //$NON-NLS-1$
                case FUNCTION -> "new_function"; //$NON-NLS-1$
                case SEQUENCE -> "new_sequence"; //$NON-NLS-1$
                case TRIGGER -> "new_trigger"; //$NON-NLS-1$
                case DATABASE_TRIGGER -> "new_ddl_trigger"; //$NON-NLS-1$
                case EVENT_TRIGGER -> "new_event_trigger"; //$NON-NLS-1$
                case POLICY -> "new_policy"; //$NON-NLS-1$
                case PACKAGE -> "new_package"; //$NON-NLS-1$
                case TYPE -> "new_type"; //$NON-NLS-1$
                case QUEUE -> "new_queue"; //$NON-NLS-1$
            };
        };
        return dialect == Dialect.ORACLE ? name.toUpperCase(Locale.ENGLISH) : name;
    }

    /**
     * The statements of a {@link #getTemplateKind} template. Bodies that the drivers' managers mark complex (routine,
     * trigger, package and Oracle type bodies) are complex actions, so MySQL gets them between {@code DELIMITER} lines.
     *
     * @param container the folder's object: the schema (catalog), the table of a table's triggers or policies, the
     *                  PostgreSQL database of event triggers, the SQL Server database of DDL triggers
     * @param name      the new object's name, not in the folder yet
     */
    @NotNull
    private static List<DBEPersistAction> getObjectTemplate(
        @NotNull Dialect dialect,
        @NotNull NewObjectKind kind,
        @NotNull DBSObject container,
        @NotNull String name
    ) {
        DBPDataSource dataSource = container.getDataSource();
        String separator = String.valueOf(dataSource.getSQLDialect().getStructSeparator());
        // Triggers and policies of a table belong to the table's schema (MySQL: database)
        DBSObject owner = container instanceof DBSTable ? container.getParentObject() : container;
        String quotedName = DBUtils.getQuotedIdentifier(dataSource, name);
        String qualifiedName = owner == null ? quotedName : DBUtils.getQuotedIdentifier(owner) + separator + quotedName;
        String tableName = container instanceof DBSTable ? DBUtils.getQuotedIdentifier(container) : DBUtils.getQuotedIdentifier(dataSource,
            switch (dialect) {
                case SQL_SERVER -> "TableName"; //$NON-NLS-1$
                case ORACLE -> "TABLE_NAME"; //$NON-NLS-1$
                default -> "table_name"; //$NON-NLS-1$
            });
        String table = owner == null ? tableName : DBUtils.getQuotedIdentifier(owner) + separator + tableName;
        boolean executeFunction = !(dataSource instanceof PostgreDataSource postgre) || postgre.isServerVersionAtLeast(11, 0);
        String executeKeyword = executeFunction ? "EXECUTE FUNCTION " : "EXECUTE PROCEDURE "; //$NON-NLS-1$ //$NON-NLS-2$
        List<DBEPersistAction> actions = new ArrayList<>();
        switch (kind) {
            case SEQUENCE -> actions.add(new SQLDatabasePersistAction(kind.name(), switch (dialect) {
                case SQL_SERVER -> "CREATE SEQUENCE " + qualifiedName + "\n    AS bigint\n    START WITH 1\n    INCREMENT BY 1\n    NO CYCLE\n";
                case POSTGRESQL -> "CREATE SEQUENCE " + qualifiedName + "\n    INCREMENT BY 1\n    MINVALUE 1\n    START WITH 1\n    NO CYCLE;\n";
                case MYSQL -> "CREATE SEQUENCE " + qualifiedName + "\n    START WITH 1\n    INCREMENT BY 1\n    MINVALUE 1\n    NOCYCLE;\n";
                case ORACLE -> "CREATE SEQUENCE " + qualifiedName + "\n    START WITH 1\n    INCREMENT BY 1\n    NOCACHE\n    NOCYCLE;\n";
            }));
            case TRIGGER -> {
                switch (dialect) {
                    case SQL_SERVER -> actions.add(new SQLDatabasePersistAction(kind.name(), "CREATE TRIGGER " + qualifiedName + "\nON " + table
                        + "\nAFTER INSERT, UPDATE\nAS\nBEGIN\n    SET NOCOUNT ON;\nEND\n", true));
                    case POSTGRESQL -> {
                        // A PostgreSQL trigger runs a trigger function; BEFORE ... RETURN NEW changes nothing
                        String function = owner == null ? DBUtils.getQuotedIdentifier(dataSource, name + "_fn") //$NON-NLS-1$
                            : DBUtils.getQuotedIdentifier(owner) + separator + DBUtils.getQuotedIdentifier(dataSource, name + "_fn"); //$NON-NLS-1$
                        actions.add(new SQLDatabasePersistAction(kind.name(), "CREATE FUNCTION " + function + "()\n    RETURNS trigger\n"
                            + "    LANGUAGE plpgsql\nAS $function$\nBEGIN\n    RETURN NEW;\nEND;\n$function$;\n", true));
                        actions.add(new SQLDatabasePersistAction(kind.name(), "CREATE TRIGGER " + quotedName + "\n    BEFORE INSERT OR UPDATE ON "
                            + table + "\n    FOR EACH ROW\n    " + executeKeyword + function + "();\n"));
                    }
                    case MYSQL -> actions.add(new SQLDatabasePersistAction(kind.name(), "CREATE TRIGGER " + qualifiedName + "\nBEFORE INSERT ON "
                        + table + "\nFOR EACH ROW\nBEGIN\nEND\n", true));
                    case ORACLE -> actions.add(new SQLDatabasePersistAction(kind.name(), "CREATE TRIGGER " + qualifiedName
                        + "\nBEFORE INSERT OR UPDATE ON " + table + "\nFOR EACH ROW\nBEGIN\n    NULL;\nEND;\n", true));
                }
            }
            case DATABASE_TRIGGER -> actions.add(new SQLDatabasePersistAction(kind.name(), switch (dialect) {
                case SQL_SERVER -> "CREATE TRIGGER " + quotedName + "\nON DATABASE\nFOR CREATE_TABLE, ALTER_TABLE, DROP_TABLE\nAS\nBEGIN\n"
                    + "    SET NOCOUNT ON;\nEND\n";
                case ORACLE -> "CREATE TRIGGER " + qualifiedName + "\nAFTER DDL ON " + DBUtils.getQuotedIdentifier(container)
                    + ".SCHEMA\nBEGIN\n    NULL;\nEND;\n";
                default -> throw new IllegalArgumentException(kind.name());
            }, true));
            case EVENT_TRIGGER -> {
                String function = DBUtils.getQuotedIdentifier(dataSource, "public") + separator //$NON-NLS-1$
                    + DBUtils.getQuotedIdentifier(dataSource, name + "_fn"); //$NON-NLS-1$
                actions.add(new SQLDatabasePersistAction(kind.name(), "CREATE FUNCTION " + function + "()\n    RETURNS event_trigger\n"
                    + "    LANGUAGE plpgsql\nAS $function$\nBEGIN\n    NULL;\nEND;\n$function$;\n", true));
                actions.add(new SQLDatabasePersistAction(kind.name(), "CREATE EVENT TRIGGER " + quotedName + "\n    ON ddl_command_end\n    "
                    + executeKeyword + function + "();\n"));
            }
            case POLICY -> actions.add(new SQLDatabasePersistAction(kind.name(), "CREATE POLICY " + quotedName + " ON " + table
                + "\n    AS PERMISSIVE\n    FOR SELECT\n    TO PUBLIC\n    USING (true);\n"));
            case PACKAGE -> {
                if (dialect == Dialect.ORACLE) {
                    actions.add(new SQLDatabasePersistAction(kind.name(), "CREATE PACKAGE " + qualifiedName
                        + " AS\n    PROCEDURE DO_WORK(P_PARAM1 IN NUMBER);\nEND " + quotedName + ";\n", true));
                    actions.add(new SQLDatabasePersistAction(kind.name(), "CREATE PACKAGE BODY " + qualifiedName
                        + " AS\n    PROCEDURE DO_WORK(P_PARAM1 IN NUMBER) IS\n    BEGIN\n        NULL;\n    END DO_WORK;\nEND " + quotedName + ";\n", true));
                } else {
                    // MariaDB packages (the MySQL driver shows the folder for MariaDB only)
                    actions.add(new SQLDatabasePersistAction(kind.name(), "CREATE PACKAGE " + qualifiedName
                        + "\n    PROCEDURE do_work(param1 INT);\nEND\n", true));
                    actions.add(new SQLDatabasePersistAction(kind.name(), "CREATE PACKAGE BODY " + qualifiedName
                        + "\n    PROCEDURE do_work(param1 INT)\n    BEGIN\n        SELECT param1;\n    END;\nEND\n", true));
                }
            }
            case TYPE -> actions.add(switch (dialect) {
                case SQL_SERVER -> new SQLDatabasePersistAction(kind.name(), "CREATE TYPE " + qualifiedName + "\n    FROM nvarchar(100) NULL\n");
                case POSTGRESQL -> new SQLDatabasePersistAction(kind.name(), "CREATE TYPE " + qualifiedName
                    + " AS (\n    id integer,\n    name text\n);\n");
                case ORACLE -> new SQLDatabasePersistAction(kind.name(), "CREATE TYPE " + qualifiedName
                    + " AS OBJECT (\n    ID NUMBER,\n    NAME VARCHAR2(100)\n);\n", true);
                default -> throw new IllegalArgumentException(kind.name());
            });
            case QUEUE -> {
                // DBMS_AQADM takes the names as strings; the queue table is named after the queue
                String queue = container.getName() + "." + name; //$NON-NLS-1$
                actions.add(new SQLDatabasePersistAction(kind.name(), "BEGIN\n    DBMS_AQADM.CREATE_QUEUE_TABLE(\n        queue_table        => '"
                    + queue + "_QT',\n        queue_payload_type => 'RAW');\n    DBMS_AQADM.CREATE_QUEUE(\n        queue_name  => '" + queue
                    + "',\n        queue_table => '" + queue + "_QT');\n    DBMS_AQADM.START_QUEUE(queue_name => '" + queue + "');\nEND;\n", true));
            }
            default -> throw new IllegalArgumentException(kind.name());
        }
        return actions;
    }

    //////////////////////////////////////////////////////////
    // Refresh

    /**
     * Reads the node again from the database after a script of its window ran (Object Explorer shows the change through
     * the navigator events). Nothing happens for a node that is gone or a connection that was closed.
     */
    static void refreshAfterRun(@NotNull DBNDatabaseNode node) {
        DBPDataSource dataSource = node.getDataSource();
        if (node.isDisposed() || dataSource == null || !dataSource.getContainer().isConnected()) {
            return;
        }
        new AbstractJob("Refresh " + node.getNodeDisplayName()) { //$NON-NLS-1$
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                try {
                    if (!node.isDisposed()) {
                        node.refreshNode(monitor, DBNEvent.FORCE_REFRESH);
                    }
                } catch (DBException e) {
                    log.debug("Can't refresh '" + node.getNodeDisplayName() + "'", e);
                }
                return Status.OK_STATUS;
            }
        }.schedule();
    }
}
