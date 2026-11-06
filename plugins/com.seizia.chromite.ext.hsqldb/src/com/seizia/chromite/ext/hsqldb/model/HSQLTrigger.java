/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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
package com.seizia.chromite.ext.hsqldb.model;

import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.generic.model.GenericTableBase;
import com.seizia.chromite.ext.generic.model.GenericTableTrigger;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCResultSet;
import com.seizia.chromite.model.impl.jdbc.JDBCUtils;
import com.seizia.chromite.model.meta.Property;
import com.seizia.chromite.model.sql.format.SQLFormatUtils;
import org.jkiss.utils.CommonUtils;

/**
 * HSQLTrigger
 */
public class HSQLTrigger extends GenericTableTrigger {

    private String manipulation;
    private String orientation;
    private String timing;

    private String statement;

    HSQLTrigger(GenericTableBase table, String name, JDBCResultSet dbResult) {
        super(table, name, null);
        manipulation = JDBCUtils.safeGetString(dbResult, "EVENT_MANIPULATION");
        orientation = JDBCUtils.safeGetString(dbResult, "ACTION_ORIENTATION");
        timing = JDBCUtils.safeGetString(dbResult, "ACTION_TIMING");
        statement = JDBCUtils.safeGetString(dbResult, "ACTION_STATEMENT");
        String newReference = JDBCUtils.safeGetString(dbResult, "ACTION_REFERENCE_NEW_ROW");
        if (CommonUtils.isEmpty(newReference)) {
            newReference = JDBCUtils.safeGetString(dbResult, "ACTION_REFERENCE_NEW_TABLE");
        }
        String oldReference = null;
        if (CommonUtils.isEmpty(newReference)) {
            oldReference = JDBCUtils.safeGetString(dbResult, "ACTION_REFERENCE_OLD_ROW");
            if (CommonUtils.isEmpty(oldReference)) {
                oldReference = JDBCUtils.safeGetString(dbResult, "ACTION_REFERENCE_OLD_TABLE");
            }
        }
        if (statement != null) {
            statement = "CREATE TRIGGER " + name + " " + timing + " " + manipulation + " ON " + DBUtils.getObjectFullName(table, DBPEvaluationContext.DML) +
                    (newReference != null ? "\nREFERENCING NEW AS " + newReference : oldReference != null ? "\nREFERENCING OLD AS " + oldReference : "") +
                    " FOR EACH " + orientation + "\n" +
                    SQLFormatUtils.formatSQL(getDataSource(), statement);
        }
    }

    @Property(viewable = true, order = 11)
    public String getManipulation() {
        return manipulation;
    }

    @Property(viewable = true, order = 12)
    public String getOrientation() {
        return orientation;
    }

    @Property(viewable = true, order = 10)
    public String getTiming() {
        return timing;
    }

    public String getStatement() {
        return statement;
    }

    @Nullable
    @Override
    public String getDescription() {
        return super.getDescription();
    }
}
