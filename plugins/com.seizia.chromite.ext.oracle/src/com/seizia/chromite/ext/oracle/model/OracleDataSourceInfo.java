/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
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
package com.seizia.chromite.ext.oracle.model;

import com.seizia.chromite.model.exec.jdbc.JDBCDatabaseMetaData;
import com.seizia.chromite.model.impl.jdbc.JDBCDataSourceInfo;
import com.seizia.chromite.model.struct.DBSObjectType;

/**
 * OracleDataSourceInfo
 *
 * 변경 이력:
 * - 2026-11-12 Chromite: 여러 결과를 지원한다고 알림({@link #supportsMultipleResults}): 블록의 둘째 암시적 결과(DBMS_SQL.RETURN_RESULT)가
 *   보이지 않았다(앱 변경 #84, docs/chromite/sql-batch-errors.md "Oracle 암시적 결과")
 */
class OracleDataSourceInfo extends JDBCDataSourceInfo {

    public OracleDataSourceInfo(JDBCDatabaseMetaData metaData) {
        super(metaData);
    }

    @Override
    public DBSObjectType[] getSupportedObjectTypes() {
        return OracleObjectType.values();
    }

    @Override
    public boolean needsTableMetaForColumnResolution() {
        return false;
    }

    /**
     * Implicit results ({@code DBMS_SQL.RETURN_RESULT}, Oracle 12c and later) come one after another through
     * {@code getMoreResults}, as the results of a SQL Server batch do.
     */
    @Override
    public boolean supportsMultipleResults() {
        return true;
    }

}
