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
package com.seizia.chromite.model.impl;

import org.jkiss.code.NotNull;
import com.seizia.chromite.model.data.DBDValueMeta;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCResultSet;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.exec.DBCStatement;

/**
 * Abstract result set
 */
public abstract class AbstractResultSet<SESSION extends DBCSession, STATEMENT extends DBCStatement> implements DBCResultSet {

    @NotNull
    protected final SESSION session;
    @NotNull
    protected STATEMENT statement;

    protected AbstractResultSet(@NotNull SESSION session, @NotNull STATEMENT statement) {
        this.session = session;
        this.statement = statement;
    }


    @Override
    @NotNull
    public SESSION getSession() {
        return session;
    }

    @Override
    @NotNull
    public STATEMENT getSourceStatement() {
        return statement;
    }

    @Override
    public Object getFeature(String name) {
        return null;
    }

    @Override
    public DBDValueMeta getAttributeValueMeta(int index) throws DBCException {
        return null;
    }

    @Override
    public DBDValueMeta getRowMeta() throws DBCException {
        return null;
    }

    @Override
    public String getResultSetName() throws DBCException {
        return null;
    }

    protected void beforeFetch() {
    }

    protected void afterFetch() {
    }

}
