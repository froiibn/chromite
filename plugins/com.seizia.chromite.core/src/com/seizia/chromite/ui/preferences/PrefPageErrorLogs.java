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
package com.seizia.chromite.ui.preferences;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.jkiss.code.NotNull;
import com.seizia.chromite.ChromitePreferences;
import com.seizia.chromite.LogOutputStream;
import com.seizia.chromite.core.CoreMessages;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.contentassist.ContentAssistUtils;
import com.seizia.chromite.ui.contentassist.SmartTextContentAdapter;
import com.seizia.chromite.ui.contentassist.StringContentProposalProvider;
import com.seizia.chromite.ui.controls.TextWithOpenFile;
import com.seizia.chromite.utils.GeneralUtils;
import com.seizia.chromite.utils.PrefUtils;
import com.seizia.chromite.utils.SystemVariablesResolver;

/**
 * PrefPageErrorHandle
 */
public class PrefPageErrorLogs extends AbstractPrefPage implements IWorkbenchPreferencePage
{
    public static final String PAGE_ID = "com.seizia.chromite.preferences.main.errorLogs"; //$NON-NLS-1$

    private Button logsDebugEnabled;
    private TextWithOpenFile logsDebugLocation;
    private Spinner logFilesMaxSizeSpinner;
    private Spinner logFilesMaxCountSpinner;
    
    @Override
    public void init(IWorkbench workbench)
    {
    }

    @NotNull
    @Override
    protected Control createPreferenceContent(@NotNull Composite parent) {
        Composite composite = UIUtils.createPlaceholder(parent, 1, 5);
        DBPPreferenceStore store = DBWorkbench.getPlatform().getPreferenceStore();

        {
            // Logs
            Composite groupLogs = UIUtils.createTitledComposite(
                composite,
                CoreMessages.pref_page_ui_general_group_debug_logs,
                2,
                GridData.FILL_HORIZONTAL | GridData.VERTICAL_ALIGN_BEGINNING
            );

            logsDebugEnabled = UIUtils.createCheckbox(
                groupLogs,
                CoreMessages.pref_page_ui_general_label_enable_debug_logs,
                CoreMessages.pref_page_ui_general_label_enable_debug_logs_tip,
                store.getBoolean(ChromitePreferences.LOGS_DEBUG_ENABLED),
                2);
            UIUtils.createControlLabel(groupLogs, CoreMessages.pref_page_ui_general_label_log_file_location);
            logsDebugLocation = new TextWithOpenFile(groupLogs, CoreMessages.pref_page_ui_general_label_open_file_text, new String[] { "*.log", "*.txt" } );
            ContentAssistUtils.installContentProposal(
                logsDebugLocation.getTextControl(),
                new SmartTextContentAdapter(),
                new StringContentProposalProvider(
                    GeneralUtils.variablePattern(SystemVariablesResolver.VAR_WORKSPACE),
                    GeneralUtils.variablePattern(SystemVariablesResolver.VAR_HOME)));
            logsDebugLocation.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
            logsDebugLocation.setText(store.getString(ChromitePreferences.LOGS_DEBUG_LOCATION));

            final DBPPreferenceStore preferenceStore = DBWorkbench.getPlatform().getPreferenceStore();
            UIUtils.createControlLabel(groupLogs, CoreMessages.pref_page_logs_files_max_size_label);
            logFilesMaxSizeSpinner = new Spinner(groupLogs, SWT.BORDER);
            logFilesMaxSizeSpinner.setDigits(0);
            logFilesMaxSizeSpinner.setIncrement(10);
            logFilesMaxSizeSpinner.setMinimum(0);
            logFilesMaxSizeSpinner.setMaximum(Integer.MAX_VALUE);
            long bigScriptSize = preferenceStore.getLong(LogOutputStream.LOGS_MAX_FILE_SIZE);
            logFilesMaxSizeSpinner.setSelection((int) (bigScriptSize / 1024));

            UIUtils.createControlLabel(groupLogs, CoreMessages.pref_page_logs_files_max_count_label);
            logFilesMaxCountSpinner = new Spinner(groupLogs, SWT.BORDER);
            logFilesMaxCountSpinner.setDigits(0);
            logFilesMaxCountSpinner.setIncrement(1);
            logFilesMaxCountSpinner.setMinimum(0);
            logFilesMaxCountSpinner.setMaximum(Integer.MAX_VALUE);
            int debugLogFilesMaxCount = preferenceStore.getInt(LogOutputStream.LOGS_MAX_FILES_COUNT);
            logFilesMaxCountSpinner.setSelection(debugLogFilesMaxCount);

            Label tipLabel = UIUtils.createLabel(groupLogs, CoreMessages.pref_page_ui_general_label_options_take_effect_after_restart);
            tipLabel.setLayoutData(new GridData(GridData.HORIZONTAL_ALIGN_BEGINNING, GridData.VERTICAL_ALIGN_BEGINNING, false, false , 2, 1));
        }

        return composite;
    }

    @Override
    protected void performDefaults() {
        DBPPreferenceStore store = DBWorkbench.getPlatform().getPreferenceStore();
        logsDebugEnabled.setSelection(store.getDefaultBoolean(ChromitePreferences.LOGS_DEBUG_ENABLED));
        logsDebugLocation.setText(store.getDefaultString(ChromitePreferences.LOGS_DEBUG_LOCATION));
        logFilesMaxSizeSpinner.setSelection((int) store.getDefaultLong(LogOutputStream.LOGS_MAX_FILE_SIZE) / 1024);
        logFilesMaxCountSpinner.setSelection(store.getDefaultInt(LogOutputStream.LOGS_MAX_FILES_COUNT));

        super.performDefaults();
    }

    @Override
    public boolean performOk() {
        DBPPreferenceStore store = DBWorkbench.getPlatform().getPreferenceStore();

        store.setValue(ChromitePreferences.LOGS_DEBUG_ENABLED, logsDebugEnabled.getSelection());
        store.setValue(ChromitePreferences.LOGS_DEBUG_LOCATION, logsDebugLocation.getText());

        store.setValue(LogOutputStream.LOGS_MAX_FILE_SIZE, logFilesMaxSizeSpinner.getSelection() * 1024L);
        store.setValue(LogOutputStream.LOGS_MAX_FILES_COUNT, logFilesMaxCountSpinner.getSelection());

        PrefUtils.savePreferenceStore(store);

        return super.performOk();
    }

}