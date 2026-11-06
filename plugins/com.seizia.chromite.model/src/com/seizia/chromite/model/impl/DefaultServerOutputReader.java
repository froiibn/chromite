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
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionResult;
import com.seizia.chromite.model.exec.DBCStatement;
import com.seizia.chromite.model.exec.output.DBCOutputWriter;
import com.seizia.chromite.model.exec.output.DBCServerOutputReader;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

import java.util.List;

/**
 * Default output reader.
 * Dumps SQL warnings
 */
public class DefaultServerOutputReader implements DBCServerOutputReader {
    @Override
    public boolean isServerOutputEnabled() {
        return true;
    }

    @Override
    public boolean isAsyncOutputReadSupported() {
        return false;
    }

    @Override
    public void readServerOutput(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext context,
        @Nullable DBCExecutionResult executionResult,
        @Nullable DBCStatement statement,
        @NotNull DBCOutputWriter output
    ) throws DBCException {
        if (executionResult != null) {
            dumpWarnings(output, executionResult.getWarnings());
        }
    }

    protected void dumpWarnings(@NotNull DBCOutputWriter output, List<Throwable> warnings) {
        if (warnings != null && warnings.size() > 0) {
            for (Throwable warning : warnings) {
                dumpWarning(output, warning);
            }
        }
    }

    protected void dumpWarning(@NotNull DBCOutputWriter output, @NotNull Throwable warning) {
        output.println(null, warning.getMessage());
    }
}
