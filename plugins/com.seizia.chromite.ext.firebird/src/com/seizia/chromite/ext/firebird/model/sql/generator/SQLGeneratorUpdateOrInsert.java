/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
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
package com.seizia.chromite.ext.firebird.model.sql.generator;

import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.model.DBPEvaluationContext;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.generator.SQLGeneratorTable;
import com.seizia.chromite.model.struct.DBSEntity;
import com.seizia.chromite.model.struct.DBSEntityAttribute;
import org.jkiss.utils.CommonUtils;

import java.util.Collection;

public class SQLGeneratorUpdateOrInsert extends SQLGeneratorTable {

    @Override
    public void generateSQL(DBRProgressMonitor monitor, StringBuilder sql, DBSEntity object) throws DBException {
        Collection<? extends DBSEntityAttribute> keyAttributes = getKeyAttributes(monitor, object);

        sql.append("UPDATE OR INSERT INTO ").append(getEntityName(object)).append(getLineSeparator());
        sql.append("(");
        boolean hasAttr = false;
        for (DBSEntityAttribute attr : getAllAttributes(monitor, object)) {
            if (DBUtils.isPseudoAttribute(attr) || DBUtils.isHiddenObject(attr)) {
                continue;
            }
            if (hasAttr) {
                sql.append(", ");
            }
            sql.append(DBUtils.getObjectFullName(attr, DBPEvaluationContext.DML));
            hasAttr = true;
        }
        sql.append(")").append(getLineSeparator());

        sql.append("VALUES (");
        hasAttr = false;
        for (DBSEntityAttribute attr : getAllAttributes(monitor, object)) {
            if (DBUtils.isPseudoAttribute(attr) || DBUtils.isHiddenObject(attr)) {
                continue;
            }
            if (hasAttr) {
                sql.append(", ");
            }
            sql.append(":").append(DBUtils.getObjectFullName(attr, DBPEvaluationContext.DML));
            hasAttr = true;
        }
        sql.append(")").append(getLineSeparator());

        if (!CommonUtils.isEmpty(keyAttributes)) {
            sql.append("MATCHING (");
            hasAttr = false;
            for (DBSEntityAttribute attr : keyAttributes) {
                if (hasAttr) {
                    sql.append(", ");
                }
                sql.append(DBUtils.getQuotedIdentifier(attr));
                hasAttr = true;
            }
            sql.append(")");
        } else {
            sql.append("MATCHING (/* specify key columns here, e.g. ID */)");
        }
        sql.append(";").append(getLineSeparator());
    }
}
