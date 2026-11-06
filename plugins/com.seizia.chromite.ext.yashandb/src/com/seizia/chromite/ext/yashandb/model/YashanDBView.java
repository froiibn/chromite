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
package com.seizia.chromite.ext.yashandb.model;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.oracle.model.OraclePrivTable;
import com.seizia.chromite.ext.oracle.model.OracleSchema;
import com.seizia.chromite.ext.oracle.model.OracleView;
import com.seizia.chromite.model.edit.DBEPersistAction;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

import java.sql.ResultSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;


/**
 * YashanDBView
 */
public class YashanDBView extends OracleView {

    public YashanDBView(@NotNull OracleSchema schema, @NotNull String name) {
        super(schema, name);
    }

    public YashanDBView(@NotNull OracleSchema schema, @NotNull ResultSet dbResult) {
        super(schema, dbResult);
    }

    @NotNull
    @Override
    public AdditionalInfo getAdditionalInfo(@NotNull DBRProgressMonitor monitor) {
        // YashanDB not support yet
        return new AdditionalInfo();
    }

    @NotNull
    @Override
    public Collection<OraclePrivTable> getTablePrivs(@NotNull DBRProgressMonitor monitor) throws DBException {
        // YashanDB not support yet.
        return Collections.emptyList();
    }

    @NotNull
    @Override
    @Property(hidden = true, editable = true, updatable = true, order = -1)
    public String getObjectDefinitionText(@NotNull DBRProgressMonitor monitor, @NotNull Map<String, Object> options)
            throws DBException {
        if (viewText != null) {
            return viewText;
        }
        // View DDL missing a comma
        return YashanDBUtils.getTableOrViewDDL(monitor, getTableTypeName(), this, options);
    }

    @NotNull
    @Override
    public DBEPersistAction[] getCompileActions(@NotNull DBRProgressMonitor monitor) {
        // YashanDB not support
        return new DBEPersistAction[] {};
    }

}
