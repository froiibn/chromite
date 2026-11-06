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
package com.seizia.chromite.ui.editors.data.preferences;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.*;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ModelPreferences;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.virtual.DBVEntity;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.UIIcon;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.controls.resultset.internal.ResultSetMessages;
import com.seizia.chromite.ui.dialogs.EnterNameDialog;
import com.seizia.chromite.ui.preferences.PreferenceStoreDelegate;
import com.seizia.chromite.ui.preferences.TargetPrefPage;
import org.jkiss.utils.CommonUtils;

import java.util.Locale;
import java.util.StringJoiner;

public class PrefPageDataViewer extends TargetPrefPage {
    public static final String PAGE_ID = "com.seizia.chromite.preferences.main.dataviewer";

    private List refPanelDescColumnKeywords;
    private Text maxAmountText;

    public PrefPageDataViewer() {
        setPreferenceStore(new PreferenceStoreDelegate(DBWorkbench.getPlatform().getPreferenceStore()));
    }

    @Override
    protected boolean hasDataSourceSpecificOptions(DBPDataSourceContainer container) {
        final DBPPreferenceStore store = container.getPreferenceStore();
        return store.contains(ModelPreferences.RESULT_REFERENCE_DESCRIPTION_COLUMN_PATTERNS)
            || store.contains(ModelPreferences.DICTIONARY_MAX_ROWS);
    }

    @Override
    protected boolean supportsDataSourceSpecificOptions() {
        return true;
    }

    @NotNull
    @Override
    protected Control createPreferenceContent(@NotNull Composite parent) {
        final Composite composite = UIUtils.createComposite(parent, 1);
        {
            Composite group = UIUtils.createTitledComposite(
                composite,
                ResultSetMessages.pref_page_data_viewer_reference_panel_group,
                2,
                GridData.FILL_HORIZONTAL
            );

            UIUtils.createControlLabel(group, ResultSetMessages.pref_page_data_viewer_reference_panel_desc_column_keywords_label, 2);

            refPanelDescColumnKeywords = new List(group, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL | SWT.H_SCROLL);
            refPanelDescColumnKeywords.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
            ((GridData) refPanelDescColumnKeywords.getLayoutData()).heightHint = UIUtils.getFontHeight(refPanelDescColumnKeywords) * 15;

            final ToolBar toolbar = new ToolBar(group, SWT.VERTICAL);
            toolbar.setLayoutData(new GridData(GridData.VERTICAL_ALIGN_BEGINNING));

            UIUtils.createToolItem(toolbar, ResultSetMessages.pref_page_data_viewer_reference_panel_desc_column_keywords_add_button, UIIcon.ADD, SelectionListener.widgetSelectedAdapter(e -> {
                    final String name = promptKeywordName(null);
                    if (name != null) {
                        refPanelDescColumnKeywords.add(name);
                        refPanelDescColumnKeywords.select(refPanelDescColumnKeywords.getItemCount() - 1);
                        refPanelDescColumnKeywords.notifyListeners(SWT.Selection, new Event());
                    }
                }));
            final ToolItem removeButton = UIUtils.createToolItem(toolbar, ResultSetMessages.pref_page_data_viewer_reference_panel_desc_column_keywords_remove_button, UIIcon.DELETE, SelectionListener.widgetSelectedAdapter(e -> {
                    final int index = refPanelDescColumnKeywords.getSelectionIndex();
                    refPanelDescColumnKeywords.remove(index);
                    refPanelDescColumnKeywords.select(CommonUtils.clamp(index, 0, refPanelDescColumnKeywords.getItemCount() - 1));
                    refPanelDescColumnKeywords.notifyListeners(SWT.Selection, new Event());
                }));
            final ToolItem editButton = UIUtils.createToolItem(toolbar, ResultSetMessages.pref_page_data_viewer_reference_panel_desc_column_keywords_edit_button, UIIcon.EDIT, SelectionListener.widgetSelectedAdapter(e -> {
                    final int index = refPanelDescColumnKeywords.getSelectionIndex();
                    final String name = promptKeywordName(refPanelDescColumnKeywords.getItem(index));
                    if (name != null) {
                        refPanelDescColumnKeywords.setItem(index, name);
                    }
                }));

            refPanelDescColumnKeywords.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> {
                    final boolean selected = refPanelDescColumnKeywords.getSelectionIndex() >= 0;
                    removeButton.setEnabled(selected);
                    editButton.setEnabled(selected);
                }));
        }
        {
            Composite group = UIUtils.createTitledComposite(composite,
                ResultSetMessages.pref_page_data_viewer_dictionary_panel_group, 1, GridData.FILL_HORIZONTAL);
            maxAmountText = UIUtils.createLabelText(
                group,
                ResultSetMessages.getPref_page_data_viewer_dictionary_panel_results_max_size,
                "200"
            );
            maxAmountText.addVerifyListener(UIUtils.getNumberVerifyListener(Locale.getDefault()));
            maxAmountText.addModifyListener((event) -> {
                updateApplyButton();
                getContainer().updateButtons();
            });
            UIUtils.createInfoLabel(group, ResultSetMessages.getPref_page_data_viewer_dictionary_panel_results_max_size_tip);
        }
        return composite;
    }

    @Override
    public boolean isValid() {
        return super.isValid() && !maxAmountText.getText().isEmpty();
    }

    @Override
    protected void loadPreferences(@NotNull DBPPreferenceStore store) {
        refPanelDescColumnKeywords.removeAll();
        for (String pattern : DBVEntity.getDescriptionColumnPatterns(store)) {
            refPanelDescColumnKeywords.add(pattern);
        }
        refPanelDescColumnKeywords.notifyListeners(SWT.Selection, new Event());
        maxAmountText.setText(store.getString(ModelPreferences.DICTIONARY_MAX_ROWS));
    }

    @Override
    protected void savePreferences(@NotNull DBPPreferenceStore store) {
        final StringJoiner buffer = new StringJoiner("|");
        for (String pattern : refPanelDescColumnKeywords.getItems()) {
            buffer.add(pattern);
        }
        store.setValue(ModelPreferences.RESULT_REFERENCE_DESCRIPTION_COLUMN_PATTERNS, buffer.toString());
        store.setValue(ModelPreferences.DICTIONARY_MAX_ROWS, maxAmountText.getText());
    }

    @Override
    protected void clearPreferences(DBPPreferenceStore store) {
        store.setToDefault(ModelPreferences.RESULT_REFERENCE_DESCRIPTION_COLUMN_PATTERNS);
        store.setToDefault(ModelPreferences.DICTIONARY_MAX_ROWS);
    }

    @Override
    protected void performDefaults() {
        maxAmountText.setText(getTargetPreferenceStore().getDefaultString(ModelPreferences.DICTIONARY_MAX_ROWS));
        refPanelDescColumnKeywords.removeAll();
        for (String pattern : DBVEntity.DEFAULT_DESCRIPTION_COLUMN_PATTERNS) {
            refPanelDescColumnKeywords.add(pattern);
        }
    }

    @NotNull
    @Override
    protected String getPropertyPageID() {
        return PAGE_ID;
    }

    @Nullable
    private String promptKeywordName(@Nullable String value) {
        String name = EnterNameDialog.chooseName(getShell(), ResultSetMessages.pref_page_data_viewer_reference_panel_desc_column_keywords_prompt_title, value);
        if (name != null) {
            name = name.toLowerCase(Locale.ENGLISH).strip();
        }
        if (CommonUtils.isNotEmpty(name) && refPanelDescColumnKeywords.indexOf(name) < 0) {
            return name;
        }
        return null;
    }
}
