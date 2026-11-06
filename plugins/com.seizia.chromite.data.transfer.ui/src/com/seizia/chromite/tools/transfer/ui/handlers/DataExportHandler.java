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
package com.seizia.chromite.tools.transfer.ui.handlers;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.swt.SWT;
import org.eclipse.ui.handlers.HandlerUtil;
import com.seizia.chromite.model.struct.DBSDataContainer;
import com.seizia.chromite.registry.ApplicationPolicyProvider;
import com.seizia.chromite.tools.transfer.IDataTransferNode;
import com.seizia.chromite.tools.transfer.database.DatabaseTransferProducer;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.internal.UIMessages;
import com.seizia.chromite.utils.RuntimeUtils;

public class DataExportHandler extends DataTransferHandler {

    @Override
    protected IDataTransferNode<?> adaptTransferNode(Object object) {
        final DBSDataContainer adapted = RuntimeUtils.getObjectAdapter(object, DBSDataContainer.class);
        if (adapted != null) {
            return new DatabaseTransferProducer(adapted);
        } else {
            return null;
        }
    }

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        if (ApplicationPolicyProvider.getInstance().isPolicyEnabled(ApplicationPolicyProvider.POLICY_DATA_EXPORT)) {
            UIUtils.showMessageBox(
                HandlerUtil.getActiveShell(event),
                UIMessages.dialog_policy_data_export_title,
                UIMessages.dialog_policy_data_export_msg,
                SWT.ICON_WARNING);
            return null;
        }
        return super.execute(event);
    }

}