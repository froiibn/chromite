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
package com.seizia.chromite.ui.data.managers;

import org.eclipse.jface.action.IContributionManager;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.exec.DBCException;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.exec.DBCExecutionPurpose;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.messages.ModelMessages;
import com.seizia.chromite.model.preferences.DBPPropertyManager;
import com.seizia.chromite.model.runtime.VoidProgressMonitor;
import com.seizia.chromite.ui.data.IValueController;
import com.seizia.chromite.ui.data.IValueEditor;
import com.seizia.chromite.ui.data.IValueManager;

/**
 * Base value manager
 */
public abstract class BaseValueManager implements IValueManager {

    private static final Log log = Log.getLog(BaseValueManager.class);

    @Nullable
    public static Object makeNullValue(@NotNull final IValueController valueController)
    {
        try {
            DBCExecutionContext executionContext = valueController.getExecutionContext();
            if (executionContext == null) {
                throw new DBCException(ModelMessages.error_not_connected_to_database);
            }
            // We are going to create NULL value - it shouldn't result in any DB roundtrips so let's use dummy monitor
            try (DBCSession session = executionContext.openSession(new VoidProgressMonitor(), DBCExecutionPurpose.UTIL, "Set NULL value")) {
                return DBUtils.makeNullValue(
                    session,
                    valueController.getValueHandler(),
                    valueController.getValueType());
            }
        } catch (DBCException e) {
            log.error("Can't make NULL value", e);
            return null;
        }
    }

    @Override
    public void contributeActions(@NotNull IContributionManager manager, @NotNull IValueController controller, @Nullable IValueEditor activeEditor) throws DBCException {
        if (activeEditor != null) {
            activeEditor.contributeActions(manager, controller);
        }
    }

    @Override
    public void contributeProperties(@NotNull DBPPropertyManager propertySource, @NotNull IValueController controller) {
        // nothing
    }

/*
    @Override
    public IValueController.EditType[] getSupportedEditTypes() {
        // Base value manager do not support any edit type.
        return new IValueController.EditType[] {};
    }
*/


}