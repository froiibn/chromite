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
package com.seizia.chromite.ext.duckdb.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.generic.model.GenericSQLDialect;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.exec.jdbc.JDBCDatabaseMetaData;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.impl.jdbc.JDBCDataSource;
import com.seizia.chromite.model.sql.parser.rules.SQLDollarQuoteRule;
import com.seizia.chromite.model.text.parser.TPRule;
import com.seizia.chromite.model.text.parser.TPRuleProvider;
import org.jkiss.utils.CommonUtils;

import java.util.List;

public final class DuckDBSQLDialect extends GenericSQLDialect implements TPRuleProvider {
    private static final List<String> DUCKDB_KEYWORDS = List.of(
        "INSTALL",
        "LOAD"
    );

    @Override
    public void initDriverSettings(JDBCSession session, JDBCDataSource dataSource, JDBCDatabaseMetaData metaData) {
        super.initDriverSettings(session, dataSource, metaData);
        addSQLKeywords(DUCKDB_KEYWORDS);
    }

    @NotNull
    @Override
    public TPRule[] extendRules(@Nullable DBPDataSourceContainer dataSource, @NotNull TPRuleProvider.RulePosition position) {
        if (position == TPRuleProvider.RulePosition.INITIAL || position == TPRuleProvider.RulePosition.PARTITION) {
            boolean ddPlain = false;
            boolean ddTag = false;
            if (dataSource != null) {
                ddPlain = CommonUtils.getBoolean(dataSource.getConnectionConfiguration().getProviderProperty(DuckDBConstants.PROP_DD_PLAIN_STRING), false);
                ddTag = CommonUtils.getBoolean(dataSource.getConnectionConfiguration().getProviderProperty(DuckDBConstants.PROP_DD_TAG_STRING), false);
            }

            return new TPRule[] {
                new SQLDollarQuoteRule(
                    position == RulePosition.PARTITION,
                    true,
                    ddTag,
                    ddPlain
                )
            };
        }
        return new TPRule[0];
    }

    @Override
    public boolean supportsAliasInSelect() {
        return true;
    }
}
