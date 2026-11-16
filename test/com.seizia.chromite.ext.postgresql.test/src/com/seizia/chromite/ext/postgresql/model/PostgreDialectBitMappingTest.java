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
package com.seizia.chromite.ext.postgresql.model;

import com.seizia.chromite.ext.postgresql.PostgreConstants;
import com.seizia.chromite.junit.ChromiteUnitTest;
import com.seizia.chromite.model.DBPDataKind;
import com.seizia.chromite.model.impl.sql.BasicSQLDialect;
import com.seizia.chromite.model.sql.SQLDialect;
import com.seizia.chromite.model.struct.DBSTypedObject;
import org.jkiss.code.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Types;

/**
 * Regression tests of the PostgreSQL type of a one-bit BIT column of another database in Copy Tables
 * ({@link PostgreDialect#convertExternalDataType}, docs/chromite/swing-data-transfer.md).
 *
 * 설계: SQL Server {@code bit}와 MySQL {@code BIT(1)}은 참/거짓 값인데, 이름으로 찾으면 PostgreSQL의 비트 문자열 {@code bit}이 되어 값을
 * 넣을 수 없었다. 한 비트 BIT만 boolean이 되고, 여러 비트 BIT와 PostgreSQL끼리의 복사는 그대로 bit여야 한다.
 *
 * 변경 이력:
 * - 2026-11-08 최초 작성 (docs/chromite/regression-tests.md "데이터")
 */
public class PostgreDialectBitMappingTest extends ChromiteUnitTest {

    private final PostgreDialect dialect = new PostgreDialect();

    @Test
    public void oneBitBitOfAnotherDatabaseBecomesBoolean() {
        Assertions.assertEquals(PostgreConstants.TYPE_BOOLEAN, dialect.convertExternalDataType(BasicSQLDialect.INSTANCE, bit(1), null));
        // SQL Server reports bit without a precision
        Assertions.assertEquals(PostgreConstants.TYPE_BOOLEAN, dialect.convertExternalDataType(BasicSQLDialect.INSTANCE, bit(null), null));
    }

    @Test
    public void bitStringsAndPostgresSourcesAreNotBoolean() {
        Assertions.assertNotEquals(PostgreConstants.TYPE_BOOLEAN, dialect.convertExternalDataType(BasicSQLDialect.INSTANCE, bit(8), null));
        SQLDialect postgres = new PostgreDialect();
        Assertions.assertNotEquals(PostgreConstants.TYPE_BOOLEAN, dialect.convertExternalDataType(postgres, bit(1), null));
    }

    @NotNull
    private static DBSTypedObject bit(Integer precision) {
        DBSTypedObject type = Mockito.mock(DBSTypedObject.class);
        Mockito.when(type.getTypeName()).thenReturn("bit");
        Mockito.when(type.getFullTypeName()).thenReturn("bit");
        Mockito.when(type.getTypeID()).thenReturn(Types.BIT);
        Mockito.when(type.getPrecision()).thenReturn(precision);
        Mockito.when(type.getDataKind()).thenReturn(DBPDataKind.BOOLEAN);
        return type;
    }
}
