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
package com.seizia.chromite.ext.greenplum.edit;

import com.seizia.chromite.ext.greenplum.model.GreenplumDataSource;
import com.seizia.chromite.ext.greenplum.model.GreenplumExternalTable;
import com.seizia.chromite.ext.greenplum.model.GreenplumSchema;
import com.seizia.chromite.ext.greenplum.model.PostgreServerGreenplum;
import com.seizia.chromite.ext.postgresql.model.PostgreDatabase;
import com.seizia.chromite.ext.postgresql.model.PostgreDialect;
import com.seizia.chromite.model.impl.edit.SQLDatabasePersistAction;
import com.seizia.chromite.junit.ChromiteUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;

public class GreenplumExternalTableManager2Test extends ChromiteUnitTest {
    @Mock
    private GreenplumSchema mockSchema;

    @Mock
    private PostgreDatabase mockDatabase;

    @Mock
    private ResultSet mockResults;

    @Mock
    private GreenplumDataSource mockDataSource;

    private GreenplumExternalTableManager greenplumExternalTableManager;

    @Mock
    private PostgreServerGreenplum mockServerGreenplum;

    @BeforeEach
    public void setUp() {
        Mockito.when(mockDataSource.getSQLDialect()).thenReturn(new PostgreDialect());
        Mockito.when(mockSchema.getDatabase()).thenReturn(mockDatabase);
        Mockito.when(mockSchema.getDataSource()).thenReturn(mockDataSource);
        Mockito.when(mockDataSource.isServerVersionAtLeast(ArgumentMatchers.anyInt(), ArgumentMatchers.anyInt())).thenReturn(false);
        Mockito.when(mockDataSource.getServerType()).thenReturn(mockServerGreenplum);

        greenplumExternalTableManager = new GreenplumExternalTableManager();
    }

    @Test
    public void createDeleteAction_whenObjectIsAnExternalTable_thenExternalTableDropActionIsReturned() throws SQLException {
        SQLDatabasePersistAction regularTableDropTableQuery =
                new SQLDatabasePersistAction("Drop table", "DROP EXTERNAL TABLE foo.bar");

        Mockito.when(mockSchema.getName()).thenReturn("foo");
        Mockito.when(mockResults.getString("relname")).thenReturn("bar");

        GreenplumExternalTable greenplumExternalTable = newGreenplumExternalTableFixture();

        SQLDatabasePersistAction sqlDatabasePersistAction =
                greenplumExternalTableManager.createDeleteAction(greenplumExternalTable, Collections.emptyMap());

        Assertions.assertEquals(regularTableDropTableQuery.getScript(), sqlDatabasePersistAction.getScript());
    }

    @Test
    public void createDeleteAction_whenCascadeOptionIsProvided_thenExternalTableDropActionIsReturnedWithCascadeOption()
            throws SQLException {
        SQLDatabasePersistAction regularTableDropTableQuery =
                new SQLDatabasePersistAction("Drop table", "DROP EXTERNAL TABLE foo.bar CASCADE");

        Mockito.when(mockSchema.getName()).thenReturn("foo");
        Mockito.when(mockResults.getString("relname")).thenReturn("bar");

        GreenplumExternalTable greenplumExternalTable = newGreenplumExternalTableFixture();

        SQLDatabasePersistAction sqlDatabasePersistAction =
                greenplumExternalTableManager.createDeleteAction(greenplumExternalTable,
                        Collections.singletonMap("deleteCascade", true));

        Assertions.assertEquals(regularTableDropTableQuery.getScript(), sqlDatabasePersistAction.getScript());
    }

    private GreenplumExternalTable newGreenplumExternalTableFixture() throws SQLException {
        Mockito.when(mockResults.getString("fmttype")).thenReturn("b");
        Mockito.when(mockResults.getString("urilocation")).thenReturn("some_location");
        Mockito.when(mockResults.getString("fmtopts")).thenReturn("\n");
        Mockito.when(mockResults.getString("encoding")).thenReturn("UTF8");
        Mockito.when(mockResults.getString("execlocation")).thenReturn("some_location");
        return new GreenplumExternalTable(mockSchema, mockResults);
    }

}
