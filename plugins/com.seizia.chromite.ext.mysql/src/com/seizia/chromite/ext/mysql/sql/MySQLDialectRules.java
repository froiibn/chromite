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
package com.seizia.chromite.ext.mysql.sql;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.sql.parser.rules.SQLFullLineRule;
import com.seizia.chromite.model.sql.parser.tokens.SQLControlToken;
import com.seizia.chromite.model.text.parser.TPRule;
import com.seizia.chromite.model.text.parser.TPRuleProvider;

/**
* MySQL dialect rules
*/
class MySQLDialectRules implements TPRuleProvider {

    @NotNull
    @Override
    public TPRule[] extendRules(@Nullable DBPDataSourceContainer dataSource, @NotNull RulePosition position) {
        if (position == RulePosition.CONTROL) {
            final SQLControlToken sourceToken = new SQLControlToken("mysql.source");

            SQLFullLineRule sourceRule2 = new SQLFullLineRule("SOURCE", sourceToken); //$NON-NLS-1$
            return new TPRule[] { sourceRule2 };
        }
        return new TPRule[0];
    }

}
