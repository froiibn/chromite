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
package com.seizia.chromite.ext.athena.model;

import org.jkiss.code.NotNull;
import com.seizia.chromite.ext.generic.model.GenericSQLDialect;
import com.seizia.chromite.model.exec.jdbc.JDBCDatabaseMetaData;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.JDBCDataSource;
import com.seizia.chromite.model.sql.SQLUtils;
import com.seizia.chromite.model.struct.DBSTypedObject;

/**
 * Athena SQL dialect
 */
public class AthenaSQLDialect extends GenericSQLDialect {
    private static final String[] ATHENA_NONKEYWORDS = {
        "DEFAULT"
    };
    private static final String[] ATHENA_KEYWORDS = {
        "SHOW"
    };

    public AthenaSQLDialect() {
        super("Athena", "aws_athena");
    }

    // https://docs.aws.amazon.com/athena/latest/ug/tables-databases-columns-names.html#tables-databases-columns-names-complex-types
    @Override
    public boolean validIdentifierPart(char c, boolean quoted) {
        return SQLUtils.isLatinLetter(c) || Character.isDigit(c) || c == '_' || (quoted && validCharacters.indexOf(c) != -1);
    }

    @Override
    public boolean validIdentifierStart(char c) {
        return SQLUtils.isLatinLetter(c);
    }

    @NotNull
    @Override
    public String getTypeCastClause(
        @NotNull DBSTypedObject attribute,
        @NotNull String expression,
        boolean isInCondition,
        boolean exprIsAttrRef
    ) {
        if (isInCondition && attribute.getFullTypeName().equalsIgnoreCase("date") && !exprIsAttrRef) {
            return "CAST(" + expression + " AS date)";
        } else {
            return super.getTypeCastClause(attribute, expression, isInCondition, exprIsAttrRef);
        }
    }

    @Override
    public void initDriverSettings(JDBCSession session, JDBCDataSource dataSource, JDBCDatabaseMetaData metaData) {
        super.initDriverSettings(session, dataSource, metaData);

        for (String word : ATHENA_NONKEYWORDS) {
            removeSQLKeyword(word);
        }
        for (String word : ATHENA_KEYWORDS) {
            addSQLKeyword(word);
        }
    }
}
