/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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
package com.seizia.chromite.model.sql.format;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.sql.SQLSyntaxManager;
import com.seizia.chromite.model.sql.registry.SQLFormatterConfigurationRegistry;

/**
 * SQL Formatter
 */
public class SQLFormatUtils {

    public static String formatSQL(DBPDataSource dataSource, String query) {
        return formatSQL(dataSource, query, null);
    }

    public static String formatSQL(@Nullable DBPDataSource dataSource, @NotNull SQLSyntaxManager syntaxManager, String query) {
        return formatSQL(dataSource, syntaxManager, query, null);
    }

    public static String formatSQL(DBPDataSource dataSource, String query, @Nullable String indent) {
        SQLSyntaxManager syntaxManager = new SQLSyntaxManager();
        syntaxManager.init(dataSource.getSQLDialect(), dataSource.getContainer().getPreferenceStore());
        return formatSQL(dataSource, syntaxManager, query, indent);
    }

    public static String formatSQL(
        @Nullable DBPDataSource dataSource,
        @NotNull SQLSyntaxManager syntaxManager,
        @NotNull String query,
        @Nullable String indent
    ) {
        SQLFormatterConfiguration configuration = new SQLFormatterConfiguration(dataSource, syntaxManager);
        if (indent != null) {
            configuration.setIndentString(indent);
        }
        SQLFormatter formatter = SQLFormatterConfigurationRegistry.getInstance().createFormatter(configuration);
        if (formatter == null) {
            return query;
        }
        return formatter.format(query, configuration);
    }

}
