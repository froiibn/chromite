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
package com.seizia.chromite.ext.clickhouse.model.data;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.data.DBDFormatSettings;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBCStatement;
import com.seizia.chromite.model.exec.jdbc.JDBCPreparedStatement;
import com.seizia.chromite.model.impl.jdbc.data.handlers.JDBCDateTimeValueHandler;
import com.seizia.chromite.model.messages.ModelMessages;
import com.seizia.chromite.model.struct.DBSTypedObject;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.text.Format;
import java.text.SimpleDateFormat;

public class ClickHouseDateTimeValueHandler extends JDBCDateTimeValueHandler {
    public static final String CLICKHOUSE_TIMESTAMP_FORMAT = "yyyy-MM-dd HH:mm:ss";
    public static final ThreadLocal<SimpleDateFormat> DEFAULT_DATETIME_FORMAT = ThreadLocal.withInitial(() -> new SimpleDateFormat("''" + CLICKHOUSE_TIMESTAMP_FORMAT + "''"));

    public ClickHouseDateTimeValueHandler(DBDFormatSettings formatSettings) {
        super(formatSettings);
    }

    @Nullable
    @Override
    protected Format getNativeValueFormat(DBSTypedObject type) {
        return switch (type.getTypeID()) {
            case Types.TIMESTAMP -> DEFAULT_DATETIME_FORMAT.get();
            case Types.TIME -> DEFAULT_TIME_FORMAT.get();
            case Types.DATE -> DEFAULT_DATE_FORMAT.get();
            default -> null;
        };
    }

    @Override
    public void bindValueObject(
        @NotNull DBCSession session,
        @NotNull DBCStatement statement,
        @NotNull DBSTypedObject type,
        int index,
        @Nullable Object value
    ) throws DBCException {

        if (value instanceof Timestamp timestamp) {
            // ClickHouse DateTime type has no NANOSECONDS part
            JDBCPreparedStatement dbStat = (JDBCPreparedStatement) statement;
            try {
                dbStat.setObject(index + 1, timestamp);
            } catch (SQLException e) {
                throw new DBCException(ModelMessages.model_jdbc_exception_could_not_bind_statement_parameter, e);            }
        } else {
            super.bindValueObject(session, statement, type, index, value);
        }
    }

}
