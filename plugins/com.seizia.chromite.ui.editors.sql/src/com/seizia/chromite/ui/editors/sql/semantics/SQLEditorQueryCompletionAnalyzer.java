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
package com.seizia.chromite.ui.editors.sql.semantics;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.DBPImage;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.model.sql.completion.SQLCompletionRequest;
import com.seizia.chromite.model.sql.semantics.completion.*;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.ui.UIIcon;

import java.util.function.Function;
import java.util.function.Supplier;


public class SQLEditorQueryCompletionAnalyzer extends SQLQueryCompletionAnalyzer {

    private SQLEditorQueryCompletionProposalContext proposalContext;

    public SQLEditorQueryCompletionAnalyzer(
        @NotNull Function<DBRProgressMonitor, SQLQueryCompletionContext> completionContextSupplier,
        @NotNull SQLCompletionRequest request,
        @NotNull Supplier<Integer> currentCompletionOffsetSupplier
    ) {
        super(completionContextSupplier, request, currentCompletionOffsetSupplier);
    }

    @NotNull
    @Override
    protected SQLQueryCompletionProposalContext createProposalContext(@NotNull SQLQueryCompletionContext completionContext) {
        return this.proposalContext = new SQLEditorQueryCompletionProposalContext(this.request, completionContext.getRequestOffset());
    }

    @Override
    @NotNull
    protected SQLQueryCompletionProposal createProposal(
        @NotNull SQLQueryCompletionItemKind itemKind,
        @Nullable DBSObject object,
        @Nullable DBPImage image,
        @Nullable String displayString,
        @Nullable String decorationString,
        @NotNull String description,
        @NotNull String replacementString,
        int replacementOffset,
        int replacementLength,
        @Nullable SQLQueryWordEntry filterString,
        int proposalScore
    ) {
        return new SQLEditorQueryCompletionProposal(
            this.proposalContext,
            itemKind,
            object,
            image,
            displayString,
            decorationString,
            description,
            replacementString,
            replacementOffset,
            replacementLength,
            filterString,
            proposalScore
        );
    }

    @NotNull
    @Override
    protected DBPImage prepareProposalImage(@NotNull SQLQueryCompletionItem item) {
        return switch (item.getKind()) {
            case SCRIPT_VARIABLE, LOCAL_VARIABLE -> UIIcon.SQL_VARIABLE2;
            default -> super.prepareProposalImage(item);
        };
    }
}
