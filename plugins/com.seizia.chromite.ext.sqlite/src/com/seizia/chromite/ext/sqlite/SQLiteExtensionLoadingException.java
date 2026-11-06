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
package com.seizia.chromite.ext.sqlite;

import org.jkiss.code.NotNull;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionContext;

public class SQLiteExtensionLoadingException extends DBCException {
    public SQLiteExtensionLoadingException(@NotNull Throwable cause, @NotNull DBCExecutionContext context) {
        super("SQLite native extension loading is disabled. Enable native extensions in this connection's Extensions settings, "
            + "then reconnect. The failed query will not be retried automatically.", cause, context);
    }
}
