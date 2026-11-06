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
package com.seizia.chromite.ext.oracle.model;

import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.oracle.edit.OracleTableManager;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.edit.DBEObjectMaker;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.exec.DBExecUtils;
import com.seizia.chromite.model.impl.edit.TestCommandContext;
import com.seizia.chromite.model.impl.jdbc.JDBCRemoteInstance;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLUtils;
import com.seizia.chromite.model.struct.DBSEntityConstraintType;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.runtime.properties.PropertySourceEditable;
import com.seizia.chromite.junit.ChromiteUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.sql.Types;
import java.util.Collections;
import java.util.List;

public class OracleBaseTableTest extends ChromiteUnitTest {

    @Mock
    private DBRProgressMonitor monitor;

    private OracleDataSource testDataSource;
    private OracleSchema testSchema;
    private OracleTable oracleTable;
    private OracleExecutionContext executionContext;
    private DBEObjectMaker<OracleTable, OracleSchema> objectMaker;

    @Mock
    private JDBCRemoteInstance mockRemoteInstance;

    @BeforeEach
    public void setUp() throws DBException {
        DBPDataSourceContainer mockDataSourceContainer = configureTestContainer("oracle");
        testDataSource = new OracleDataSource(mockDataSourceContainer);

        Mockito.when(mockRemoteInstance.getDataSource()).thenReturn(testDataSource);

        executionContext = new OracleExecutionContext(mockRemoteInstance, "Test");
        testSchema = new OracleSchema(testDataSource, -1, "TEST_SCHEMA");

        objectMaker = OracleTestUtils.getManagerForClass(OracleTable.class);

        oracleTable = new OracleTable(testSchema, "TEST_TABLE");
        oracleTable.setPersisted(true);
        OracleTableColumn tableColumn = OracleTestUtils.addColumn(oracleTable, "COLUMN1", "VARCHAR", 1);
        tableColumn.setMaxLength(100);
        OracleTableColumn tableColumn1 = OracleTestUtils.addColumn(oracleTable, "COLUMN2", "NUMBER", 2);
        tableColumn1.setPrecision(38);
        tableColumn1.setScale(4);
        OracleTableColumn tableColumn2 = OracleTestUtils.addColumn(oracleTable, "COLUMN3", "CHAR", 3);
        tableColumn2.setMaxLength(13);
    }

    @Test
    public void generateCreateTableWithTwoColumnsStatement() throws Exception {
        TestCommandContext commandContext = new TestCommandContext(executionContext, false);

        OracleTable newObject = objectMaker.createNewObject(monitor, commandContext, testSchema, null, Collections.emptyMap());
        DBEObjectMaker objectManager = OracleTestUtils.getManagerForClass(OracleTableColumn.class);
        OracleTableColumn column1 = (OracleTableColumn) objectManager.createNewObject(
            monitor,
            commandContext,
            newObject,
            null,
            Collections.emptyMap()
        );
        column1.setTypeName("INTEGER");
        column1.setValueType(Types.INTEGER);

        OracleTableColumn column2 = (OracleTableColumn) objectManager.createNewObject(
            monitor,
            commandContext,
            newObject,
            null,
            Collections.emptyMap()
        );
        column2.setTypeName("INTEGER");
        column2.setValueType(Types.INTEGER);
        List<DBEPersistAction> actions = DBExecUtils.getActionsListFromCommandContext(
            monitor,
            commandContext,
            executionContext,
            Collections.emptyMap(),
            null
        );
        String script = SQLUtils.generateScript(testDataSource, actions.toArray(new DBEPersistAction[0]), false);

        String expectedDDL = "CREATE TABLE TEST_SCHEMA.NEWTABLE (" + lineBreak +
            "\tCOLUMN1 INTEGER NULL," + lineBreak +
            "\tCOLUMN2 INTEGER NULL" + lineBreak +
            ");" + lineBreak;

        Assertions.assertEquals(script, expectedDDL);
    }

    @Test
    public void generateCreateTableWithTwoColumnsOneNullableStatement() throws Exception {
        TestCommandContext commandContext = new TestCommandContext(executionContext, false);

        OracleTable newObject = objectMaker.createNewObject(
            monitor,
            commandContext,
            testSchema,
            null,
            Collections.emptyMap()
        );
        DBEObjectMaker objectManager = OracleTestUtils.getManagerForClass(OracleTableColumn.class);
        OracleTableColumn column1 = (OracleTableColumn) objectManager.createNewObject(
            monitor, commandContext, newObject, null, Collections.emptyMap());
        column1.setTypeName("INTEGER");
        column1.setValueType(Types.INTEGER);

        final DBSObject newColumn = objectManager.createNewObject(
            monitor, commandContext, newObject, null, Collections.emptyMap());
        if (newColumn instanceof OracleTableColumn oracleTableColumn) {
            oracleTableColumn.setTypeName("INTEGER");
            oracleTableColumn.setValueType(Types.INTEGER);
            oracleTableColumn.setRequired(true);
        }

        List<DBEPersistAction> actions = DBExecUtils.getActionsListFromCommandContext(
            monitor,
            commandContext,
            executionContext,
            Collections.emptyMap(),
            null
        );
        String script = SQLUtils.generateScript(
            testDataSource,
            actions.toArray(new DBEPersistAction[0]),
            false
        );

        String expectedDDL = "CREATE TABLE TEST_SCHEMA.NEWTABLE (" + lineBreak +
            "\tCOLUMN1 INTEGER NULL," + lineBreak +
            "\tCOLUMN2 INTEGER NOT NULL" + lineBreak +
            ");" + lineBreak;

        Assertions.assertEquals(script, expectedDDL);
    }

    @Test
    public void generateCreateTableWithTwoColumnsAndPrimaryKeyStatement() throws Exception {
        TestCommandContext commandContext = new TestCommandContext(executionContext, false);

        OracleTable newObject = objectMaker.createNewObject(monitor, commandContext, testSchema, null, Collections.emptyMap());
        DBEObjectMaker<OracleTableColumn, OracleTableBase> objectManager = OracleTestUtils.getManagerForClass(OracleTableColumn.class);
        OracleTableColumn column1 = objectManager.createNewObject(monitor, commandContext, newObject, null, Collections.emptyMap());
        column1.setTypeName("INTEGER");
        column1.setValueType(Types.INTEGER);

        OracleTableColumn column2 = objectManager.createNewObject(monitor, commandContext, newObject, null, Collections.emptyMap());
        column2.setTypeName("INTEGER");
        column2.setValueType(Types.INTEGER);

        DBEObjectMaker<OracleTableConstraint, OracleTableBase> constraintManager
            = OracleTestUtils.getManagerForClass(OracleTableConstraint.class);
        OracleTableConstraint constraint = constraintManager.createNewObject(
            monitor,
            commandContext,
            newObject,
            null,
            Collections.emptyMap()
        );
        constraint.setName("NEWTABLE_PK");
        constraint.setConstraintType(DBSEntityConstraintType.PRIMARY_KEY);
        OracleTableConstraintColumn constraintColumn = new OracleTableConstraintColumn(constraint, column1, 1);
        constraint.setAttributeReferences(Collections.singletonList(constraintColumn));

        List<DBEPersistAction> actions = DBExecUtils.getActionsListFromCommandContext(
            monitor,
            commandContext,
            executionContext,
            Collections.emptyMap(),
            null
        );
        String script = SQLUtils.generateScript(testDataSource, actions.toArray(new DBEPersistAction[0]), false);

        String expectedDDL = "CREATE TABLE TEST_SCHEMA.NEWTABLE (" + lineBreak +
            "\tCOLUMN1 INTEGER NULL," + lineBreak +
            "\tCOLUMN2 INTEGER NULL," + lineBreak +
            "\tCONSTRAINT NEWTABLE_PK PRIMARY KEY (COLUMN1)" + lineBreak +
            ");" + lineBreak;

        Assertions.assertEquals(script, expectedDDL);
    }

    @Test
    public void generateCreateTableWithTwoColumnsWithCommentStatement() throws Exception {
        TestCommandContext commandContext = new TestCommandContext(executionContext, false);

        OracleTable newObject = objectMaker.createNewObject(monitor, commandContext, testSchema, null, Collections.emptyMap());
        DBEObjectMaker<OracleTableColumn, OracleTableBase> objectManager = OracleTestUtils.getManagerForClass(OracleTableColumn.class);
        OracleTableColumn column1 = objectManager.createNewObject(monitor, commandContext, newObject, null, Collections.emptyMap());
        column1.setTypeName("INTEGER");
        column1.setValueType(Types.INTEGER);
        column1.setComment("Test comment 1");
        OracleTableColumn column2 = objectManager.createNewObject(monitor, commandContext, newObject, null, Collections.emptyMap());
        column2.setComment("Test comment 2");
        column2.setTypeName("INTEGER");
        column2.setValueType(Types.INTEGER);

        List<DBEPersistAction> actions = DBExecUtils.getActionsListFromCommandContext(monitor, commandContext, executionContext, Collections.emptyMap(), null);
        String script = SQLUtils.generateScript(testDataSource, actions.toArray(new DBEPersistAction[0]), false);

        String expectedDDL = "CREATE TABLE TEST_SCHEMA.NEWTABLE (" + lineBreak +
                "\tCOLUMN1 INTEGER NULL," + lineBreak +
                "\tCOLUMN2 INTEGER NULL" + lineBreak +
                ");" + lineBreak +
                "COMMENT ON COLUMN TEST_SCHEMA.NEWTABLE.COLUMN1 IS 'Test comment 1';" + lineBreak +
                "COMMENT ON COLUMN TEST_SCHEMA.NEWTABLE.COLUMN2 IS 'Test comment 2';" + lineBreak;

        Assertions.assertEquals(script, expectedDDL);
    }

    @Test
    public void generateAlterTableRenameStatement() throws Exception {
        TestCommandContext commandContext = new TestCommandContext(executionContext, false);

        if (objectMaker instanceof OracleTableManager) {
            ((OracleTableManager) objectMaker).renameObject(commandContext, oracleTable, Collections.emptyMap(), "NEW_TEST_TABLE");
        }

        List<DBEPersistAction> actions = DBExecUtils.getActionsListFromCommandContext(monitor, commandContext, executionContext, Collections.emptyMap(), null);

        String script = SQLUtils.generateScript(testDataSource, actions.toArray(new DBEPersistAction[0]), false);

        String expectedDDL = "ALTER TABLE TEST_SCHEMA.TEST_TABLE RENAME TO NEW_TEST_TABLE;" + lineBreak;
        Assertions.assertEquals(script, expectedDDL);
    }

    @Test
    public void generateTableCommentStatement() throws Exception {
        TestCommandContext commandContext = new TestCommandContext(executionContext, false);

        PropertySourceEditable pse = new PropertySourceEditable(commandContext, oracleTable, oracleTable);
        pse.collectProperties();
        pse.setPropertyValue(monitor, "comment", "Test comment");

        List<DBEPersistAction> actions = DBExecUtils.getActionsListFromCommandContext(monitor, commandContext, executionContext, Collections.emptyMap(), null);

        String script = SQLUtils.generateScript(testDataSource, actions.toArray(new DBEPersistAction[0]), false);

        String expectedDDL = "COMMENT ON TABLE TEST_SCHEMA.TEST_TABLE IS 'Test comment';" + lineBreak;
        Assertions.assertEquals(script, expectedDDL);
    }

    @Test
    public void generateDropTableStatement() throws Exception {
        TestCommandContext commandContext = new TestCommandContext(executionContext, false);

        objectMaker.deleteObject(commandContext, oracleTable, Collections.emptyMap());

        List<DBEPersistAction> actions = DBExecUtils.getActionsListFromCommandContext(monitor, commandContext, executionContext, Collections.emptyMap(), null);
        String script = SQLUtils.generateScript(testDataSource, actions.toArray(new DBEPersistAction[0]), false);

        String expectedDDL = "DROP TABLE TEST_SCHEMA.TEST_TABLE;" + lineBreak;

        Assertions.assertEquals(script, expectedDDL);
    }

}
