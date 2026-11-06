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
package com.seizia.chromite.ui.controls.resultset.view;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.internal.WorkbenchMessages;
import org.eclipse.ui.internal.WorkbenchPlugin;
import org.eclipse.ui.statushandlers.StatusManager;
import org.eclipse.ui.views.IViewDescriptor;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBIcon;
import com.seizia.chromite.ui.SwtIcons;
import com.seizia.chromite.ui.IActionConstants;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.controls.resultset.IResultSetContainer;
import com.seizia.chromite.ui.controls.resultset.IResultSetErrorAction;
import com.seizia.chromite.ui.controls.resultset.ResultSetErrorActionDescriptor;
import com.seizia.chromite.ui.controls.resultset.ResultSetErrorActionRegistry;
import com.seizia.chromite.ui.dialogs.EditTextDialog;
import com.seizia.chromite.utils.GeneralUtils;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * @since 3.1
 */
class ErrorDetailsPart {
    private static final Log log = Log.getLog(ErrorDetailsPart.class);

    private final Composite parent;
    private final IStatus reason;

    ErrorDetailsPart(final Composite parent, IStatus reason, @NotNull IResultSetContainer resultSetContainer) {
        this.parent = parent;
        this.reason = reason;

        parent.setLayout(GridLayoutFactory.fillDefaults().extendedMargins(5, 0, 5, 5).numColumns(2).create());

        Label imageLabel = new Label(parent, SWT.NONE);
        imageLabel.setImage(getImage());
        imageLabel.setLayoutData(new GridData(GridData.HORIZONTAL_ALIGN_CENTER | GridData.VERTICAL_ALIGN_BEGINNING));

        StyledText text = new StyledText(parent, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);

        text.setLayoutData(new GridData(GridData.FILL_BOTH));
        text.setText(GeneralUtils.normalizeLineSeparators(reason.getMessage()));
        text.setFont(UIUtils.getMonospaceFont());

        text.addListener(SWT.Resize, e -> {
            final Point size = text.getSize();
            if (size.y > 100) {
                // Can't use the setSize here - will revalidate every time the parent is resized
                ((GridData) text.getLayoutData()).heightHint = 100;
                parent.layout(true);
            }
        });

        Composite buttonParent = new Composite(parent, SWT.NONE);
        buttonParent.setLayout(GridLayoutFactory.fillDefaults().numColumns(3).create());
        buttonParent.setLayoutData(GridDataFactory.fillDefaults().grab(true, false).span(2, 1).create());
        buttonParent.setBackground(parent.getBackground());

        // Spacer
        new Label(buttonParent, SWT.NONE).setLayoutData(new GridData(SWT.FILL, SWT.BEGINNING, true, false));

        for (ResultSetErrorActionDescriptor descriptor : ResultSetErrorActionRegistry.getInstance().getActions()) {
            IResultSetErrorAction action;
            try {
                action = descriptor.createInstance();
            } catch (DBException e) {
                log.error("Can't create error action '" + descriptor.getLabel() + "'", e);
                continue;
            }
            if (!action.isVisible(resultSetContainer, this.reason)) {
                continue;
            }
            UIUtils.createDialogButton(
                buttonParent,
                descriptor.getLabel(),
                descriptor.getIcon(),
                descriptor.getDescription(),
                SelectionListener.widgetSelectedAdapter(e -> action.perform(resultSetContainer, this.reason))
            );
            ((GridLayout) buttonParent.getLayout()).numColumns++;
        }

        createShowLogButton(buttonParent);
        createDetailsButton(buttonParent);
    }

    /**
     * Return the image for the upper-left corner of this part
     *
     * @return the image
     */
    private Image getImage() {
        return switch (reason.getSeverity()) {
            case IStatus.ERROR -> SwtIcons.getImage(DBIcon.STATUS_ERROR);
            case IStatus.WARNING -> SwtIcons.getImage(DBIcon.STATUS_WARNING);
            default -> SwtIcons.getImage(DBIcon.STATUS_INFO);
        };
    }

    private void showDetails() {
        EditTextDialog dialog = new EditTextDialog(
            parent.getShell(),
            "Error details",
            getDetails(reason),
            true);
        dialog.setMonospaceFont(true);
        dialog.setAutoSize(true);
        dialog.open();
    }

    private String getDetails(IStatus status) {
        if (status.getException() != null) {
            return GeneralUtils.normalizeLineSeparators(getStackTrace(status.getException()));
        }

        return ""; //$NON-NLS-1$
    }

    private String getStackTrace(Throwable throwable) {
        StringWriter swriter = new StringWriter();
        try (PrintWriter pwriter = new PrintWriter(swriter)) {
            throwable.printStackTrace(pwriter);
            pwriter.flush();
        }
        return swriter.toString();
    }

    private void createShowLogButton(Composite parent) {
        IViewDescriptor descriptor = PlatformUI.getWorkbench().getViewRegistry().find(IActionConstants.LOG_VIEW_ID);
        if (descriptor == null) {
            return;
        }
        Button button = UIUtils.createDialogButton(
            parent,
            "Show log",
            null,
            WorkbenchMessages.ErrorLogUtil_ShowErrorLogTooltip,
            SelectionListener.widgetSelectedAdapter(e -> {
                try {
                    PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().showView(IActionConstants.LOG_VIEW_ID);
                } catch (CoreException ce) {
                    StatusManager.getManager().handle(ce, WorkbenchPlugin.PI_WORKBENCH);
                }
            })
        );
        final Image image = descriptor.getImageDescriptor().createImage();
        button.setImage(image);
        button.addDisposeListener(e -> image.dispose());
    }

    private void createDetailsButton(@NotNull Composite parent) {
        if (reason.getException() == null) {
            return;
        }
        UIUtils.createDialogButton(
            parent,
            IDialogConstants.SHOW_DETAILS_LABEL,
            SelectionListener.widgetSelectedAdapter(e -> showDetails())
        );
    }
}
