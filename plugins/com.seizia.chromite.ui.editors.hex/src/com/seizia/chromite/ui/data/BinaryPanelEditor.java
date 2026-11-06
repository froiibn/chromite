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
package com.seizia.chromite.ui.data;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IContributionManager;
import org.eclipse.swt.SWT;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import com.seizia.chromite.Log;
import com.seizia.chromite.model.DBPDataSourceContainer;
import com.seizia.chromite.model.DBPEventListener;
import com.seizia.chromite.model.DBValueFormatting;
import com.seizia.chromite.model.app.DBPDataSourceRegistry;
import com.seizia.chromite.model.data.DBDContent;
import com.seizia.chromite.model.data.DBDContentStorage;
import com.seizia.chromite.model.data.storage.BytesContentStorage;
import com.seizia.chromite.model.exec.DBCExecutionContext;
import com.seizia.chromite.model.preferences.DBPPreferenceListener;
import com.seizia.chromite.model.preferences.DBPPreferenceStore;
import com.seizia.chromite.model.runtime.DBRProgressMonitor;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.ui.SwtIcons;
import com.seizia.chromite.ui.UIIcon;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.editors.binary.BinaryContent;
import com.seizia.chromite.ui.editors.binary.HexEditControl;
import com.seizia.chromite.ui.editors.binary.pref.HexPreferencesPage;
import com.seizia.chromite.utils.ContentUtils;
import com.seizia.chromite.utils.GeneralUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
* ControlPanelEditor
*/
public class BinaryPanelEditor implements IStreamValueEditor<HexEditControl> {

    private static final Log log = Log.getLog(BinaryPanelEditor.class);

    private IValueController valueController;

    @Override
    public HexEditControl createControl(IValueController valueController) {
        this.valueController = valueController;
        HexEditControl hControl = new HexEditControl(valueController.getEditPlaceholder(), SWT.READ_ONLY);
        DBPPreferenceListener preferencesChangeListener = event -> {

            if (HexPreferencesPage.PROP_DEF_WIDTH.equals(event.getProperty())) {
                String defValue = (String) event.getNewValue();
                hControl.setDefWidth(Integer.parseInt(defValue));
            }
        };
        DBPPreferenceStore store = DBWorkbench.getPlatform().getPreferenceStore();
        store.addPropertyChangeListener(preferencesChangeListener);
        hControl.addDisposeListener(e -> store.removePropertyChangeListener(preferencesChangeListener));

        final DBCExecutionContext executionContext = valueController.getExecutionContext();
        if (executionContext != null) {
            final DBPDataSourceContainer container = executionContext.getDataSource().getContainer();
            final DBPEventListener listener = e -> hControl.setReadOnly(container.isConnectionReadOnly() || valueController.isReadOnly());
            final DBPDataSourceRegistry registry = container.getRegistry();

            registry.addDataSourceListener(listener);
            hControl.addDisposeListener(e -> registry.removeDataSourceListener(listener));
        }

        return hControl;
    }

    @Override
    public void primeEditorValue(@NotNull DBRProgressMonitor monitor, @NotNull HexEditControl control, @NotNull DBDContent value) throws DBException
    {
        monitor.beginTask("Prime content value", 1);
        try {
            DBDContentStorage data = value.getContents(monitor);
            String charset;
            monitor.subTask("Read binary value");
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            if (data != null) {
                try (InputStream contentStream = data.getContentStream()){
                    ContentUtils.copyStreams(contentStream, -1, buffer, monitor);
                }
                charset = data.getCharset();
            } else {
                charset = DBValueFormatting.getDefaultBinaryFileEncoding(value.getDataSource());
            }
            String finalCharset = charset;
            byte[] byteData = buffer.toByteArray();
            BinaryContent content = control.getContent();
            if (content != null && content.length() == byteData.length) {
                ByteBuffer byteBuffer = ByteBuffer.allocate(byteData.length);
                if (Arrays.equals(byteBuffer.array(), byteData)) {
                    // Equals data
                    return;
                }
            }
            UIUtils.syncExec(() -> {
                control.setContent(byteData, finalCharset, false);
                control.setReadOnly(value.getDataSource().getContainer().isConnectionReadOnly() || valueController.isReadOnly());
            });
        } catch (IOException e) {
            throw new DBException("Error reading stream value", e);
        } finally {
            monitor.done();
        }
    }

    @Override
    public void extractEditorValue(@NotNull DBRProgressMonitor monitor, @NotNull HexEditControl control, @NotNull DBDContent value) throws DBException
    {
        BinaryContent binaryContent = control.getContent();
        if (binaryContent != null) {
            ByteBuffer buffer = ByteBuffer.allocate((int) binaryContent.length());
            try {
                binaryContent.get(buffer, 0);
            } catch (IOException e) {
                log.error(e);
            }
            value.updateContents(
                monitor,
                new BytesContentStorage(buffer.array(), GeneralUtils.getDefaultFileEncoding()));
        }
    }

    @Override
    public void contributeActions(@NotNull IContributionManager manager, @NotNull final HexEditControl control) {
        manager.add(new Action("Switch Insert/Overwrite mode", SwtIcons.getImageDescriptor(UIIcon.CURSOR)) {
            @Override
            public void run() {
                control.redrawCaret(true);
            }
        });
    }

    @Override
    public void contributeSettings(@NotNull IContributionManager manager, @NotNull HexEditControl control) {

    }
}