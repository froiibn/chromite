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
import com.seizia.chromite.ext.mysql.model.MySQLDataSource;
import com.seizia.chromite.ext.oracle.model.OracleDataSource;
import com.seizia.chromite.ext.postgresql.model.PostgreDataSource;
import com.seizia.chromite.ext.postgresql.model.PostgreMaterializedView;
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
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.rdb.DBSCatalog;
import com.seizia.chromite.model.struct.rdb.DBSPackage;
import com.seizia.chromite.model.struct.rdb.DBSProcedure;
import com.seizia.chromite.model.struct.rdb.DBSSchema;
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
 *     SWT 구성기 {@code PostgreProcedureConfigurator}·{@code OracleProcedureConfigurator}의 본문). 이름은 폴더에 없는 기본 이름이다.</li>
 *     <li>실행 뒤 새로 고침: Modify는 객체 노드를, New는 폴더 노드를 다시 읽는다({@code DBNNode#refreshNode}, Object Explorer가
 *     모델 이벤트로 트리에 반영). 폴더를 다시 읽으면 모델이 폴더의 부모(스키마)를 다시 읽는다(Object Explorer의 새로 고침과 같음).</li>
 * </ul>
 * 스크립트를 만드는 메서드는 DB를 읽으므로 작업 스레드에서 부른다. {@code can*}·{@link #getNewKinds}는 메모리만 보므로 EDT에서 부른다.
 *
 * 변경 이력:
 * - 2026-11-06 최초 작성 (docs/chromite/swing-source-editor.md)
 * - 2026-11-07 주석만: 쿼리 창마다 별도 연결이면 새 창이 주 문맥의 기본값을 복사함 (docs/chromite/transactions.md "쿼리 창마다 별도 연결")
 * - 2026-11-08 {@link #bindDefaultContext}를 패키지 안에 열어 Execute Procedure 창도 루틴의 DB·스키마에서 실행되게 함 (docs/chromite/swing-routines.md)
 */
final class SourceScripts {

    private static final Log log = Log.getLog(SourceScripts.class);

    /**
     * Kinds of "New …" templates.
     */
    enum NewObjectKind {
        VIEW,
        PROCEDURE,
        FUNCTION
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
        String quotedName = DBUtils.getQuotedIdentifier(dataSource, name);
        String qualifiedName = DBUtils.getQuotedIdentifier(container) + dataSource.getSQLDialect().getStructSeparator() + quotedName;

        String body = switch (dialect) {
            case SQL_SERVER -> switch (kind) {
                case VIEW -> "CREATE VIEW " + qualifiedName + "\nAS\nSELECT 1 AS Column1\n";
                case PROCEDURE -> "CREATE PROCEDURE " + qualifiedName + "\n    @Param1 int = 0\nAS\nBEGIN\n    SET NOCOUNT ON;\n\n"
                    + "    SELECT @Param1 AS Param1;\nEND\n";
                case FUNCTION -> "CREATE FUNCTION " + qualifiedName + "\n(\n    @Param1 int\n)\nRETURNS int\nAS\nBEGIN\n    RETURN @Param1;\nEND\n";
            };
            case POSTGRESQL -> switch (kind) {
                case VIEW -> "CREATE OR REPLACE VIEW " + qualifiedName + " AS\nSELECT 1 AS column1;\n";
                case PROCEDURE -> "CREATE OR REPLACE PROCEDURE " + qualifiedName + "(param1 integer)\n    LANGUAGE plpgsql\nAS $procedure$\n"
                    + "BEGIN\n    RAISE NOTICE 'param1: %', param1;\nEND;\n$procedure$;\n";
                case FUNCTION -> "CREATE OR REPLACE FUNCTION " + qualifiedName + "(param1 integer)\n    RETURNS integer\n    LANGUAGE plpgsql\n"
                    + "AS $function$\nBEGIN\n    RETURN param1;\nEND;\n$function$;\n";
            };
            case MYSQL -> switch (kind) {
                case VIEW -> "CREATE VIEW " + qualifiedName + " AS\nSELECT 1 AS column1;\n";
                case PROCEDURE -> "CREATE PROCEDURE " + qualifiedName + "(IN param1 INT)\nBEGIN\n    SELECT param1;\nEND\n";
                case FUNCTION -> "CREATE FUNCTION " + qualifiedName + "(param1 INT)\nRETURNS INT\nDETERMINISTIC\nBEGIN\n    RETURN param1;\nEND\n";
            };
            case ORACLE -> switch (kind) {
                case VIEW -> "CREATE OR REPLACE VIEW " + qualifiedName + " AS\nSELECT 1 AS COLUMN1 FROM DUAL;\n";
                case PROCEDURE -> "CREATE OR REPLACE PROCEDURE " + qualifiedName + "(P_PARAM1 IN NUMBER) IS\nBEGIN\n    NULL;\nEND " + quotedName + ";\n";
                case FUNCTION -> "CREATE OR REPLACE FUNCTION " + qualifiedName + "(P_PARAM1 IN NUMBER) RETURN NUMBER IS\nBEGIN\n"
                    + "    RETURN P_PARAM1;\nEND " + quotedName + ";\n";
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
            };
            default -> switch (kind) {
                case VIEW -> "new_view"; //$NON-NLS-1$
                case PROCEDURE -> "new_procedure"; //$NON-NLS-1$
                case FUNCTION -> "new_function"; //$NON-NLS-1$
            };
        };
        return dialect == Dialect.ORACLE ? name.toUpperCase(Locale.ENGLISH) : name;
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
