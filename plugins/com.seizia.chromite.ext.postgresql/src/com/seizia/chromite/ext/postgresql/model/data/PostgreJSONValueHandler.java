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
package com.seizia.chromite.ext.postgresql.model.data;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.postgresql.PostgreUtils;
import com.seizia.chromite.ext.postgresql.model.PostgreDataSource;
import com.seizia.chromite.model.data.DBDContent;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.impl.jdbc.data.handlers.JDBCContentValueHandler;
import com.seizia.chromite.model.struct.DBSTypedObject;
import com.seizia.chromite.utils.ContentUtils;

import java.sql.SQLException;

/**
 * PostgreJSONValueHandler
 *
 * 설계: json/jsonb values are PostgreContentJSON, which binds as Types.OTHER. Text content of other databases
 * (Oracle JSON, character LOBs) is turned into it too; bound as character data, PostgreSQL rejected it
 * ("column is of type jsonb but expression is of type character varying").
 *
 * 변경 이력:
 * - 2026-11-07 Chromite: convert content values of other handlers (Copy Tables Oracle JSON -> jsonb).
 */
public class PostgreJSONValueHandler extends JDBCContentValueHandler {

    public static final PostgreJSONValueHandler INSTANCE = new PostgreJSONValueHandler();

    @Override
    protected DBDContent fetchColumnValue(@NotNull DBCSession session, @NotNull JDBCResultSet resultSet, @NotNull DBSTypedObject type, int index) throws SQLException {
        String json = resultSet.getString(index);
        return new PostgreContentJSON(session.getExecutionContext(), json);
    }

    @Override
    public DBDContent getValueFromObject(
        @NotNull DBCSession session,
        @NotNull DBSTypedObject type,
        @Nullable Object object,
        boolean copy,
        boolean validateValue
    ) throws DBCException {
        PostgreDataSource dataSource = (PostgreDataSource) session.getDataSource();
        boolean isPgObject = PostgreUtils.isPgObject(dataSource, object);
        if (isPgObject) {
            object = PostgreUtils.extractPGObjectValue(object, dataSource);
        }
        return switch (object) {
            case null -> new PostgreContentJSON(session.getExecutionContext(), null);
            case PostgreContentJSON contentJSON -> copy ? contentJSON.cloneValue(session.getProgressMonitor()) : contentJSON;
            case String stringValue -> new PostgreContentJSON(session.getExecutionContext(), stringValue);
            case DBDContent content when !content.isNull() ->
                new PostgreContentJSON(session.getExecutionContext(), ContentUtils.getContentStringValue(session.getProgressMonitor(), content));
            default -> super.getValueFromObject(session, type, object, copy, validateValue);
        };
    }
}
