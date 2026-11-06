/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
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
package com.seizia.chromite.ui.editors.sql.dialogs;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.IWorkbenchPartSite;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.internal.WorkbenchMessages;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPImage;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.impl.sql.BasicSQLDialect;
import com.seizia.chromite.model.messages.ModelMessages;
import com.seizia.chromite.model.sql.SQLDialect;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.dialogs.BaseDialog;
import com.seizia.chromite.ui.dialogs.DialogUtils;
import com.seizia.chromite.ui.editors.StringEditorInput;
import com.seizia.chromite.ui.editors.SubEditorSite;
import com.seizia.chromite.ui.editors.sql.SQLEditorBase;
import com.seizia.chromite.ui.editors.sql.internal.SQLEditorMessages;
import com.seizia.chromite.utils.GeneralUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;


public abstract class BaseSQLDialog extends BaseDialog {

    private static final Log log = Log.getLog(BaseSQLDialog.class);

    private static final int SAVE_TO_FILE_BUTTON_ID = IDialogConstants.CLIENT_ID + 1;

    private final IEditorSite subSite;
    private SQLEditorBase sqlViewer;
    private final StringEditorInput sqlInput;

    public BaseSQLDialog(final IWorkbenchPartSite parentSite, String title, @Nullable DBPImage image) {
        this(parentSite.getShell(), parentSite, title, image);
    }

    public BaseSQLDialog(final Shell shell, final IWorkbenchPartSite parentSite, String title, @Nullable DBPImage image)
    {
        super(shell, title, image);
        this.subSite = new SubEditorSite(parentSite);
        this.sqlInput = new StringEditorInput(title, "", true, GeneralUtils.getDefaultFileEncoding());
    }

    public boolean isReadOnly() {
        return sqlInput.isReadOnly();
    }

    public void setReadOnly(boolean readOnly) {
        sqlInput.setReadOnly(readOnly);
    }

    public String getText() {
        return sqlInput.getBuffer().toString();
    }

    protected boolean isWordWrap() {
        return false;
    }

    protected boolean isLabelVisible() {
        return true;
    }

    protected Composite createSQLPanel(Composite parent)
    {
        Composite panel = UIUtils.createPlaceholder(parent, 1);
        panel.setLayoutData(new GridData(GridData.FILL_BOTH));

        if (isLabelVisible()) {
            UIUtils.createControlLabel(panel, SQLEditorMessages.pref_page_sql_format_label_SQLPreview);
        }
//        new Label(panel, SWT.SEPARATOR | SWT.HORIZONTAL).setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        Composite editorPH = new Composite(panel, SWT.NONE);
        GridData gd = new GridData(GridData.FILL_BOTH);
        gd.verticalIndent = 3;
        gd.horizontalSpan = 1;
        gd.minimumHeight = 100;
        gd.minimumWidth = 100;
        editorPH.setLayoutData(gd);
        editorPH.setLayout(new FillLayout());

        sqlViewer = new SQLEditorBase() {
            @NotNull
            @Override
            public SQLDialect getSQLDialect() {
                return BaseSQLDialog.this.getSQLDialect();
            }

            @Nullable
            @Override
            public DBCExecutionContext getExecutionContext() {
                return BaseSQLDialog.this.getExecutionContext();
            }

            @Override
            protected boolean isAnnotationRulerVisible() {
                return false;
            }
        };
        updateSQL();
        sqlViewer.createPartControl(editorPH);
        if (isWordWrap()) {
            Object text = sqlViewer.getAdapter(Control.class);
            if (text instanceof StyledText) {
                ((StyledText) text).setWordWrap(true);
            }
        }
        sqlViewer.reloadSyntaxRules();

        UIUtils.asyncExec(editorPH::layout);

        return panel;
    }

    protected SQLDialect getSQLDialect() {
        DBCExecutionContext executionContext = getExecutionContext();
        if (executionContext != null) {
            return executionContext.getDataSource().getSQLDialect();
        }
        return BasicSQLDialect.INSTANCE;
    }

    protected abstract DBCExecutionContext getExecutionContext();

    protected abstract String getSQLText();

    protected void createCopyButton(Composite parent) {
        createButton(parent, IDialogConstants.DETAILS_ID, WorkbenchMessages.Workbench_copy, false);
    }
    
    protected void createRefreshButton(Composite parent) {
        createButton(parent, IDialogConstants.RETRY_ID, SQLEditorMessages.dialog_view_sql_button_refresh, false);
    }

    protected void createSaveToFileButton(@NotNull Composite parent) {
        createButton(parent, SAVE_TO_FILE_BUTTON_ID, ModelMessages.model_jdbc_save_to_file_, false);
    }

    protected void saveToClipboard() {
        CharSequence text = getSQLText();
        UIUtils.setClipboardContents(getShell().getDisplay(), TextTransfer.getInstance(), text);
    }

    protected boolean saveToFile() {
        Path file = DialogUtils.selectFileForSave(
            this.getShell(),
            this.getTitle() + " : " + ModelMessages.model_jdbc_save_to_file_,
            new String[]{"*.sql", "*.txt", "*", "*.*"},
            null
        );
        if (file != null) {
            CharSequence sqlText = getSQLText();
            try {
                Files.writeString(file, sqlText);
                return true;
            } catch (IOException e) {
                DBWorkbench.getPlatformUI().showError(
                    SQLEditorMessages.dialog_view_sql_save_to_file_failure_title,
                    NLS.bind(SQLEditorMessages.dialog_view_sql_save_to_file_failure_message, file),
                    e
                );
            }
        }
        return false;
    }

    @Override
    protected void buttonPressed(int buttonId) {
        if (buttonId == SAVE_TO_FILE_BUTTON_ID) {
            if (saveToFile()) {
                close();
            }
        } else if (buttonId == IDialogConstants.DETAILS_ID) {
            saveToClipboard();
            super.buttonPressed(IDialogConstants.CANCEL_ID);
        } else {
            super.buttonPressed(buttonId);
        }
    }

    @Override
    protected void okPressed() {
        if (sqlViewer != null && sqlViewer.getTextViewer() != null && sqlViewer.getTextViewer().getDocument() != null) {
            sqlInput.setText(sqlViewer.getTextViewer().getDocument().get());
        }
        super.okPressed();
    }

    @Override
    public boolean close() {
        if (sqlViewer != null) {
            try {
                sqlViewer.dispose();
            } catch (Exception e) {
                log.debug("Error disposing embedded SQL editor", e);
            }
        }
        return super.close();
    }

    protected void updateSQL() {
        try {
            this.sqlInput.setText(getSQLText());
            if (sqlViewer.getSite() != null) {
                sqlViewer.setInput(sqlInput);
            } else {
                sqlViewer.init(subSite, sqlInput);
            }
            sqlViewer.reloadSyntaxRules();
        } catch (PartInitException e) {
            DBWorkbench.getPlatformUI().showError(getShell().getText(), null, e);
        }
    }
}
