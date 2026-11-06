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

package com.seizia.chromite.ui.editors.sql;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.TextViewer;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.IWorkbenchPartSite;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.texteditor.ITextEditorActionConstants;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.ModelPreferences.SeparateConnectionBehavior;
import com.seizia.chromite.model.DBIcon;
import com.seizia.chromite.model.DBPContextProvider;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPImage;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.preferences.DBPPropertyDescriptor;
import com.seizia.chromite.model.runtime.DBRCreator;
import com.seizia.chromite.model.struct.DBSObject;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.ui.UIServiceSQL;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.dialogs.MessageBoxBuilder;
import com.seizia.chromite.ui.dialogs.Reply;
import com.seizia.chromite.ui.editors.StringEditorInput;
import com.seizia.chromite.ui.editors.SubEditorSite;
import com.seizia.chromite.ui.editors.TextEditorUtils;
import com.seizia.chromite.ui.editors.sql.dialogs.GenerateSQLParametrizedDialog;
import com.seizia.chromite.ui.editors.sql.dialogs.ViewSQLDialog;
import com.seizia.chromite.ui.editors.sql.handlers.SQLEditorHandlerOpenEditor;
import com.seizia.chromite.ui.editors.sql.handlers.SQLNavigatorContext;
import com.seizia.chromite.ui.internal.UIMessages;
import com.seizia.chromite.utils.GeneralUtils;
import org.jkiss.utils.CommonUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SQLEditorControl
 */
public class UIServiceSQLImpl implements UIServiceSQL {

    private static final Log log = Log.getLog(UIServiceSQLImpl.class);

    @Override
    public int openSQLViewer(DBCExecutionContext context, String title, DBPImage image, String text, boolean showSaveButton, boolean showOpenEditorButton) {
        ViewSQLDialog dialog = new ViewSQLDialog(
            UIUtils.getActiveWorkbenchWindow().getActivePage().getActivePart().getSite(),
            () -> context,
            title,
            image,
            text
        );
        dialog.setShowSaveButton(showSaveButton);
        dialog.setShowOpenEditorButton(showOpenEditorButton);
        return dialog.open();
    }

    @Override
    public String openSQLEditor(@Nullable DBPContextProvider contextProvider, String title, @Nullable DBPImage image, String text) {
        ViewSQLDialog dialog = new ViewSQLDialog(
            UIUtils.getActiveWorkbenchWindow().getActivePage().getActivePart().getSite(),
            contextProvider,
            title,
            image,
            text
        );
        dialog.setReadOnly(false);
        if (dialog.open() == IDialogConstants.OK_ID) {
            return dialog.getText();
        }
        return null;
    }

    @Override
    public int openGeneratedScriptViewer(
        @Nullable DBCExecutionContext context,
        String title,
        @Nullable DBPImage image,
        @NotNull DBRCreator<String, Map<String, Object>> scriptGenerator,
        @NotNull DBPPropertyDescriptor[] properties,
        boolean showSaveButton)
    {
        GenerateSQLParametrizedDialog dialog = new GenerateSQLParametrizedDialog(
            UIUtils.getActiveWorkbenchWindow().getActivePage().getActivePart().getSite(),
            context,
            title,
            image)
        {
            @Override
            protected void createControls(Composite parent) {
                for (DBPPropertyDescriptor prop : properties) {
                    if (prop.getDataType() == Boolean.class) {
                        UIUtils.createCheckbox(parent, prop.getDisplayName(), CommonUtils.notEmpty(prop.getDescription()), false, 1);
                    }
                }
                super.createControls(parent);
            }

            @Override
            protected String[] generateSQLScript() {
                Map<String, Object> params = new LinkedHashMap<>();
                return new String[] { scriptGenerator.createObject(params) };
            }
        };
/*
        ViewSQLDialog dialog = new ViewSQLDialog(
            UIUtils.getActiveWorkbenchWindow().getActivePage().getActivePart().getSite(),
            context,
            title,
            image,
            text
        );
        dialog.setShowSaveButton(showSaveButton);
        return dialog.open();
*/
        return 0;
    }

    @Override
    public Object openSQLConsole(@NotNull DBPDataSourceContainer dataSourceContainer, DBCExecutionContext executionContext, DBSObject selectedObject, String name, String sqlText) {
        SQLNavigatorContext navigatorContext;
        if (executionContext == null || SQLEditorUtils.isOpenSeparateConnection(dataSourceContainer)) {
            navigatorContext = new SQLNavigatorContext(dataSourceContainer);
        } else {
            navigatorContext = new SQLNavigatorContext(executionContext);
        }
        if (selectedObject != null) {
            navigatorContext.setSelectedObject(selectedObject);
        }
        return SQLEditorHandlerOpenEditor.openSQLConsole(
            UIUtils.getActiveWorkbenchWindow(),
            navigatorContext,
            name,
            sqlText);
    }

    @Override
    public Object createSQLPanel(Object site, Object parent, DBPContextProvider contextProvider, String panelName, boolean showVerticalBar, String sqlText) throws DBException {
        IWorkbenchPartSite partSite = (IWorkbenchPartSite) site;
        Composite editorPH = (Composite)parent;
        final SQLEditorBase editor = new SQLEditorBase() {
            @Nullable
            @Override
            public DBCExecutionContext getExecutionContext() {
                return contextProvider.getExecutionContext();
            }

            @Override
            public void createPartControl(@NotNull Composite parent) {
                super.createPartControl(parent);
                getAction(ITextEditorActionConstants.CONTEXT_PREFERENCES).setEnabled(false);
            }

            @Override
            public boolean isFoldingEnabled() {
                return false;
            }
        };
        editor.setHasVerticalRuler(showVerticalBar);
        try {
            editor.init(new SubEditorSite(partSite), new StringEditorInput(panelName, sqlText, true, GeneralUtils.getDefaultFileEncoding()));
        } catch (PartInitException e) {
            throw new DBException("Error initializing SQL panel", e);
        }
        editor.createPartControl(editorPH);
        editor.reloadSyntaxRules();

        TextViewer textViewer = editor.getTextViewer();
        textViewer.setData("editor", editor);
        TextEditorUtils.enableHostEditorKeyBindingsSupport(partSite, textViewer.getTextWidget());

        editorPH.addDisposeListener(e -> editor.dispose());

        return textViewer;
    }

    @Override
    public void setSQLPanelText(Object panelObject, String sqlText) {
        if (panelObject instanceof TextViewer) {
            Object editor = ((TextViewer) panelObject).getData("editor");
            if (editor instanceof SQLEditorBase) {
                IDocument document = ((SQLEditorBase) editor).getDocument();
                if (((SQLEditorBase) editor).getEditorInput() instanceof StringEditorInput && document != null) {
                    document.set(sqlText);
                } else {
                    ((SQLEditorBase) editor).setInput(
                        new StringEditorInput("SQL", sqlText, true, GeneralUtils.getDefaultFileEncoding()));
                    ((SQLEditorBase) editor).reloadSyntaxRules();
                }
            }
        }
    }

    @Override
    public String getSQLPanelText(Object panelObject) {
        if (panelObject instanceof TextViewer) {
            Object editor = ((TextViewer) panelObject).getData("editor");
            if (editor instanceof SQLEditorBase) {
                return ((SQLEditorBase) editor).getDocument().get();
            }
        }
        return null;
    }

    @Override
    public Object openNewScript(DBSObject forObject) {
        try {
            SQLEditorHandlerOpenEditor.openNewEditor(new SQLNavigatorContext(forObject), null);
            return true;
        } catch (CoreException e) {
            DBWorkbench.getPlatformUI().showError("Open new SQL editor", "Can't open new SQL editor", e);
            return false;
        }
    }

    @Override
    public Object openRecentScript(DBSObject forObject) {
        try {
            SQLEditorHandlerOpenEditor.openRecentScript(
                UIUtils.getActiveWorkbenchWindow(),
                new SQLNavigatorContext(forObject),
                null);
            return true;
        } catch (CoreException e) {
            DBWorkbench.getPlatformUI().showError("Open SQL editor", "Can't open SQL editor", e);
            return false;
        }
    }

    @Override
    public boolean useIsolatedConnections(DBPContextProvider contextProvider) {
        DBPDataSourceContainer container = contextProvider.getExecutionContext().getDataSource().getContainer();
        SeparateConnectionBehavior behavior = SeparateConnectionBehavior.parse(
            container.getPreferenceStore().getString(SQLPreferenceConstants.EDITOR_SEPARATE_CONNECTION)
        );
        switch (behavior) {
            case ALWAYS:
                return true;
            case NEVER:
                return false;
            case DEFAULT:
            default:
                return !container.isForceUseSingleConnection() && !container.getDriver().isEmbedded();
        }
    }

    @Override
    public boolean confirmQueryExecution(
        @NotNull String title,
        @NotNull String message,
        @NotNull String queryText,
        @NotNull DBPContextProvider contextProvider,
        boolean isWarning
    ) {
        final Reply[] reply = {null};

        UIUtils.syncExec(() -> reply[0] = MessageBoxBuilder.builder(UIUtils.getActiveWorkbenchShell())
            .setTitle(title)
            .setMessage(message)
            .setReplies(Reply.YES, Reply.NO)
            .setDefaultReply(Reply.NO)
            .setPrimaryImage(isWarning ? DBIcon.STATUS_WARNING : DBIcon.STATUS_QUESTION)
            .setCustomButton(buttonBar -> {
                buttonBar.setLayout(new GridLayout(3, false));
                Button showQueryButton = UIUtils.createPushButton(
                    buttonBar,
                    UIMessages.dialog_confirm_action_show_query,
                    null
                );
                showQueryButton.addSelectionListener(SelectionListener.widgetSelectedAdapter(e ->
                    openSQLViewer(
                        contextProvider.getExecutionContext(),
                        UIMessages.dialog_confirm_action_query,
                        null,
                        queryText,
                        false,
                        false
                    )
                ));
            })
            .showMessageBox()
        );

        return reply[0] == Reply.YES;
    }
}
