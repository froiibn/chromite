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
package com.seizia.chromite.ext.sqlite.model;

import com.seizia.chromite.ext.generic.model.GenericStructContainer;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.junit.ChromiteUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.sql.SQLException;

public class SQLiteMetaModelTest extends ChromiteUnitTest {

    @Test
    public void tableListQueryUsesMainSchemaPragmaToAvoidTempTableShadowing() throws SQLException {
        SQLiteDataSource dataSource = Mockito.mock(SQLiteDataSource.class);
        JDBCSession session = Mockito.mock(JDBCSession.class);
        GenericStructContainer owner = Mockito.mock(GenericStructContainer.class);
        JDBCPreparedStatement statement = Mockito.mock(JDBCPreparedStatement.class);

        Mockito.when(session.getDataSource()).thenReturn(dataSource);
        Mockito.when(dataSource.supportsStrictTyping()).thenReturn(true);
        Mockito.when(session.prepareStatement(Mockito.anyString())).thenReturn(statement);

        new SQLiteMetaModel().prepareTableLoadStatement(session, owner, null, null);

        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        Mockito.verify(session).prepareStatement(query.capture());
        Assertions.assertTrue(query.getValue().contains("LEFT OUTER JOIN main.pragma_table_list AS INFOS"));
    }
}
