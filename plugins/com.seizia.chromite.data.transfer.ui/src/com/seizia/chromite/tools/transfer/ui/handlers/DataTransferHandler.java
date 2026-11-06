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
package com.seizia.chromite.tools.transfer.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.tools.transfer.IDataTransferConsumer;
import com.seizia.chromite.tools.transfer.IDataTransferNode;
import com.seizia.chromite.tools.transfer.IDataTransferProducer;
import com.seizia.chromite.tools.transfer.ui.internal.DTUIMessages;
import com.seizia.chromite.tools.transfer.ui.wizard.DataTransferWizard;

import java.util.ArrayList;
import java.util.List;

public abstract class DataTransferHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        final IWorkbenchWindow workbenchWindow = HandlerUtil.getActiveWorkbenchWindow(event);
        final ISelection selection = HandlerUtil.getCurrentSelection(event);
        if (!(selection instanceof IStructuredSelection)) {
            return null;
        }
        IStructuredSelection ss = (IStructuredSelection)selection;
        final List<IDataTransferProducer<?>> producers = new ArrayList<>();
        final List<IDataTransferConsumer<?,?>> consumers = new ArrayList<>();
        for (Object object : ss) {
            IDataTransferNode<?> node = adaptTransferNode(object);
            if (node instanceof IDataTransferProducer) {
                producers.add((IDataTransferProducer<?>) node);
            } else if (node instanceof IDataTransferConsumer) {
                consumers.add((IDataTransferConsumer<?, ?>) node);
            }
        }

        // Run transfer wizard
        if (!producers.isEmpty() || !consumers.isEmpty()) {
            try {
                DataTransferWizard.openWizard(
                    workbenchWindow,
                    producers,
                    consumers,
                    true);
            } catch (Exception e) {
                DBWorkbench.getPlatformUI().showError(DTUIMessages.data_transfer_handler_title_data_transfer_error, DTUIMessages.data_transfer_handler_message_data_transfer_error, e);
            }
        } else {
            DBWorkbench.getPlatformUI().showError(DTUIMessages.data_transfer_handler_title_data_transfer_error, "Can't perform data transfer: selected objects are not recognized as data producers or data consumers");
        }

        return null;
    }

    protected abstract IDataTransferNode<?> adaptTransferNode(Object object);

}