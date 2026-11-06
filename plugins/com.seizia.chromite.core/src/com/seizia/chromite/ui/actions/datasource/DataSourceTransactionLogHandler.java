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
package com.seizia.chromite.ui.actions.datasource;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;
import com.seizia.chromite.model.DBPContextProvider;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.ui.actions.AbstractDataSourceHandler;
import com.seizia.chromite.ui.controls.txn.TransactionLogDialog;
import org.jkiss.utils.CommonUtils;

public class DataSourceTransactionLogHandler extends AbstractDataSourceHandler {

    // Command parameter
    public static final String PARAM_SHOW_ALL = "showAll";

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        final Shell activeShell = HandlerUtil.getActiveShell(event);
        IEditorPart editor = HandlerUtil.getActiveEditor(event);
        DBCExecutionContext context = null;
        if (editor instanceof DBPContextProvider contextProvider) {
            context = contextProvider.getExecutionContext();
        }
        boolean showAll = CommonUtils.toBoolean(event.getParameters().get(PARAM_SHOW_ALL));
        TransactionLogDialog.showDialog(activeShell, context, false, showAll);
        return null;
    }

}