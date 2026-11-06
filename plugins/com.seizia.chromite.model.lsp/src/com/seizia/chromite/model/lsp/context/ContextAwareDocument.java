/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
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
package com.seizia.chromite.model.lsp.context;

import org.eclipse.lsp4j.TextDocumentItem;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.sql.BasicSQLDialect;
import com.seizia.chromite.model.sql.SQLSyntaxManager;
import com.seizia.chromite.model.sql.parser.SQLRuleManager;
import com.seizia.chromite.runtime.DBWorkbench;

public class ContextAwareDocument extends TextDocumentItem {
    @Nullable
    private DBCExecutionContext executionContext;
    @NotNull
    private SQLSyntaxManager syntaxManager;
    @NotNull
    private LspSQLRuleManager ruleManager;

    public ContextAwareDocument(
        @NotNull final String uri,
        final String languageId,
        final int version,
        @NotNull final String text
    ) {
        super(uri, languageId, version, text);
        initBasicSyntax();
    }

    @NotNull
    public SQLRuleManager getRuleManager() {
        return ruleManager;
    }

    public void setRuleManager(@NotNull LspSQLRuleManager ruleManager) {
        this.ruleManager = ruleManager;
    }

    @NotNull
    public SQLSyntaxManager getSyntaxManager() {
        return syntaxManager;
    }

    public void setSyntaxManager(@NotNull SQLSyntaxManager syntaxManager) {
        this.syntaxManager = syntaxManager;
    }

    @Nullable
    public DBCExecutionContext getExecutionContext() {
        return executionContext;
    }

    public void setExecutionContext(@Nullable DBCExecutionContext executionContext) {
        this.executionContext = executionContext;
    }

    @Nullable
    public DBPDataSource getDataSource() {
        if (executionContext == null) {
            return null;
        } else {
            return executionContext.getDataSource();
        }
    }

    @Override
    public String toString() {
        return "ContextAwareDocument{" +
            "executionContext=" + executionContext +
            ", syntaxManager=" + syntaxManager +
            ", ruleManager=" + ruleManager +
            ", textDocument=" + super.toString() +
            '}';
    }

    private void initBasicSyntax() {
        syntaxManager = new SQLSyntaxManager();
        syntaxManager.init(BasicSQLDialect.INSTANCE, DBWorkbench.getPlatform().getPreferenceStore());

        ruleManager = new LspSQLRuleManager(syntaxManager);
        ruleManager.loadRules(getDataSource(), false);
    }

    @NotNull
    public static ContextAwareDocument from(@NotNull TextDocumentItem document) {
        return new ContextAwareDocument(
            document.getUri(),
            document.getLanguageId(),
            document.getVersion(),
            document.getText()
        );
    }
}
