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
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.impl.jdbc.data.handlers.JDBCStringValueHandler;
import com.seizia.chromite.model.struct.DBSTypedObject;

import java.sql.SQLException;

public class ClikhouseInetTypeValueHandler extends JDBCStringValueHandler {

    static final ClikhouseInetTypeValueHandler INSTANCE = new ClikhouseInetTypeValueHandler();

    @Override
    protected Object fetchColumnValue(@NotNull DBCSession session, @NotNull JDBCResultSet resultSet, @NotNull DBSTypedObject type, int index) throws SQLException {
        // Use getString instead of getObject to avoid incorrect result of toString method
        return resultSet.getString(index);
    }
}
