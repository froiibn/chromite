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
package com.seizia.chromite.ext.oracle.model;

import com.seizia.chromite.junit.ChromiteUnitTest;
import com.seizia.chromite.model.DBPDataKind;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.sql.BasicSQLDialect;
import com.seizia.chromite.model.struct.DBSTypedObject;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Regression tests of the VARCHAR2 limit of Copy Tables into Oracle: 32767 bytes with MAX_STRING_SIZE = EXTENDED, else
 * (STANDARD, no access to V$PARAMETER, servers before 12c) the 4000 of STANDARD (docs/chromite/swing-data-transfer.md).
 *
 * 설계: 한도를 읽는 쿼리는 실제 서버 없이 JDBC 세션 mock으로 답을 바꿔 본다. 읽지 못하면 언제나 4000으로 남아야 한다: 더 긴 문자열은 CLOB이
 * 되어 값이 잘리지 않는다. 읽은 한도는 방언의 필드에 두므로 같은 필드에 값을 넣고 형식 변환 결과를 본다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/regression-tests.md "데이터")
 * - 2026-11-08 문장·결과가 없는 세션과 SQL이 아닌 예외도 4000으로 남는지 더함(그 경우 NullPointerException이 방언 밖으로 나갔다)
 */
public class OracleMaxStringSizeTest extends ChromiteUnitTest {

    @Test
    public void limitIsReadFromMaxStringSizeAndFallsBackToStandard() throws Exception {
        Assertions.assertEquals(32767L, readLimit(session("EXTENDED")));
        Assertions.assertEquals(32767L, readLimit(session(" extended ")));
        Assertions.assertEquals(4000L, readLimit(session("STANDARD")));
        // No row (no such parameter before 12c) and no access to V$PARAMETER (ORA-00942)
        Assertions.assertEquals(4000L, readLimit(session(null)));
        JDBCSession denied = Mockito.mock(JDBCSession.class);
        Mockito.when(denied.prepareStatement(Mockito.anyString())).thenThrow(new SQLException("ORA-00942: table or view does not exist"));
        Assertions.assertEquals(4000L, readLimit(denied));
    }

    @Test
    public void probeThatBreaksOtherwiseStillKeepsTheStandardLimit() throws Exception {
        // A session that gives no statement (the parser tests' sessions), a statement without a result, a driver error
        Assertions.assertEquals(4000L, readLimit(Mockito.mock(JDBCSession.class)));
        JDBCPreparedStatement noResult = Mockito.mock(JDBCPreparedStatement.class);
        JDBCSession withoutResult = Mockito.mock(JDBCSession.class);
        Mockito.when(withoutResult.prepareStatement(Mockito.anyString())).thenReturn(noResult);
        Assertions.assertEquals(4000L, readLimit(withoutResult));
        JDBCSession failing = Mockito.mock(JDBCSession.class);
        Mockito.when(failing.prepareStatement(Mockito.anyString())).thenThrow(new IllegalStateException("driver bug"));
        Assertions.assertEquals(4000L, readLimit(failing));
    }

    @Test
    public void longerStringsBecomeClobAtTheLimit() throws Exception {
        OracleSQLDialect dialect = new OracleSQLDialect();
        // Before the connection is read: STANDARD
        Assertions.assertEquals("VARCHAR2", dialect.convertExternalDataType(BasicSQLDialect.INSTANCE, string(4000), null));
        Assertions.assertEquals("CLOB", dialect.convertExternalDataType(BasicSQLDialect.INSTANCE, string(10000), null));

        setLimit(dialect, 32767L);
        Assertions.assertEquals("VARCHAR2", dialect.convertExternalDataType(BasicSQLDialect.INSTANCE, string(10000), null));
        Assertions.assertEquals("CLOB", dialect.convertExternalDataType(BasicSQLDialect.INSTANCE, string(40000), null));
        // Unbounded text is always CLOB
        Assertions.assertEquals("CLOB", dialect.convertExternalDataType(BasicSQLDialect.INSTANCE, string(0), null));
    }

    @NotNull
    private static JDBCSession session(@Nullable String value) throws SQLException {
        JDBCResultSet resultSet = Mockito.mock(JDBCResultSet.class);
        Mockito.when(resultSet.next()).thenReturn(value != null);
        Mockito.when(resultSet.getString(1)).thenReturn(value);
        JDBCPreparedStatement statement = Mockito.mock(JDBCPreparedStatement.class);
        Mockito.when(statement.executeQuery()).thenReturn(resultSet);
        JDBCSession session = Mockito.mock(JDBCSession.class);
        Mockito.when(session.prepareStatement(Mockito.anyString())).thenReturn(statement);
        return session;
    }

    private static long readLimit(@NotNull JDBCSession session) throws Exception {
        Method read = OracleSQLDialect.class.getDeclaredMethod("readMaxVarchar2Length", JDBCSession.class);
        read.setAccessible(true);
        return (long) read.invoke(null, session);
    }

    private static void setLimit(@NotNull OracleSQLDialect dialect, long limit) throws Exception {
        Field field = OracleSQLDialect.class.getDeclaredField("maxVarchar2Length");
        field.setAccessible(true);
        field.setLong(dialect, limit);
    }

    @NotNull
    private static DBSTypedObject string(long maxLength) {
        DBSTypedObject type = Mockito.mock(DBSTypedObject.class);
        Mockito.when(type.getTypeName()).thenReturn("character varying");
        Mockito.when(type.getFullTypeName()).thenReturn("character varying");
        Mockito.when(type.getTypeID()).thenReturn(Types.VARCHAR);
        Mockito.when(type.getDataKind()).thenReturn(DBPDataKind.STRING);
        Mockito.when(type.getMaxLength()).thenReturn(maxLength);
        return type;
    }
}
