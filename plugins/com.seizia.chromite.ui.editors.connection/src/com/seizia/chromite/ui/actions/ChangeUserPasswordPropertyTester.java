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
package com.seizia.chromite.ui.actions;

import org.eclipse.core.expressions.PropertyTester;
import org.eclipse.core.runtime.IAdaptable;
import com.seizia.chromite.model.DBPDataSource;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.access.DBAUserPasswordManager;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.navigator.DBNDataSource;
import com.seizia.chromite.model.navigator.DBNLocalFolder;
import com.seizia.chromite.model.rm.RMConstants;
import org.jkiss.utils.CommonUtils;

public class ChangeUserPasswordPropertyTester extends PropertyTester {

    @Override
    public boolean test(Object element, String property, Object[] objects, Object o1) {
        if (!(element instanceof DBNDataSource)) {
            return false;
        }

        if (property.equals("canChangePassword")) {
            DBNDataSource dsNode = (DBNDataSource) element;
            DBPProject project = dsNode.getOwnerProject();
            if (project == null || !project.hasRealmPermission(RMConstants.PERMISSION_PROJECT_DATASOURCES_EDIT)) {
                return false;
            }

            DBPDataSourceContainer dataSourceContainer = dsNode.getDataSourceContainer();
            DBPDataSource dataSource = dataSourceContainer.getDataSource();
            String userName = dataSourceContainer.getConnectionConfiguration().getUserName();
            if (dataSource instanceof IAdaptable) {
                return ((IAdaptable) dataSource).getAdapter(DBAUserPasswordManager.class) != null && CommonUtils.isNotEmpty(userName);
            }
        }
        return false;
    }
}
