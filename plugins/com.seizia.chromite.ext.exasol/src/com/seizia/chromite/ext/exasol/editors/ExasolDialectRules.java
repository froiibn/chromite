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
package com.seizia.chromite.ext.exasol.editors;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ext.exasol.sql.ExasolSquareBracketsRule;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.sql.parser.rules.SQLFullLineRule;
import com.seizia.chromite.model.sql.parser.tokens.SQLControlToken;
import com.seizia.chromite.model.text.parser.TPRule;
import com.seizia.chromite.model.text.parser.TPRuleProvider;

/**
* Exasol dialect rules
*/
class ExasolDialectRules implements TPRuleProvider {

    @NotNull
    @Override
    public TPRule[] extendRules(@Nullable DBPDataSourceContainer dataSource, @NotNull RulePosition position) {
        if (position == TPRuleProvider.RulePosition.CONTROL) {
            final SQLControlToken defineToken = new SQLControlToken("exasol.define");

            return new TPRule[] {
                new SQLFullLineRule("define", defineToken), //$NON-NLS-1$
                new SQLFullLineRule("DEFINE", defineToken) //$NON-NLS-1$
            };
        }
        if (position == RulePosition.INITIAL || position == RulePosition.PARTITION) {
            return new TPRule[] {new ExasolSquareBracketsRule(dataSource,  position == RulePosition.PARTITION) };
        }
        return new TPRule[0];
    }

}
