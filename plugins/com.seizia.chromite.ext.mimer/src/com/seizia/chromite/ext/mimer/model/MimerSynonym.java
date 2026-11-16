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
package com.seizia.chromite.ext.mimer.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.generic.model.GenericStructContainer;
import com.seizia.chromite.ext.generic.model.GenericSynonym;
import com.seizia.chromite.model.DBPSaveableObject;
import com.seizia.chromite.model.DBPScriptObject;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.meta.PropertyLength;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObject;

import java.util.Map;

/**
 * Mimer SQL synonym - an alias for a table/view, possibly in another schema, read from
 * {@code INFORMATION_SCHEMA.EXT_SYNONYMS} (only table-like targets - that view has no column
 * for any other object kind). Adds create/drop support - see {@link
 * com.seizia.chromite.ext.mimer.edit.MimerSynonymManager}.
 *
 * @author Mimer Information Technology
 */
public class MimerSynonym extends GenericSynonym implements DBPScriptObject, DBPSaveableObject, MimerCommentable {

    private String targetSchema;
    private String targetName;
    private boolean persisted = true;
    private String comment;

    public MimerSynonym(@NotNull GenericStructContainer container, @NotNull JDBCResultSet dbResult) {
        super(container, JDBCUtils.safeGetString(dbResult, "SYNONYM_NAME"), null);
        this.targetSchema = JDBCUtils.safeGetString(dbResult, "TABLE_SCHEMA");
        this.targetName = JDBCUtils.safeGetString(dbResult, "TABLE_NAME");
    }

    /**
     * For a brand-new, not-yet-created synonym - see {@link com.seizia.chromite.ext.mimer.edit.MimerSynonymManager}.
     */
    public MimerSynonym(@NotNull GenericStructContainer container, @NotNull String name) {
        super(container, name, null);
        this.persisted = false;
    }

    @NotNull
    @Property(viewable = true, editable = true, order = 2)
    public String getTargetSchema() {
        return targetSchema;
    }

    public void setTargetSchema(@NotNull String targetSchema) {
        this.targetSchema = targetSchema;
    }

    @NotNull
    @Property(viewable = true, editable = true, order = 3)
    public String getTargetName() {
        return targetName;
    }

    public void setTargetName(@NotNull String targetName) {
        this.targetName = targetName;
    }

    @Nullable
    @Override
    @Property(viewable = true, order = 4)
    public DBSObject getTargetObject(@Nullable DBRProgressMonitor monitor) throws DBException {
        if (monitor == null || targetSchema == null || targetName == null) {
            return null;
        }
        GenericStructContainer schema = getDataSource().getSchema(targetSchema);
        return schema == null ? null : schema.getChild(monitor, targetName);
    }

    /**
     * Hides the inherited {@code GenericSynonym.getDescription()} - always {@code null} here
     * (both constructors pass {@code null}), but a redundant "Description" row next to the
     * real {@link #getComment} below.
     */
    @Nullable
    @Override
    @Property(hidden = true)
    public String getDescription() {
        return super.getDescription();
    }

    /**
     * {@code COMMENT ON SYNONYM "schema"."name" IS '...'}. See {@link
     * com.seizia.chromite.ext.mimer.edit.MimerSynonymManager} for the write side.
     */
    @Nullable
    @Property(viewable = true, editable = true, updatable = true, length = PropertyLength.MULTILINE, order = 10)
    public String getComment(@NotNull DBRProgressMonitor monitor) throws DBException {
        if (comment == null && persisted) {
            comment = MimerUtils.readObjectComment(monitor, this, getParentObject().getName(), null, getName(), "SYNONYM");
        }
        return comment;
    }

    public void setComment(@Nullable String comment) {
        this.comment = comment;
    }

    @Override
    public boolean isPersisted() {
        return persisted;
    }

    @Override
    public void setPersisted(boolean persisted) {
        this.persisted = persisted;
    }

    /**
     * {@code CREATE SYNONYM "schema"."name" FOR "targetSchema"."targetName"} - reconstructed,
     * not stored; Mimer SQL has no {@code ALTER SYNONYM}, so this is create/drop only, same as
     * {@link MimerDomain}.
     */
    @NotNull
    @Override
    public String getObjectDefinitionText(@NotNull DBRProgressMonitor monitor, @NotNull Map<String, Object> options) {
        return "CREATE SYNONYM \"" + getParentObject().getName().replace("\"", "\"\"")
            + "\".\"" + getName().replace("\"", "\"\"") + "\"\n" +
            "    FOR \"" + targetSchema.replace("\"", "\"\"") + "\".\"" + targetName.replace("\"", "\"\"") + "\"";
    }
}
