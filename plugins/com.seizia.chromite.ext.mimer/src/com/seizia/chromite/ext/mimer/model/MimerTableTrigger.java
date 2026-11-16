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
import com.seizia.chromite.ext.generic.model.GenericTableBase;
import com.seizia.chromite.ext.generic.model.GenericTableTrigger;
import com.seizia.chromite.model.DBPSaveableObject;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCStatement;
import com.seizia.chromite.model.impl.jdbc.cache.JDBCObjectCache;
import com.seizia.chromite.model.meta.Association;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.meta.PropertyLength;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.struct.DBSObjectWithScript;

import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;

/**
 * Mimer SQL table trigger - {@code GenericTableTrigger} + create/drop/edit support, see {@link
 * com.seizia.chromite.ext.mimer.edit.MimerTableTriggerManager}. {@link #getObjectDefinitionText}
 * must be re-declared (not just inherited) so its {@code @Property} annotation's declaring
 * class resolves to this class, where {@link #setObjectDefinitionText} actually lives -
 * otherwise the Source tab's Save silently discards edits. {@link DBPSaveableObject} backs the
 * not-yet-persisted create flow ({@code GenericTrigger#isPersisted()} is hardcoded {@code true}).
 * <p>
 * Also backs view (always {@code INSTEAD OF}) triggers, not just table triggers - {@code
 * GenericView extends GenericTableBase}, so the same per-schema trigger cache covers both; only
 * the create dialog branches its Timing/Granularity fields on whether {@link #getTable()} is a
 * view. Not used for the separate, flat schema-level "Triggers" node, which stays read-only.
 *
 * @author Mimer Information Technology
 */
public class MimerTableTrigger extends GenericTableTrigger implements DBSObjectWithScript, DBPSaveableObject, MimerCommentable {

    private boolean persisted = true;
    private String comment;
    private final UsesCache usesCache = new UsesCache();

    public MimerTableTrigger(@NotNull GenericTableBase container, @NotNull String name, @NotNull String description) {
        super(container, name, description);
    }

    /**
     * For a brand-new, not-yet-created trigger - see {@link com.seizia.chromite.ext.mimer.edit.MimerTableTriggerManager}.
     */
    public MimerTableTrigger(@NotNull GenericTableBase container, @NotNull String name) {
        super(container, name, null);
        this.persisted = false;
    }

    @Override
    public boolean isPersisted() {
        return persisted;
    }

    @Override
    public void setPersisted(boolean persisted) {
        this.persisted = persisted;
    }

    @NotNull
    @Override
    @Property(hidden = true, editable = true, updatable = true, order = -1)
    public String getObjectDefinitionText(@NotNull DBRProgressMonitor monitor, @NotNull Map<String, Object> options) throws DBException {
        return super.getObjectDefinitionText(monitor, options);
    }

    @Override
    public void setObjectDefinitionText(@NotNull String source) {
        setSource(source);
    }

    /**
     * {@code COMMENT ON TRIGGER "schema"."name" IS '...'}. See {@link
     * com.seizia.chromite.ext.mimer.edit.MimerTableTriggerManager} for the write side.
     */
    @Nullable
    @Property(viewable = true, editable = true, updatable = true, length = PropertyLength.MULTILINE, order = 100)
    public String getComment(@NotNull DBRProgressMonitor monitor) throws DBException {
        if (comment == null && persisted) {
            comment = MimerUtils.readObjectComment(monitor, this, getTable().getSchema().getName(), null, getName(), "TRIGGER");
        }
        return comment;
    }

    public void setComment(@Nullable String comment) {
        this.comment = comment;
    }

    @NotNull
    @Association
    public Collection<MimerObjectUses> getUses(@NotNull DBRProgressMonitor monitor) throws DBException {
        return usesCache.getAllObjects(monitor, this);
    }

    static class UsesCache extends JDBCObjectCache<MimerTableTrigger, MimerObjectUses> {
        @NotNull
        @Override
        protected JDBCStatement prepareObjectsStatement(
            @NotNull JDBCSession session,
            @NotNull MimerTableTrigger owner
        ) throws SQLException {
            return MimerObjectUses.prepareUsesStatement(session, owner.getTable().getSchema().getName(), owner.getName(), "TRIGGER");
        }

        @NotNull
        @Override
        protected MimerObjectUses fetchObject(
            @NotNull JDBCSession session,
            @NotNull MimerTableTrigger owner,
            @NotNull JDBCResultSet resultSet
        ) throws SQLException, DBException {
            return new MimerObjectUses(owner, resultSet);
        }
    }
}
