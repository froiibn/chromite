/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
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
package com.seizia.chromite.model.sql.semantics;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.eclipse.jface.text.Document;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.lsm.LSMAnalyzer;
import com.seizia.chromite.model.lsm.LSMAnalyzerParameters;
import com.seizia.chromite.model.lsm.sql.dialect.LSMDialectRegistry;
import com.seizia.chromite.model.sql.SQLDialect;
import com.seizia.chromite.model.sql.SQLObjectOperation;
import com.seizia.chromite.model.sql.SQLQuery;
import com.seizia.chromite.model.sql.SQLScriptElement;
import com.seizia.chromite.model.sql.SQLSyntaxManager;
import com.seizia.chromite.model.sql.parser.SQLParserContext;
import com.seizia.chromite.model.sql.parser.SQLRuleManager;
import com.seizia.chromite.model.sql.parser.SQLScriptParser;
import com.seizia.chromite.model.stm.STMErrorListener;
import com.seizia.chromite.model.stm.STMKnownRuleNames;
import com.seizia.chromite.model.stm.STMSource;
import com.seizia.chromite.model.stm.STMTreeNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognizes database object operations from DDL syntax trees produced by the LSM parser.
 *
 * 변경 이력:
 * - 2026-11-07 {@link #isDropStatement}: Swing 쿼리 창의 DROP 확인이 JSQLParser가 읽지 못하는 DROP(DATABASE, USER, TABLESPACE,
 *   PROCEDURE, TRIGGER 등)도 찾도록. SWT 편집기는 이 클래스로 그것을 찾았다 (docs/chromite/swing-sql-exec-safety.md)
 * - 2026-11-17 방언의 문장 접두어({@link SQLDialect#getStatementPrefixLength})를 뺀 본문을 읽음: 접두어가 있는 DROP을 DROP으로 찾고
 *   (실행 전 확인), 접두어가 있는 DDL 뒤에도 Object Explorer를 새로 고침(앱 변경 #139, docs/chromite/swing-sql-exec-safety.md "문장 접두어")
 * - 2026-11-24 본문을 읽지 못한 프로시저·함수의 CREATE/ALTER도 머리의 이름으로 찾음({@link #recognizeRoutineHead}). 전에는 오류가 하나라도
 *   있으면 아무것도 찾지 않아, 쿼리 창에서 고쳐 실행한 Oracle·SQL Server 루틴의 DDL 보기가 실행 전 정의를 보였다
 *   (앱 변경 181, docs/chromite/swing-query-commands.md "DDL 뒤 탐색기 갱신")
 * - 2026-11-24 문법에 없는 저장 단위(패키지·형식·Altibase 타입세트·트리거)의 CREATE는 글의 머리에서 이름을 읽음({@link #recognizeStoredUnitHead}).
 *   Swing 쿼리 창에만 있던 Oracle 전용 대체 경로를 이것으로 바꿔 Altibase도 같은 길을 탄다. 문법이 읽은 CREATE TRIGGER도 찾음
 *   ({@link #createTriggerOperation}, 전에는 인식하지 않아 Altibase 트리거를 고친 뒤 DDL 보기가 옛 본문이었다)(앱 변경 181)
 */
public final class SQLObjectOperationRecognizer {

    // The statement rules of the LSM grammar that drop an object
    private static final Set<String> DROP_STATEMENTS = Set.of(
        STMKnownRuleNames.dropSchemaStatement,
        STMKnownRuleNames.dropTableStatement,
        STMKnownRuleNames.dropViewStatement,
        STMKnownRuleNames.dropProcedureStatement,
        STMKnownRuleNames.dropTriggerStatement,
        STMKnownRuleNames.dropIndexStatement,
        STMKnownRuleNames.dropCharacterSetStatement,
        STMKnownRuleNames.dropNamedObjectStatement,
        STMKnownRuleNames.dropCatalogDatabaseStatement
    );

    // One part of an object name: quoted ("", ``, []) or a plain identifier
    private static final String NAME = "(?:\"[^\"]+\"|`[^`]+`|\\[[^\\]]+\\]|[\\p{L}_][\\p{L}\\p{N}_$#@]*)"; //$NON-NLS-1$
    private static final Pattern NAME_PART = Pattern.compile(NAME);

    // The head of a CREATE of a stored program unit after leading comments (recognizeStoredUnitHead)
    private static final Pattern STORED_UNIT_HEAD = Pattern.compile(
        "(?:\\s+|--[^\\n]*(?:\\n|$)|/\\*.*?\\*/)*CREATE\\s+(?:OR\\s+(?:REPLACE|ALTER)\\s+)?(?:(?:NO\\s+)?FORCE\\s+)?" //$NON-NLS-1$
            + "(?:(?:EDITIONABLE|NONEDITIONABLE|EDITIONING)\\s+)*" //$NON-NLS-1$
            + "(?<kind>PACKAGE\\s+BODY|TYPE\\s+BODY|PACKAGE|TYPESET|TYPE|PROCEDURE|FUNCTION|TRIGGER|MATERIALIZED\\s+VIEW|VIEW)\\s+" //$NON-NLS-1$
            + "(?:IF\\s+NOT\\s+EXISTS\\s+)?(?<name>" + NAME + "(?:\\s*\\.\\s*" + NAME + "){0,2})", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private SQLObjectOperationRecognizer() {
    }

    /**
     * Whether the LSM parser reads one statement as a DROP of a database object, of any kind.
     * <p>
     * Unlike {@link #recognizeAll}, syntax errors after the object kind do not matter: the parser chooses the DROP
     * rule by the leading {@code DROP <kind>}, and a name it cannot read (MySQL accounts {@code 'user'@'host'}) must not
     * hide the DROP. Only the kind of statement is used, not the object.
     *
     * @param statement the text of one statement
     */
    public static boolean isDropStatement(
        @NotNull SQLDialect dialect,
        @NotNull SQLSyntaxManager syntaxManager,
        @NotNull String statement
    ) {
        LSMAnalyzer analyzer = LSMDialectRegistry.getInstance().getAnalyzerFactoryForDialect(dialect)
            .createAnalyzer(LSMAnalyzerParameters.forDialect(dialect, syntaxManager));
        // The listener only keeps the parser from reporting the syntax errors on the console. The statement proper is read,
        // without the dialect's statement prefix
        STMTreeNode tree = analyzer.parseSqlQueryTree(STMSource.fromString(withoutPrefix(dialect, statement)), new RecognitionErrorListener());
        STMTreeNode schemaStatement = tree == null ? null : unwrapSchemaStatement(tree);
        STMTreeNode node = schemaStatement == null ? null : schemaStatement.findFirstNonErrorChild();
        return node != null && DROP_STATEMENTS.contains(node.getNodeName());
    }

    /**
     * Recognizes database object operations in a query, applying statement separation only when the query is not known
     * to represent one logical statement.
     */
    @NotNull
    public static List<SQLObjectOperation> recognizeAll(
        @Nullable DBPDataSource dataSource,
        @NotNull SQLDialect dialect,
        @NotNull SQLSyntaxManager syntaxManager,
        @NotNull SQLQuery query
    ) {
        LSMAnalyzer analyzer = LSMDialectRegistry.getInstance().getAnalyzerFactoryForDialect(dialect)
            .createAnalyzer(LSMAnalyzerParameters.forDialect(dialect, syntaxManager));
        return query.representsOneStatement() ?
            recognizeStatement(analyzer, withoutPrefix(dialect, query.getText())) :
            separateAndRecognize(dataSource, syntaxManager, analyzer, query.getText());
    }

    /**
     * The statement proper: the text after the dialect's statement prefix ({@link SQLDialect#getStatementPrefixLength}).
     */
    @NotNull
    private static String withoutPrefix(@NotNull SQLDialect dialect, @NotNull String statement) {
        return statement.substring(dialect.getStatementPrefixLength(statement));
    }

    @NotNull
    private static List<SQLObjectOperation> separateAndRecognize(
        @Nullable DBPDataSource dataSource,
        @NotNull SQLSyntaxManager syntaxManager,
        @NotNull LSMAnalyzer analyzer,
        @NotNull String queryText
    ) {
        SQLRuleManager ruleManager = new SQLRuleManager(syntaxManager);
        ruleManager.loadRules(dataSource, false);
        SQLParserContext parserContext = new SQLParserContext(
            dataSource,
            syntaxManager,
            ruleManager,
            new Document(queryText)
        );
        parserContext.setPreferenceStore(syntaxManager.getPreferenceStore());

        List<SQLObjectOperation> operations = new ArrayList<>();
        for (SQLScriptElement element : SQLScriptParser.extractScriptQueries(
            parserContext,
            0,
            queryText.length(),
            false,
            false,
            false
        )) {
            if (element instanceof SQLQuery query) {
                operations.addAll(recognizeStatement(analyzer, withoutPrefix(syntaxManager.getDialect(), query.getText())));
            }
        }
        return List.copyOf(operations);
    }

    @NotNull
    private static List<SQLObjectOperation> recognizeStatement(
        @NotNull LSMAnalyzer analyzer,
        @NotNull String queryText
    ) {
        RecognitionErrorListener errorListener = new RecognitionErrorListener();
        STMTreeNode tree = analyzer.parseSqlQueryTree(STMSource.fromString(queryText), errorListener);
        if (tree == null) {
            return List.of();
        }
        if (errorListener.hasErrors || hasErrors(tree)) {
            List<SQLObjectOperation> operations = recognizeRoutineHead(tree);
            return operations.isEmpty() ? recognizeStoredUnitHead(queryText) : operations;
        }
        return recognizeTree(tree);
    }

    /**
     * The stored program unit a CREATE statement names in its head, read from the text, when the grammar does not know
     * the kind or cannot read the head: packages and their bodies, types and type bodies, Altibase typesets, triggers
     * (Oracle, Altibase), and procedures, functions and views behind modifiers the grammar does not read
     * ({@code EDITIONABLE}, {@code FORCE}). The grammar has no such kinds (and no tokens for {@code PACKAGE},
     * {@code BODY}, {@code TYPESET}); its routine modifiers take any word before the routine kind, so adding them there
     * would change how other statements parse. Reading only the head is enough here, as in {@link #recognizeRoutineHead}:
     * the statement ran, and only the container of the named unit is wanted.
     */
    @NotNull
    private static List<SQLObjectOperation> recognizeStoredUnitHead(@NotNull String queryText) {
        Matcher matcher = STORED_UNIT_HEAD.matcher(queryText);
        if (!matcher.lookingAt()) {
            return List.of();
        }
        String kind = matcher.group("kind").toUpperCase(Locale.ENGLISH);
        SQLObjectOperation.ObjectKind objectKind;
        if (kind.equals("PROCEDURE")) {
            objectKind = SQLObjectOperation.ObjectKind.PROCEDURE;
        } else if (kind.equals("FUNCTION")) {
            objectKind = SQLObjectOperation.ObjectKind.FUNCTION;
        } else if (kind.endsWith("VIEW")) {
            objectKind = SQLObjectOperation.ObjectKind.VIEW;
        } else {
            objectKind = SQLObjectOperation.ObjectKind.PROGRAM_UNIT;
        }
        List<String> nameParts = new ArrayList<>();
        Matcher part = NAME_PART.matcher(matcher.group("name"));
        while (part.find()) {
            nameParts.add(part.group());
        }
        return List.of(new SQLObjectOperation(SQLObjectOperation.Operation.CREATE, objectKind, nameParts));
    }

    /**
     * The routine a CREATE or ALTER of a procedure or function names in its head, when the rest of the statement does
     * not parse.
     * <p>
     * The grammar reads few routine bodies to the end: PL/SQL declarations, T-SQL statements after
     * {@code SET NOCOUNT ON;}, and the {@code ;} that ends an Oracle or Altibase block (the query window keeps it in the
     * statement) are left as errors. Like {@link #isDropStatement}, errors after the head do not matter: the statement ran,
     * and the routine it made or changed is the one its head names. Without this a query window that ran
     * {@code CREATE OR REPLACE PROCEDURE} found no object, and Object Explorer and the model kept the old definition
     * (View DDL, Script as and Modify showed the source from before the run).
     */
    @NotNull
    private static List<SQLObjectOperation> recognizeRoutineHead(@NotNull STMTreeNode root) {
        if (root.getNodeName().equals(STMKnownRuleNames.sqlQueries) &&
            root.findChildrenOfName(STMKnownRuleNames.sqlQuery).size() != 1) {
            return List.of();
        }
        STMTreeNode schemaStatement = unwrapSchemaStatement(root);
        STMTreeNode statement = schemaStatement == null ? null : schemaStatement.findFirstNonErrorChild();
        if (statement == null) {
            return List.of();
        }
        if (statement.getNodeName().equals(STMKnownRuleNames.createTriggerStatement)) {
            SQLObjectOperation trigger = createTriggerOperation(statement);
            return trigger == null ? List.of() : List.of(trigger);
        }
        SQLObjectOperation.Operation operation;
        STMTreeNode kindNode;
        if (statement.getNodeName().equals(STMKnownRuleNames.createNamedObjectStatement)) {
            operation = SQLObjectOperation.Operation.CREATE;
            kindNode = statement.findFirstChildOfName(STMKnownRuleNames.createObjectKind);
        } else if (statement.getNodeName().equals(STMKnownRuleNames.alterNamedObjectStatement)) {
            operation = SQLObjectOperation.Operation.ALTER;
            kindNode = statement.findFirstChildOfName(STMKnownRuleNames.alterObjectKind);
        } else {
            return List.of();
        }
        STMTreeNode nameNode = statement.findFirstChildOfName(STMKnownRuleNames.qualifiedName);
        if (kindNode == null || nameNode == null || hasErrors(kindNode) || hasErrors(nameNode)) {
            return List.of();
        }
        SQLObjectOperation.ObjectKind objectKind = getObjectKind(kindNode);
        if (objectKind != SQLObjectOperation.ObjectKind.PROCEDURE && objectKind != SQLObjectOperation.ObjectKind.FUNCTION) {
            return List.of();
        }
        List<String> nameParts = getNameParts(nameNode);
        return nameParts.isEmpty() ? List.of() : List.of(new SQLObjectOperation(operation, objectKind, nameParts));
    }

    @NotNull
    private static List<SQLObjectOperation> recognizeTree(@NotNull STMTreeNode root) {
        if (hasErrors(root) || root.getNodeName().equals(STMKnownRuleNames.sqlQueries) &&
            root.findChildrenOfName(STMKnownRuleNames.sqlQuery).size() != 1) {
            return List.of();
        }
        STMTreeNode schemaStatement = unwrapSchemaStatement(root);
        if (schemaStatement == null) {
            return List.of();
        }
        STMTreeNode statement = schemaStatement.findFirstNonErrorChild();
        if (statement == null) {
            return List.of();
        }
        if (statement.getNodeName().equals(STMKnownRuleNames.dropTableStatement)) {
            return createOperations(
                statement,
                SQLObjectOperation.Operation.DROP,
                SQLObjectOperation.ObjectKind.TABLE,
                STMKnownRuleNames.tableName
            );
        }
        if (statement.getNodeName().equals(STMKnownRuleNames.dropViewStatement)) {
            return createOperations(
                statement,
                SQLObjectOperation.Operation.DROP,
                SQLObjectOperation.ObjectKind.VIEW,
                STMKnownRuleNames.tableName
            );
        }
        if (statement.getNodeName().equals(STMKnownRuleNames.renameNamedObjectStatement)) {
            STMTreeNode kindNode = findDescendant(statement, STMKnownRuleNames.renameObjectKind);
            return kindNode == null ? List.of() : createOperations(
                statement,
                SQLObjectOperation.Operation.RENAME,
                getObjectKind(kindNode),
                STMKnownRuleNames.qualifiedName
            );
        }
        if (statement.getNodeName().equals(STMKnownRuleNames.dropNamedObjectStatement)) {
            STMTreeNode kindNode = findDescendant(statement, STMKnownRuleNames.dropObjectKind);
            return kindNode == null ? List.of() : createOperations(
                statement,
                SQLObjectOperation.Operation.DROP,
                getObjectKind(kindNode),
                STMKnownRuleNames.qualifiedName
            );
        }
        if (statement.getNodeName().equals(STMKnownRuleNames.alterNamedObjectStatement)) {
            STMTreeNode kindNode = findDescendant(statement, STMKnownRuleNames.alterObjectKind);
            return kindNode == null ? List.of() : createAlterOperations(
                statement,
                getObjectKind(kindNode),
                STMKnownRuleNames.qualifiedName
            );
        }
        if (statement.getNodeName().equals(STMKnownRuleNames.alterTableStatement)) {
            return createAlterOperations(
                statement,
                SQLObjectOperation.ObjectKind.TABLE,
                STMKnownRuleNames.tableName
            );
        }
        SQLObjectOperation operation = recognizeSingle(statement);
        return operation == null ? List.of() : List.of(operation);
    }

    @Nullable
    private static SQLObjectOperation recognizeSingle(@NotNull STMTreeNode statement) {
        if (statement.getNodeName().equals(STMKnownRuleNames.schemaDefinition)) {
            STMTreeNode name = findDescendant(statement, STMKnownRuleNames.schemaName);
            if (name == null) {
                return findDescendant(statement, STMKnownRuleNames.authorizationIdentifier) == null ? null :
                    new SQLObjectOperation(SQLObjectOperation.Operation.CREATE, SQLObjectOperation.ObjectKind.SCHEMA, List.of());
            }
            return createOperation(SQLObjectOperation.Operation.CREATE, SQLObjectOperation.ObjectKind.SCHEMA, name);
        }
        if (statement.getNodeName().equals(STMKnownRuleNames.createTableStatement)) {
            return createOperation(
                SQLObjectOperation.Operation.CREATE,
                SQLObjectOperation.ObjectKind.TABLE,
                findDescendant(statement, STMKnownRuleNames.tableName)
            );
        }
        if (statement.getNodeName().equals(STMKnownRuleNames.createViewStatement)) {
            return createOperation(
                SQLObjectOperation.Operation.CREATE,
                SQLObjectOperation.ObjectKind.VIEW,
                findDescendant(statement, STMKnownRuleNames.tableName)
            );
        }
        if (statement.getNodeName().equals(STMKnownRuleNames.createIndexStatement)) {
            return createIndexOperation(statement, SQLObjectOperation.Operation.CREATE);
        }
        if (statement.getNodeName().equals(STMKnownRuleNames.createTriggerStatement)) {
            return createTriggerOperation(statement);
        }
        if (statement.getNodeName().equals(STMKnownRuleNames.createNamedObjectStatement)) {
            return createNamedOperation(
                statement,
                SQLObjectOperation.Operation.CREATE,
                STMKnownRuleNames.createObjectKind
            );
        }
        if (statement.getNodeName().equals(STMKnownRuleNames.createCatalogDatabaseStatement)) {
            return createContainerOperation(statement, SQLObjectOperation.Operation.CREATE);
        }
        if (statement.getNodeName().equals(STMKnownRuleNames.dropSchemaStatement)) {
            return createOperation(
                SQLObjectOperation.Operation.DROP,
                SQLObjectOperation.ObjectKind.SCHEMA,
                findDescendant(statement, STMKnownRuleNames.schemaName)
            );
        }
        if (statement.getNodeName().equals(STMKnownRuleNames.dropCatalogDatabaseStatement)) {
            return createContainerOperation(statement, SQLObjectOperation.Operation.DROP);
        }
        if (statement.getNodeName().equals(STMKnownRuleNames.dropTableStatement)) {
            return createOperation(
                SQLObjectOperation.Operation.DROP,
                SQLObjectOperation.ObjectKind.TABLE,
                findDescendant(statement, STMKnownRuleNames.tableName)
            );
        }
        if (statement.getNodeName().equals(STMKnownRuleNames.dropViewStatement)) {
            return createOperation(
                SQLObjectOperation.Operation.DROP,
                SQLObjectOperation.ObjectKind.VIEW,
                findDescendant(statement, STMKnownRuleNames.tableName)
            );
        }
        if (statement.getNodeName().equals(STMKnownRuleNames.dropProcedureStatement)) {
            return createOperation(
                SQLObjectOperation.Operation.DROP,
                SQLObjectOperation.ObjectKind.PROCEDURE,
                findDescendant(statement, STMKnownRuleNames.qualifiedName)
            );
        }
        if (statement.getNodeName().equals(STMKnownRuleNames.dropIndexStatement)) {
            return createIndexOperation(statement, SQLObjectOperation.Operation.DROP);
        }
        if (statement.getNodeName().equals(STMKnownRuleNames.alterContainerStatement)) {
            SQLObjectOperation.Operation operation = findDescendant(statement, STMKnownRuleNames.renameContainerAction) == null ?
                SQLObjectOperation.Operation.ALTER : SQLObjectOperation.Operation.RENAME;
            return createContainerOperation(statement, operation);
        }
        return null;
    }

    @NotNull
    private static List<SQLObjectOperation> createAlterOperations(
        @NotNull STMTreeNode statement,
        @NotNull SQLObjectOperation.ObjectKind objectKind,
        @NotNull String nameNodeName
    ) {
        List<String> sourceName = getNameParts(findDescendant(statement, nameNodeName));
        if (sourceName.isEmpty()) {
            return List.of();
        }

        SQLObjectOperation source = new SQLObjectOperation(
            SQLObjectOperation.Operation.ALTER,
            objectKind,
            sourceName
        );
        STMTreeNode setSchemaAction = findDescendant(statement, STMKnownRuleNames.setTableSchemaAction);
        List<String> destinationSchema = getNameParts(setSchemaAction);
        if (destinationSchema.isEmpty()) {
            return List.of(source);
        }

        List<String> destinationName = new ArrayList<>();
        if (destinationSchema.size() == 1 && sourceName.size() > 2) {
            destinationName.addAll(sourceName.subList(0, sourceName.size() - 2));
        }
        destinationName.addAll(destinationSchema);
        destinationName.add(sourceName.getLast());
        return List.of(source, new SQLObjectOperation(
            SQLObjectOperation.Operation.ALTER,
            objectKind,
            destinationName
        ));
    }

    @Nullable
    private static SQLObjectOperation createIndexOperation(
        @NotNull STMTreeNode statement,
        @NotNull SQLObjectOperation.Operation operation
    ) {
        STMTreeNode indexNameNode = statement.findFirstChildOfName(STMKnownRuleNames.qualifiedName);
        STMTreeNode tableNameNode = statement.findFirstChildOfName(STMKnownRuleNames.tableName);
        List<String> indexName = getNameParts(indexNameNode);
        List<String> tableName = getNameParts(tableNameNode);
        if (indexName.size() == 1 && tableName.size() > 1) {
            List<String> qualifiedIndexName = new ArrayList<>(tableName.size());
            qualifiedIndexName.addAll(tableName.subList(0, tableName.size() - 1));
            qualifiedIndexName.add(indexName.getFirst());
            indexName = List.copyOf(qualifiedIndexName);
        }
        return indexName.isEmpty() ? null :
            new SQLObjectOperation(operation, SQLObjectOperation.ObjectKind.INDEX, indexName);
    }

    /**
     * A trigger the statement creates, in the container of its name, or of its table when the name has no qualifier
     * (PostgreSQL trigger names never have one; the trigger is in the table's schema). Before 2026-11-24 a CREATE
     * TRIGGER was not recognized at all, so nothing was read again after it.
     */
    @Nullable
    private static SQLObjectOperation createTriggerOperation(@NotNull STMTreeNode statement) {
        STMTreeNode nameNode = statement.findFirstChildOfName(STMKnownRuleNames.qualifiedName);
        if (nameNode == null || hasErrors(nameNode)) {
            return null;
        }
        List<String> nameParts = getNameParts(nameNode);
        if (nameParts.size() == 1) {
            STMTreeNode tableNode = findDescendant(statement, STMKnownRuleNames.tableName);
            List<String> tableParts = tableNode == null || hasErrors(tableNode) ? List.of() : getNameParts(tableNode);
            if (tableParts.size() > 1) {
                List<String> qualified = new ArrayList<>(tableParts.subList(0, tableParts.size() - 1));
                qualified.add(nameParts.getFirst());
                nameParts = qualified;
            }
        }
        return nameParts.isEmpty() ? null :
            new SQLObjectOperation(SQLObjectOperation.Operation.CREATE, SQLObjectOperation.ObjectKind.PROGRAM_UNIT, nameParts);
    }

    @Nullable
    private static SQLObjectOperation createNamedOperation(
        @NotNull STMTreeNode statement,
        @NotNull SQLObjectOperation.Operation operation,
        @NotNull String kindNodeName
    ) {
        STMTreeNode kindNode = findDescendant(statement, kindNodeName);
        STMTreeNode nameNode = findDescendant(statement, STMKnownRuleNames.qualifiedName);
        if (kindNode == null || nameNode == null) {
            return null;
        }
        return createOperation(operation, getObjectKind(kindNode), nameNode);
    }

    @Nullable
    private static STMTreeNode unwrapSchemaStatement(@NotNull STMTreeNode root) {
        STMTreeNode node = root;
        if (node.getNodeName().equals(STMKnownRuleNames.sqlQueries)) {
            node = node.findFirstNonErrorChild();
        }
        if (node != null && node.getNodeName().equals(STMKnownRuleNames.sqlQuery)) {
            node = node.findFirstChildOfName(STMKnownRuleNames.sqlQueryBody);
        }
        if (node != null && node.getNodeName().equals(STMKnownRuleNames.sqlQueryBody)) {
            node = node.findFirstNonErrorChild();
        }
        return node != null && node.getNodeName().equals(STMKnownRuleNames.sqlSchemaStatement) ? node : null;
    }

    @Nullable
    private static SQLObjectOperation createContainerOperation(
        @NotNull STMTreeNode statement,
        @NotNull SQLObjectOperation.Operation operation
    ) {
        STMTreeNode kindNode = findDescendant(statement, STMKnownRuleNames.containerKind);
        if (kindNode == null) {
            kindNode = findDescendant(statement, STMKnownRuleNames.alterContainerKind);
        }
        STMTreeNode nameNode = findDescendant(statement, STMKnownRuleNames.qualifiedName);
        if (kindNode == null || nameNode == null) {
            return null;
        }
        return createOperation(operation, getObjectKind(kindNode), nameNode);
    }

    @NotNull
    private static SQLObjectOperation.ObjectKind getObjectKind(@NotNull STMTreeNode kindNode) {
        String kindName = kindNode.getText().toUpperCase(Locale.ENGLISH);
        if (kindName.equals("MATERIALIZEDVIEW")) {
            return SQLObjectOperation.ObjectKind.VIEW;
        }
        try {
            return SQLObjectOperation.ObjectKind.valueOf(kindName);
        } catch (IllegalArgumentException e) {
            return SQLObjectOperation.ObjectKind.OTHER;
        }
    }

    @Nullable
    private static SQLObjectOperation createOperation(
        @NotNull SQLObjectOperation.Operation operation,
        @NotNull SQLObjectOperation.ObjectKind objectKind,
        @Nullable STMTreeNode nameNode
    ) {
        if (nameNode == null) {
            return null;
        }
        List<String> nameParts = getNameParts(nameNode);
        return nameParts.isEmpty() ? null : new SQLObjectOperation(operation, objectKind, nameParts);
    }

    @NotNull
    private static List<String> getNameParts(@Nullable STMTreeNode nameNode) {
        if (nameNode == null) {
            return List.of();
        }
        STMTreeNode qualifiedName = nameNode.getNodeName().equals(STMKnownRuleNames.qualifiedName) ? nameNode :
            findDescendant(nameNode, STMKnownRuleNames.qualifiedName);
        if (qualifiedName == null) {
            return List.of();
        }
        return qualifiedName.findChildrenOfName(STMKnownRuleNames.identifier).stream()
            .map(STMTreeNode::getTextContent)
            .toList();
    }

    @Nullable
    private static STMTreeNode findDescendant(@NotNull STMTreeNode node, @NotNull String nodeName) {
        if (node.getNodeName().equals(nodeName)) {
            return node;
        }
        for (STMTreeNode child : node.findNonErrorChildren()) {
            STMTreeNode result = findDescendant(child, nodeName);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    @NotNull
    private static List<SQLObjectOperation> createOperations(
        @NotNull STMTreeNode statement,
        @NotNull SQLObjectOperation.Operation operation,
        @NotNull SQLObjectOperation.ObjectKind objectKind,
        @NotNull String nameNodeName
    ) {
        return statement.findChildrenOfName(nameNodeName).stream()
            .map(name -> createOperation(operation, objectKind, name))
            .filter(Objects::nonNull)
            .toList();
    }

    private static boolean hasErrors(@NotNull STMTreeNode node) {
        if (node instanceof ErrorNode || node.hasErrorChildren()) {
            return true;
        }
        for (STMTreeNode child : node.getChildren()) {
            if (hasErrors(child)) {
                return true;
            }
        }
        return false;
    }

    private static final class RecognitionErrorListener extends BaseErrorListener implements STMErrorListener {
        private boolean hasErrors;

        @Override
        public void syntaxError(
            @NotNull Recognizer<?, ?> recognizer,
            @Nullable Object offendingSymbol,
            int line,
            int charPositionInLine,
            @NotNull String msg,
            @Nullable RecognitionException e
        ) {
            hasErrors = true;
        }
    }
}
