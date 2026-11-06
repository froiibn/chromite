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

package com.seizia.chromite.tools.transfer.stream.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceInfo;
import com.seizia.chromite.model.data.DBDFormatSettings;
import com.seizia.chromite.model.data.DBDValueHandler;
import com.seizia.chromite.model.data.DBDValueHandlerProvider;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.AbstractSimpleDataSource;
import com.seizia.chromite.model.impl.data.DefaultValueHandler;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.SQLDialect;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSTypedObject;
import com.seizia.chromite.runtime.DBWorkbench;

import java.util.Collection;

/**
 * Stream data source. It is a fake client-side datasource to emulate database-like data producers.
 */
public class StreamDataSource extends AbstractSimpleDataSource<StreamExecutionContext> implements DBDValueHandlerProvider {

    private final StreamDataSourceDialect dialect;

    public StreamDataSource(@NotNull StreamDataSourceContainer container) {
        super(container);
        this.executionContext = new StreamExecutionContext(this, "Main");
        this.dialect = new StreamDataSourceDialect();
    }

    public StreamDataSource(@NotNull String inputName) {
        this(new StreamDataSourceContainer(inputName, DBWorkbench.getPlatform().getWorkspace().getActiveProject()));
    }

    @NotNull
    @Override
    public DBPDataSourceInfo getInfo() {
        return new StreamDataSourceInfo();
    }

    @NotNull
    @Override
    public SQLDialect getSQLDialect() {
        return dialect;
    }

    @Override
    public void initialize(@NotNull DBRProgressMonitor monitor) throws DBException {

    }

    @NotNull
    @Override
    public StreamExecutionContext openIsolatedContext(@NotNull DBRProgressMonitor monitor, @NotNull String purpose, @Nullable DBCExecutionContext initFrom) throws DBException {
        return new StreamExecutionContext(this, purpose);
    }

    // We need to implement value handler provider to pass default value handler for attribute bindings
    @Nullable
    @Override
    public DBDValueHandler getValueHandler(
        @NotNull DBPDataSource dataSource,
        @NotNull DBDFormatSettings preferences,
        @NotNull DBSTypedObject typedObject
    ) {
        return DefaultValueHandler.INSTANCE;
    }

    @Override
    public Collection<? extends DBSObject> getChildren(@NotNull DBRProgressMonitor monitor) throws DBException {
        return null;
    }

    @Nullable
    @Override
    public DBSObject getChild(@NotNull DBRProgressMonitor monitor, @NotNull String childName) throws DBException {
        return null;
    }

    @NotNull
    @Override
    public Class<? extends DBSObject> getPrimaryChildType(@Nullable DBRProgressMonitor monitor) throws DBException {
        return DBSObject.class;
    }

    @Override
    public void cacheStructure(@NotNull DBRProgressMonitor monitor, int scope) throws DBException {

    }

}
