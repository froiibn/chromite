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
package com.seizia.chromite.ext.mssql.model;

import com.seizia.chromite.model.impl.sql.BasicSQLDialect;
import com.seizia.chromite.model.sql.SQLDialect;
import com.seizia.chromite.model.struct.rdb.DBSProcedure;
import com.seizia.chromite.model.struct.rdb.DBSProcedureType;
import org.jkiss.code.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Regression tests of the routines SQL completion proposes after {@code CALL}/{@code EXEC}
 * ({@link SQLDialect#isCallStatementTarget}, docs/chromite/swing-sql-completion.md).
 *
 * 설계: 대부분의 DB는 함수를 식 안에서만 부르므로 기본은 프로시저만이고, SQL Server는 스칼라 함수(FN, FS)도 EXEC로 부를 수 있다.
 * 표 값 함수(TF, IF, FT)는 FROM에서만 쓴다. 방언마다 다른 이 규칙이 자동완성의 후보를 정한다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/regression-tests.md "자동완성")
 */
public class CallStatementTargetTest {

    @Test
    public void defaultDialectCallsProceduresOnly() {
        SQLDialect dialect = BasicSQLDialect.INSTANCE;
        Assertions.assertTrue(dialect.isCallStatementTarget(routine(DBSProcedureType.PROCEDURE)));
        Assertions.assertFalse(dialect.isCallStatementTarget(routine(DBSProcedureType.FUNCTION)));
    }

    @Test
    public void sqlServerExecAlsoRunsScalarFunctions() {
        SQLDialect dialect = new SQLServerDialectMssql();
        Assertions.assertTrue(dialect.isCallStatementTarget(serverRoutine(DBSProcedureType.PROCEDURE, SQLServerObjectType.P)));
        Assertions.assertTrue(dialect.isCallStatementTarget(serverRoutine(DBSProcedureType.PROCEDURE, SQLServerObjectType.X)));
        Assertions.assertTrue(dialect.isCallStatementTarget(serverRoutine(DBSProcedureType.FUNCTION, SQLServerObjectType.FN)));
        Assertions.assertTrue(dialect.isCallStatementTarget(serverRoutine(DBSProcedureType.FUNCTION, SQLServerObjectType.FS)));
        for (SQLServerObjectType tableValued : new SQLServerObjectType[] {SQLServerObjectType.TF, SQLServerObjectType.IF, SQLServerObjectType.FT}) {
            Assertions.assertFalse(dialect.isCallStatementTarget(serverRoutine(DBSProcedureType.FUNCTION, tableValued)), tableValued.name());
        }
        // A routine of another model falls back to the default rule
        Assertions.assertFalse(dialect.isCallStatementTarget(routine(DBSProcedureType.FUNCTION)));
    }

    @NotNull
    private static DBSProcedure routine(@NotNull DBSProcedureType type) {
        DBSProcedure procedure = Mockito.mock(DBSProcedure.class);
        Mockito.when(procedure.getProcedureType()).thenReturn(type);
        return procedure;
    }

    @NotNull
    private static SQLServerProcedure serverRoutine(@NotNull DBSProcedureType type, @NotNull SQLServerObjectType objectType) {
        SQLServerProcedure procedure = Mockito.mock(SQLServerProcedure.class);
        Mockito.when(procedure.getProcedureType()).thenReturn(type);
        Mockito.when(procedure.getObjectType()).thenReturn(objectType);
        return procedure;
    }
}
