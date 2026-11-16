/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
 * Modifications Copyright (C) 2026 Chromite contributors
 * Copyright (C) 2017-2018 Alexander Fedorov (alexander.fedorov@jkiss.org)
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
package com.seizia.chromite.ext.postgresql.debug.core;

import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.postgresql.debug.PostgreDebugConstants;
import com.seizia.chromite.ext.postgresql.model.PostgreDataSource;
import com.seizia.chromite.ext.postgresql.model.PostgreDatabase;
import com.seizia.chromite.ext.postgresql.model.PostgreProcedure;
import com.seizia.chromite.ext.postgresql.model.PostgreSchema;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import org.jkiss.utils.CommonUtils;

import java.util.Map;

/**
 * Stores the debugged function in a debug configuration and finds it again.
 *
 * 변경 이력:
 * - 2026-11-07 Swing 디버거용으로 복원(커밋 745f87f8ee에서 삭제): 실행 구성의 프로젝트·연결·디버그 종류 키를 빼고 함수 위치만 저장
 *   (docs/chromite/procedure-debugger.md)
 */
public class PostgreSqlDebugCore {

    public static void saveFunction(PostgreProcedure procedure, Map<String, Object> configuration) {
        configuration.put(PostgreDebugConstants.ATTR_DATABASE_NAME, procedure.getDatabase().getName());
        configuration.put(PostgreDebugConstants.ATTR_SCHEMA_NAME, procedure.getSchema().getName());
        configuration.put(PostgreDebugConstants.ATTR_FUNCTION_OID, String.valueOf(procedure.getObjectId()));
    }

    public static PostgreProcedure resolveFunction(DBRProgressMonitor monitor, DBPDataSourceContainer dsContainer, Map<String, Object> configuration) throws DBException {
        if (!dsContainer.isConnected()) {
            dsContainer.connect(monitor, true, true);
        }
        long functionId = CommonUtils.toLong(configuration.get(PostgreDebugConstants.ATTR_FUNCTION_OID));
        String databaseName = (String)configuration.get(PostgreDebugConstants.ATTR_DATABASE_NAME);
        String schemaName = (String)configuration.get(PostgreDebugConstants.ATTR_SCHEMA_NAME);
        PostgreDataSource ds = (PostgreDataSource) dsContainer.getDataSource();
        PostgreDatabase database = ds.getDatabase(databaseName);
        if (database != null) {
            PostgreSchema schema = database.getSchema(monitor, schemaName);
            if (schema != null) {
                PostgreProcedure function = schema.getProcedure(monitor, functionId);
                if (function != null) {
                    return function;
                }
                throw new DBException("Function " + functionId + " not found in schema " + schemaName);
            } else {
                throw new DBException("Schema '" + schemaName + "' not found in database " + databaseName);
            }
        } else {
            throw new DBException("Database '" + databaseName + "' not found");
        }
    }
}
