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
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionSource;
import com.seizia.chromite.model.exec.DBCScriptContext;
import com.seizia.chromite.model.struct.DBSDataContainer;

/**
 * AbstractExecutionSource
 */
public class AbstractExecutionSource implements DBCExecutionSource {

    private final DBSDataContainer dataContainer;
    private final DBCExecutionContext executionContext;
    private final transient Object controller;
    private final Object descriptor;
    private DBCScriptContext scriptContext;

    public AbstractExecutionSource(DBSDataContainer dataContainer, DBCExecutionContext executionContext, Object controller) {
        this(dataContainer, executionContext, controller, null);
    }

    public AbstractExecutionSource(DBSDataContainer dataContainer, DBCExecutionContext executionContext, Object controller, Object descriptor) {
        this.dataContainer = dataContainer;
        this.executionContext = executionContext;
        this.controller = controller;
        this.descriptor = descriptor;
    }

    @Nullable
    @Override
    public DBSDataContainer getDataContainer() {
        return dataContainer;
    }

    @NotNull
    @Override
    public Object getExecutionController() {
        return controller;
    }

    @Nullable
    @Override
    public Object getSourceDescriptor() {
        return descriptor;
    }

    @Nullable
    @Override
    public DBCScriptContext getScriptContext() {
        return scriptContext;
    }

    public void setScriptContext(DBCScriptContext scriptContext) {
        this.scriptContext = scriptContext;
    }
}
