/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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
package com.seizia.chromite.model.impl;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionResult;
import com.seizia.chromite.model.exec.DBCStatement;
import com.seizia.chromite.model.exec.output.DBCOutputWriter;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

import java.util.Arrays;

public class AsyncServerOutputReader extends DefaultServerOutputReader {
    private static final Log log = Log.getLog(AsyncServerOutputReader.class);

    @Override
    public boolean isAsyncOutputReadSupported() {
        return true;
    }

    @Override
    public void readServerOutput(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext context,
        @Nullable DBCExecutionResult executionResult,
        @Nullable DBCStatement statement,
        @NotNull DBCOutputWriter output
    ) throws DBCException {
        if (statement == null) {
            super.readServerOutput(monitor, context, executionResult, null, output);
        } else {
            // Do not read from connection warnings as it blocks statements cancellation and other connection-level stuff.
            // See #7885
/*
                try {
                    SQLWarning connWarning = ((JDBCSession) statement.getSession()).getWarnings();
                    if (connWarning != null) {
                        dumpWarnings(output, Collections.singletonList(connWarning));
                    }
                } catch (SQLException e) {
                    log.debug(e);
                }
*/

            Throwable[] statementWarnings = statement.getStatementWarnings();
            if (statementWarnings != null && statementWarnings.length > 0) {
                dumpWarnings(output, Arrays.asList(statementWarnings));
            }
        }
    }
}

