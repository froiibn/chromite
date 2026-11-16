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
package com.seizia.chromite.ext.doris.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.generic.model.GenericStructContainer;
import com.seizia.chromite.ext.doris.DorisUtils;
import com.seizia.chromite.model.DBPScriptObject;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.meta.IPropertyCacheValidator;
import com.seizia.chromite.model.meta.LazyProperty;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.meta.PropertyGroup;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.rdb.DBSView;

import java.util.Map;

/**
 * Doris View - represents a view within a Doris database.
 */
public class DorisView extends DorisViewBase implements DBSView, DBPScriptObject {

    private static final String COL_CREATE_VIEW = "Create View"; //$NON-NLS-1$

    private final AdditionalInfo additionalInfo = new AdditionalInfo();

    public static class AdditionalInfo {
        private volatile boolean loaded = false;
        private volatile String definition;
        private volatile String definer;

        public boolean isLoaded() {
            return loaded;
        }

        @Nullable
        public String getDefinition() {
            return definition;
        }

        public void setDefinition(@Nullable String definition) {
            this.definition = definition;
        }

        @Nullable
        @Property(viewable = true, order = 4)
        public String getDefiner() {
            return definer;
        }

        public void setDefiner(@Nullable String definer) {
            this.definer = definer;
        }
    }

    public static class AdditionalInfoValidator implements IPropertyCacheValidator<DorisView> {
        @Override
        public boolean isPropertyCached(@NotNull DorisView object, @NotNull Object propertyId) {
            return object.additionalInfo.loaded;
        }
    }

    public DorisView(
        @NotNull GenericStructContainer container,
        @Nullable String viewName,
        @Nullable String tableType,
        @Nullable JDBCResultSet dbResult
    ) {
        super(container, viewName, tableType, dbResult);
    }

    @Override
    public boolean isView() {
        return true;
    }

    @Nullable
    @Override
    public String getDDL() {
        return additionalInfo.getDefinition();
    }

    @NotNull
    public AdditionalInfo getAdditionalInfo() {
        return additionalInfo;
    }

    @NotNull
    @PropertyGroup()
    @LazyProperty(cacheValidator = AdditionalInfoValidator.class)
    public AdditionalInfo getAdditionalInfo(@NotNull DBRProgressMonitor monitor) throws DBCException {
        synchronized (additionalInfo) {
            if (!additionalInfo.loaded) {
                loadAdditionalInfo(monitor);
            }
            return additionalInfo;
        }
    }

    private void loadAdditionalInfo(@NotNull DBRProgressMonitor monitor) throws DBCException {
        if (!isPersisted()) {
            additionalInfo.loaded = true;
            return;
        }
        String definition = DorisUtils.loadShowCreateDDL(
            monitor, this, "Load view definition", "SHOW CREATE VIEW", COL_CREATE_VIEW); //$NON-NLS-1$ //$NON-NLS-2$
        additionalInfo.setDefinition(definition);
        additionalInfo.loaded = true;
    }

    @NotNull
    @Override
    @Property(hidden = true, editable = true, updatable = true, order = -1)
    public String getObjectDefinitionText(@NotNull DBRProgressMonitor monitor, @NotNull Map<String, Object> options) throws DBException {
        String definition = getAdditionalInfo(monitor).getDefinition();
        if (definition == null && !isPersisted()) {
            return "";
        }
        return definition != null ? definition : "";
    }

    public void setObjectDefinitionText(@Nullable String sourceText) {
        getAdditionalInfo().setDefinition(sourceText);
    }
}
