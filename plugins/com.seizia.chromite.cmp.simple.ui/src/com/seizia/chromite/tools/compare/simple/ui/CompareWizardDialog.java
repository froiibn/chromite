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

package com.seizia.chromite.tools.compare.simple.ui;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.wizard.IWizard;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.IWorkbenchWindow;
import com.seizia.chromite.tools.compare.simple.ui.internal.CompareUIMessages;
import com.seizia.chromite.ui.dialogs.ActiveWizardDialog;

/**
 * Compare wizard dialog
 */
public class CompareWizardDialog extends ActiveWizardDialog
{
    public CompareWizardDialog(IWorkbenchWindow window, IWizard wizard)
    {
        super(window, wizard);
        //setTitleImage(DBIcon.COMPARE.getImage());
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent)
    {
        super.createButtonsForButtonBar(parent);
        Button finishButton = getButton(IDialogConstants.FINISH_ID);
        finishButton.setText(CompareUIMessages.compare_objects_wizard_dialog_button_compare);
    }
}
