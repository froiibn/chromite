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

package com.seizia.chromite.debug.jdbc;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.jkiss.code.NotNull;
import com.seizia.chromite.debug.DBGEvent;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.runtime.AbstractJob;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.utils.GeneralUtils;

import java.sql.SQLException;
import java.sql.Statement;

public class DBGJDBCWorker extends AbstractJob {

    private final DBGJDBCSession debugSession;
    private final String sql;
    private final DBGEvent before;
    private final DBGEvent after;

    public DBGJDBCWorker(DBGJDBCSession debugSession, String name, String sqlCommand, DBGEvent begin, DBGEvent end) {
        super(name);
        this.debugSession = debugSession;
        this.sql = sqlCommand;
        this.before = begin;
        this.after = end;
    }

    @NotNull
    @Override
    protected IStatus run(@NotNull DBRProgressMonitor monitor) {
        monitor.beginTask("Execute debug job", 1);
        try (JDBCSession session = debugSession.getControllerConnection().openSession(monitor, DBCExecutionPurpose.UTIL, "Run debug job")) {
            monitor.subTask(sql);
            try (Statement stmt = session.createStatement()) {
                debugSession.fireEvent(before);
                stmt.execute(sql);
                debugSession.fireEvent(after);
                return Status.OK_STATUS;
            }
        } catch (SQLException e) {
            return GeneralUtils.makeExceptionStatus(String.format("Failed to execute %s", sql), e);
        } finally {
            monitor.done();
        }
    }


}
