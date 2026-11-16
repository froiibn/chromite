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
package com.seizia.chromite.ext.postgresql;

import com.seizia.chromite.ext.postgresql.model.PostgreDataSource;
import com.seizia.chromite.ext.postgresql.model.PostgreDatabase;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.impl.jdbc.exec.JDBCColumnMetaData;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSTypedObject;
import com.seizia.chromite.junit.ChromiteUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.sql.Types;

public class PostgreUtilsTest extends ChromiteUnitTest {
    @Mock
    private DBCSession session;
    @Mock
    private DBRProgressMonitor monitor;
    @Mock
    private DBCExecutionContext context;
    @Mock
    private PostgreDataSource dataSource;
    @Mock
    private PostgreDatabase database;
    @Mock
    private JDBCColumnMetaData column;
    @Mock
    private DBSTypedObject otherType;

    @Test
    public void unresolvedResultColumnRowTypeDoesNotLogOrRetrySameDatabase() throws Exception {
        Mockito.when(session.getProgressMonitor()).thenReturn(monitor);
        Mockito.when(session.getExecutionContext()).thenReturn(context);
        Mockito.when(context.getOwnerInstance()).thenReturn(database);
        Mockito.when(column.getCatalogName()).thenReturn("postgres");
        Mockito.when(column.getTypeName()).thenReturn("view6");
        Mockito.when(column.getTypeID()).thenReturn(Types.STRUCT);
        Mockito.when(dataSource.getDatabase("postgres")).thenReturn(database);

        Assertions.assertNull(PostgreUtils.findDataType(session, dataSource, column));
        Mockito.verify(database).getDataType(monitor, "view6", false);
        Mockito.verify(database, Mockito.never()).getDataType(monitor, "view6", true);
    }

    @Test
    public void nonResultColumnTypeIsVerified() throws Exception {
        Mockito.when(session.getProgressMonitor()).thenReturn(monitor);
        Mockito.when(session.getExecutionContext()).thenReturn(context);
        Mockito.when(context.getOwnerInstance()).thenReturn(database);
        Mockito.when(otherType.getTypeName()).thenReturn("test_type");

        Assertions.assertNull(PostgreUtils.findDataType(session, dataSource, otherType));
        Mockito.verify(database).getDataType(monitor, "test_type", true);
    }
}
