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
package com.seizia.chromite.ui.editors;

import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.struct.DBSObject;

public class SimpleDatabaseEditorContext implements DatabaseEditorContext {

    private final DBPDataSourceContainer dataSourceContainer;

    public SimpleDatabaseEditorContext(DBPDataSourceContainer dataSourceContainer) {
        this.dataSourceContainer = dataSourceContainer;
    }

    @Override
    public DBPDataSourceContainer getDataSourceContainer() {
        return dataSourceContainer;
    }

    @Override
    public DBSObject getSelectedObject() {
        return null;
    }

    @Nullable
    @Override
    public DBCExecutionContext getExecutionContext() {
        return null;
    }
}
