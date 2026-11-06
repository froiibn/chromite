/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
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
package com.seizia.chromite.ui.editors.sql.commands;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.ui.handlers.HandlerUtil;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.navigator.DBNDatabaseNode;
import com.seizia.chromite.model.navigator.DBNModel;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.ui.editors.sql.SQLEditor;
import com.seizia.chromite.ui.navigator.actions.NavigatorHandlerRefresh;
import com.seizia.chromite.utils.RuntimeUtils;

import java.util.List;

public abstract class AbstractSchemaHandler extends AbstractHandler {

    @Nullable
    protected SQLEditor getEditor(@NotNull ExecutionEvent event) {
        return RuntimeUtils.getObjectAdapter(HandlerUtil.getActiveEditor(event), SQLEditor.class);
    }

    @Nullable
    protected DBCExecutionContext getExecutionContext(@NotNull SQLEditor editor) {
        return editor.getExecutionContext();
    }

    protected DBNModel getNavigatorModel(DBPProject project) {
        return project.getNavigatorModel();
    }

    protected DBPProject getProject(SQLEditor editor) {
        return editor.getProject();
    }

    protected DBNDatabaseNode getDatabaseNode(SQLEditor editor, DBSObject object) {
        var project = getProject(editor);
        if (project != null) {
            var navigatorModel = getNavigatorModel(project);
            if (navigatorModel != null) {
                return navigatorModel.findNode(object);
            }
        }
        return null;
    }

    protected void refreshNode(DBNDatabaseNode node) {
        if (node != null) {
            NavigatorHandlerRefresh.refreshNavigator(List.of(node));
        }
    }
}
