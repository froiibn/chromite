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
package com.seizia.chromite.ui.app.config.pages;

import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.SWT;
import org.jkiss.code.NotNull;
import com.seizia.chromite.ui.SwtIcons;
import com.seizia.chromite.ui.UIIcon;
import com.seizia.chromite.ui.app.config.ProductConfigWizard;

public abstract class ProductConfigWizardPage extends WizardPage {
    protected static final int TEXT_WIDTH_HINT = 300;
    protected static final int TEXT_HEIGHT_HINT = SWT.DEFAULT;

    public ProductConfigWizardPage(@NotNull String title, @NotNull String description) {
        super(title);
        setTitle(title);
        setDescription(description);
        setImageDescriptor(SwtIcons.getImageDescriptor(UIIcon.CHROMITE_LOGO));
    }

    @NotNull
    @Override
    public ProductConfigWizard getWizard() {
        return (ProductConfigWizard) super.getWizard();
    }

    /**
     * Determines whether this page should be shown in the current context.
     *
     * @return {@code true} if the page is applicable and should be shown, {@code false} otherwise
     */
    public boolean isPageApplicable() {
        return true;
    }

    public boolean isApplicableToFeatureUpdate() {
        return false;
    }

    public void loadSettings() {
        // do nothing by default
    }

    public void applySettings() {
        // do nothing by default
    }
}
