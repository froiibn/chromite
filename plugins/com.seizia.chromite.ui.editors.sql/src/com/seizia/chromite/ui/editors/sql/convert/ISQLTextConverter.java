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

package com.seizia.chromite.ui.editors.sql.convert;

import org.eclipse.jface.text.IDocument;
import org.jkiss.code.NotNull;
import com.seizia.chromite.model.sql.SQLDialect;
import com.seizia.chromite.model.sql.SQLSyntaxManager;
import com.seizia.chromite.ui.editors.sql.syntax.SQLRuleScanner;

import java.util.Map;

/**
 * SQL code converter
 */
public interface ISQLTextConverter {

    @NotNull
    String convertText(
            @NotNull SQLDialect dialect,
            @NotNull SQLSyntaxManager syntaxManager,
            @NotNull SQLRuleScanner ruleManager,
            @NotNull IDocument document,
            int startPos,
            int length,
            @NotNull Map<String, Object> options);

}
