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
package com.seizia.chromite.ext.vertica.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.generic.model.GenericView;
import com.seizia.chromite.ext.vertica.VerticaUtils;
import com.seizia.chromite.model.DBPObjectWithLazyDescription;
import com.seizia.chromite.model.DBPSystemObject;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.IPropertyCacheValidator;
import com.seizia.chromite.model.meta.LazyProperty;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.meta.PropertyLength;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;

import java.util.Date;

/**
 * VerticaView
 */
public class VerticaView extends GenericView implements DBPSystemObject, DBPObjectWithLazyDescription {

    private Date createTime;
    private boolean isTempTable;
    private boolean isSystemTable;
    private String description;

    public VerticaView(VerticaSchema container, String tableName, String tableType, JDBCResultSet dbResult) {
        super(container, tableName, tableType, dbResult);
        if (dbResult != null) {
            this.createTime = JDBCUtils.safeGetDate(dbResult, "create_time");
            this.isTempTable = JDBCUtils.safeGetBoolean(dbResult, "is_temp_table");
            this.isSystemTable = JDBCUtils.safeGetBoolean(dbResult, "is_system_table");
        }
    }

    @Property(viewable = true, order = 3)
    public Date getCreateTime() {
        return createTime;
    }

    @Property(viewable = true, order = 4)
    public boolean isTempTable() {
        return isTempTable;
    }

    public boolean isSystem() {
        return isSystemTable;
    }

    @Nullable
    @Override
    public String getDescription() {
        return description;
    }

    @Nullable
    @Override
    @Property(viewable = true, editable = true, updatable = true, length = PropertyLength.MULTILINE, order = 100)
    @LazyProperty(cacheValidator = CommentsValidator.class)
    public String getDescription(@NotNull DBRProgressMonitor monitor) throws DBException {
        if (description == null) {
            if (!((VerticaDataSource) getDataSource()).avoidCommentsReading()) {
                VerticaUtils.readTableAndColumnsDescriptions(monitor, getDataSource(), this, true);
            }
            if (description == null) {
                description = "";
            }
        }
        return description;
    }

    @Override
    public void setDescription(@Nullable String description) {
        this.description = description;
    }

    public static class CommentsValidator implements IPropertyCacheValidator<VerticaView> {

        @Override
        public boolean isPropertyCached(@NotNull VerticaView object, @NotNull Object propertyId) {
            return object.description != null;
        }
    }
}
