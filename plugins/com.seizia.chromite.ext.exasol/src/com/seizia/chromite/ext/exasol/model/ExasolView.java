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
package com.seizia.chromite.ext.exasol.model;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.ext.exasol.editors.ExasolSourceObject;
import com.seizia.chromite.ext.exasol.tools.ExasolUtils;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.exec.jdbc.JDBCStatement;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.impl.jdbc.cache.JDBCStructCache;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.meta.PropertyLength;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import com.seizia.chromite.model.struct.DBSEntityAssociation;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.model.struct.DBSObjectState;
import com.seizia.chromite.model.struct.rdb.DBSView;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ExasolView extends ExasolTableBase implements ExasolSourceObject, DBSView {


    private String owner;
    private Boolean hasRead = false;
    

    private String text;

    public ExasolView(ExasolSchema schema, String name, boolean persisted) {
        super(schema, name, persisted);
    }

    public ExasolView(DBRProgressMonitor monitor, ExasolSchema schema, ResultSet dbResult) {
        super(monitor, schema, dbResult);
        hasRead=false;

    }
    
    public ExasolView(ExasolSchema schema)
    {
        super(schema,null,false);
        text = "";
        hasRead = true;
    }


    @NotNull
    @Override
    public DBSObjectState getObjectState() {
        return DBSObjectState.NORMAL;
    }


    @Override
    @Property(viewable = true, editable = false, updatable = false, length = PropertyLength.MULTILINE, order = 40)
    public String getDescription() {
        return super.getDescription();
    }

    // -----------------
    // Properties
    // -----------------

    @NotNull
    @Property(viewable = true, order = 100)
    public String getOwner() throws DBCException {
        read();
        return owner;
    }

    private void read() throws DBCException
    {
        if (!hasRead)
        {
            JDBCSession session = DBUtils.openMetaSession(new VoidProgressMonitor(), this, "Read Table Details");
            try (JDBCStatement stmt = session.createStatement())
            {
                String sql = String.format("/*snapshot execution*/ SELECT VIEW_OWNER,VIEW_TEXT FROM SYS.EXA_ALL_VIEWS WHERE VIEW_SCHEMA = '%s' and VIEW_NAME = '%s'",
                        ExasolUtils.quoteString(this.getSchema().getName()),
                        ExasolUtils.quoteString(this.getName())
                        );
                
                try (JDBCResultSet dbResult = stmt.executeQuery(sql)) {
                    if (dbResult.next()) {
                        this.owner = JDBCUtils.safeGetString(dbResult, "VIEW_OWNER");
                        this.text = JDBCUtils.safeGetString(dbResult, "VIEW_TEXT");
                        this.hasRead = true;
                    } else {
                        this.owner = "SYS OBJECT";
                        this.text = "-- No View Text for system objects available";
                    }
                    this.hasRead = true;
                }
                
            } catch (SQLException e) {
                throw new DBCException(e, session.getExecutionContext());
            }
            
        }
        
    }

    @Override
    public boolean isView() {
        return true;
    }


    // -----------------
    // Business Contract
    // -----------------

    @Override
    public void refreshObjectState(@NotNull DBRProgressMonitor monitor) throws DBCException {
    }

    @NotNull
    @Override
    public String getFullyQualifiedName(@NotNull DBPEvaluationContext context) {
        return DBUtils.getFullQualifiedName(getDataSource(), getSchema(), this);
    }

    @Override
    public DBSObject refreshObject(@NotNull DBRProgressMonitor monitor) throws DBException {
        super.refreshObject(monitor);
        
        //force reading of attributes
        hasRead = false;
        return this;
    }

    @Override
    public ExasolTableColumn getAttribute(@NotNull DBRProgressMonitor monitor, @NotNull String attributeName) throws DBException {
        return getContainer().getViewCache().getChild(monitor, getSchema(), (ExasolView) this, attributeName);
    }

    @Override
    public List<ExasolTableColumn> getAttributes(@NotNull DBRProgressMonitor monitor) throws DBException {
        return getContainer().getViewCache().getChildren(monitor, getContainer(), this);
    }


    // -----------------
    // Associations (Imposed from DBSTable). In Exasol, Most of objects "derived"
    // from Tables don't have those..
    // -----------------
    @Override
    public Collection<? extends DBSEntityAssociation> getReferences(@NotNull DBRProgressMonitor monitor) throws DBException {
        return Collections.emptyList();
    }


    @Override
    public Collection<ExasolTableForeignKey> getAssociations(@NotNull DBRProgressMonitor monitor) throws DBException {
        return Collections.emptyList();
    }

    @NotNull
    @Override
    @Property(hidden = true, editable = true, updatable = true, order = -1)
    public String getObjectDefinitionText(@NotNull DBRProgressMonitor monitor, @NotNull Map<String, Object> options) throws DBException {
        read();
        //return SQLFormatUtils.formatSQL(getDataSource(), this.text);
        return this.text;

    }
    
    @Override
    public void setObjectDefinitionText(String sourceText) throws DBException
    {
        this.text = sourceText;
    }
    
    public String getSource() throws DBCException
    {
        read();
        return this.text;
    }

    
    @Override
    public JDBCStructCache<ExasolSchema, ExasolView, ExasolTableColumn> getCache() {
        return getContainer().getViewCache();
    }
    
}
