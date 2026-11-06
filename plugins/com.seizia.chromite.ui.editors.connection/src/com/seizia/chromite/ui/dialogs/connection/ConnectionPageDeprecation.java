/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
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
package com.seizia.chromite.ui.dialogs.connection;

import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.forms.events.IHyperlinkListener;
import org.eclipse.ui.forms.widgets.FormText;
import org.eclipse.ui.forms.widgets.FormToolkit;
import org.jkiss.code.NotNull;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.connection.DBPDriverStub;
import com.seizia.chromite.ui.ShellUtils;

public class ConnectionPageDeprecation extends ConnectionWizardPage {
    private final DBPDriverStub driverStub;

    public ConnectionPageDeprecation(@NotNull DBPDriverStub driverStub) {
        super(ConnectionPageDeprecation.class.getName());
        this.driverStub = driverStub;

        setTitle(driverStub.nonAvailabilityTitle());
        setDescription(driverStub.nonAvailabilityDescription());
        setPageComplete(false);
    }

    @Override
    public void createControl(Composite parent) {
        final FormToolkit toolkit = new FormToolkit(parent.getDisplay());

        final Composite composite = toolkit.createComposite(parent, SWT.NONE);
        composite.setLayoutData(new GridData(GridData.FILL_BOTH));
        composite.setLayout(new GridLayout(1, false));

        final FormText text = toolkit.createFormText(composite, false);
        text.setFont("header", JFaceResources.getFont("org.eclipse.jface.headerfont"));
        text.setText(driverStub.nonAvailabilityReason(), true, false);
        text.setHyperlinkSettings(toolkit.getHyperlinkGroup());
        text.addHyperlinkListener(IHyperlinkListener.linkActivatedAdapter(e -> ShellUtils.launchProgram(e.getHref().toString())));
        text.setLayoutData(GridDataFactory.fillDefaults().grab(true, true).hint(200, 200).create());

        setControl(composite);
    }

    @Override
    public void saveSettings(@NotNull DBPDataSourceContainer dataSourceDescriptor) {
        // do nothing
    }
}
