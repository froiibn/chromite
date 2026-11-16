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
package com.seizia.chromite.ext.greptime;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.generic.model.GenericTableBase;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLUtils;
import com.seizia.chromite.model.sql.format.SQLFormatUtils;

import java.sql.SQLException;
import java.sql.Types;
import java.util.Locale;

public final class GreptimeUtils {

    private GreptimeUtils() {
    }

    public static int typeNameToValueType(@Nullable String typeName) {
        if (typeName == null) {
            return Types.OTHER;
        }

        String normalizedType = typeName.toUpperCase(Locale.ENGLISH).trim();
        boolean unsigned = normalizedType.endsWith(" UNSIGNED"); //$NON-NLS-1$
        if (unsigned) {
            normalizedType = normalizedType.substring(0, normalizedType.length() - " UNSIGNED".length()).trim(); //$NON-NLS-1$
        }
        normalizedType = SQLUtils.stripColumnTypeModifiers(normalizedType).trim();

        if (unsigned) {
            return switch (normalizedType) {
                case "TINYINT" -> Types.SMALLINT;
                case "SMALLINT" -> Types.INTEGER;
                case "INT", "INTEGER" -> Types.BIGINT;
                case "BIGINT", "DECIMAL", "NUMERIC" -> Types.DECIMAL;
                default -> Types.OTHER;
            };
        }

        return switch (normalizedType) {
            case "BOOLEAN", "BOOL" -> Types.BOOLEAN;
            case "TINYINT" -> Types.TINYINT;
            case "SMALLINT", "INT2", "INT16" -> Types.SMALLINT;
            case "INT", "INTEGER", "INT4", "INT32" -> Types.INTEGER;
            case "BIGINT", "INT8", "INT64" -> Types.BIGINT;
            case "UINT8" -> Types.SMALLINT;
            case "UINT16" -> Types.INTEGER;
            case "UINT32" -> Types.BIGINT;
            case "UINT64" -> Types.DECIMAL;
            case "FLOAT", "FLOAT4", "FLOAT32" -> Types.FLOAT;
            case "DOUBLE", "FLOAT8", "FLOAT64" -> Types.DOUBLE;
            case "DECIMAL", "NUMERIC" -> Types.DECIMAL;
            case "CHAR" -> Types.CHAR;
            case "VARCHAR", "STRING", "TEXT" -> Types.VARCHAR;
            case "BINARY", "VARBINARY" -> Types.VARBINARY;
            case "DATE" -> Types.DATE;
            case "DATETIME", "TIMESTAMP", "TIMESTAMPSECOND", "TIMESTAMPMILLISECOND",
                 "TIMESTAMPMICROSECOND", "TIMESTAMPNANOSECOND" -> Types.TIMESTAMP;
            default -> Types.OTHER;
        };
    }

    @Nullable
    public static String loadShowCreateDDL(
        @NotNull DBRProgressMonitor monitor,
        @NotNull GenericTableBase table,
        @NotNull String showCommand
    ) throws DBCException {
        try (JDBCSession session = DBUtils.openMetaSession(monitor, table, "Load GreptimeDB DDL")) {
            String sql = showCommand + " " + table.getFullyQualifiedName(DBPEvaluationContext.DDL); //$NON-NLS-1$
            try (JDBCPreparedStatement dbStat = session.prepareStatement(sql)) {
                try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                    if (dbResult.next()) {
                        String definition = JDBCUtils.safeGetString(dbResult, 2);
                        if (definition != null) {
                            return SQLFormatUtils.formatSQL(table.getDataSource(), definition);
                        }
                    }
                }
            } catch (SQLException e) {
                throw new DBCException(e, session.getExecutionContext());
            }
        }
        return null;
    }
}
