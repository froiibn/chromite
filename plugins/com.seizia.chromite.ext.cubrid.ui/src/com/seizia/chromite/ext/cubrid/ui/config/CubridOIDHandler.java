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
package com.seizia.chromite.ext.cubrid.ui.config;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;
import com.seizia.chromite.ext.cubrid.model.CubridDataSource;
import com.seizia.chromite.ext.cubrid.ui.views.CubridOIDSearchDialog;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.jdbc.JDBCSession;
import com.seizia.chromite.model.navigator.DBNDataSource;
import com.seizia.chromite.model.navigator.DBNNode;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import com.seizia.chromite.registry.DataSourceDescriptor;
import com.seizia.chromite.ui.navigator.NavigatorUtils;

public class CubridOIDHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        final Shell activeShell = HandlerUtil.getActiveShell(event);
        final ISelection selection = HandlerUtil.getCurrentSelection(event);
        final DBNNode node = NavigatorUtils.getSelectedNode(selection);
        if (node instanceof DBNDataSource dataSourceNode) {
            DataSourceDescriptor descriptor = (DataSourceDescriptor) dataSourceNode.getDataSourceContainer();
            DBPDataSource dataSource = descriptor.getDataSource();
            CubridDataSource cubrid = (CubridDataSource) dataSource;
            try {
                JDBCSession session = DBUtils.openMetaSession(new VoidProgressMonitor(), cubrid, "GetSession");
                CubridOIDSearchDialog dialog = new CubridOIDSearchDialog(activeShell, session);
                dialog.open();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return null;
    }
}
