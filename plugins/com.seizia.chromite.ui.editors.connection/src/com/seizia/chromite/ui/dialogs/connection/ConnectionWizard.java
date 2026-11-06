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
package com.seizia.chromite.ui.dialogs.connection;

import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IDialogPage;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.jface.wizard.IWizardPage;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.ui.INewWizard;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.ModelPreferences;
import com.seizia.chromite.ModelPreferences.SeparateConnectionBehavior;
import com.seizia.chromite.model.DBUtils;
import com.seizia.chromite.model.app.DBPDataSourceRegistry;
import com.seizia.chromite.model.app.DBPProject;
import com.seizia.chromite.model.connection.*;
import com.seizia.chromite.model.exec.DBCSession;
import com.seizia.chromite.model.navigator.DBNBrowseSettings;
import com.seizia.chromite.model.net.DBWHandlerConfiguration;
import com.seizia.chromite.model.net.DBWUtils;
import com.seizia.chromite.model.rm.RMConstants;
import com.seizia.chromite.registry.DataSourceDescriptor;
import com.seizia.chromite.registry.driver.DriverDescriptor;
import com.seizia.chromite.runtime.DBWorkbench;
import com.seizia.chromite.runtime.jobs.ConnectionTestJob;
import com.seizia.chromite.ui.ConnectionFeatures;
import com.seizia.chromite.ui.ConnectionPreferences;
import com.seizia.chromite.ui.IDataSourceConnectionTester;
import com.seizia.chromite.ui.IDialogPageProvider;
import com.seizia.chromite.ui.internal.UIConnectionMessages;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.dialogs.ActiveWizard;
import com.seizia.chromite.ui.dialogs.ConfirmationDialog;
import com.seizia.chromite.ui.dialogs.IConnectionWizard;
import com.seizia.chromite.utils.GeneralUtils;
import org.jkiss.utils.ArrayUtils;

import java.lang.reflect.InvocationTargetException;
import java.util.*;

/**
 * Abstract connection wizard
 */

public abstract class ConnectionWizard extends ActiveWizard implements IConnectionWizard, INewWizard {

    static final String PROP_CONNECTION_TYPE = "connection-type";

    // protected final IProject project;
    private final Map<DriverDescriptor, DataSourceDescriptor> infoMap = new HashMap<>();
    private final List<IPropertyChangeListener> propertyListeners = new ArrayList<>();
    private DBPDriverSubstitutionDescriptor driverSubstitution;

    protected ConnectionWizard() {
        setNeedsProgressMonitor(true);
    }

    @Override
    public String getWindowTitle() {
        if (isNew()) {
            return UIConnectionMessages.dialog_new_connection_wizard_title;
        } else {
            DataSourceDescriptor activeDataSource = getActiveDataSource();
            return NLS.bind( UIConnectionMessages.dialog_connection_edit_title, activeDataSource.getName());
        }
    }

    @Override
    public Image getDefaultPageImage() {
        return super.getDefaultPageImage();
//        DBPDriver selectedDriver = getSelectedDriver();
//        return SwtIcons.getImage(selectedDriver == null ? DBIcon.DATABASE_DEFAULT : selectedDriver.getIcon());
    }

    @Override
    public void dispose() {
        // Dispose all temp data sources
        for (DataSourceDescriptor dataSource : infoMap.values()) {
            dataSource.dispose();
        }
        super.dispose();
    }

    @Nullable
    abstract public DBPDataSourceRegistry getDataSourceRegistry();

    abstract DBPDriver getSelectedDriver();

    abstract DBPProject getSelectedProject();

    @NotNull
    abstract DBNBrowseSettings getSelectedNavigatorSettings();

    public abstract ConnectionPageSettings getPageSettings();

    protected abstract void saveSettings(DataSourceDescriptor dataSource);

    @NotNull
    public DataSourceDescriptor getActiveDataSource() {
        DriverDescriptor driver = (DriverDescriptor) getSelectedDriver();
        DataSourceDescriptor info = infoMap.get(driver);
        DBPDataSourceRegistry registry = getDataSourceRegistry();
        if (registry == null) {
            throw new IllegalStateException("No active project");
        }
        if (info == null && driver != null) {
            DBPConnectionConfiguration connectionInfo = getDefaultConnectionConfiguration();
            info = registry.createDataSource(
                DataSourceDescriptor.generateNewId(driver),
                driver,
                connectionInfo
            );
            DBPNativeClientLocation defaultClientLocation = driver.getDefaultClientLocation();
            if (defaultClientLocation != null) {
                info.getConnectionConfiguration().setClientHomeId(defaultClientLocation.getName());
            }
            info.setSavePassword(true);
            infoMap.put(driver, info);
        }
        return info;
    }

    @Nullable
    public abstract DataSourceDescriptor getOriginalDataSource();

    @Nullable
    @Override
    public DBPDriverSubstitutionDescriptor getDriverSubstitution() {
        return driverSubstitution;
    }

    @NotNull
    protected abstract PersistResult persistDataSource();

    public void setDriverSubstitution(@Nullable DBPDriverSubstitutionDescriptor driverSubstitution) {
        this.driverSubstitution = driverSubstitution;
        getActiveDataSource().setDriverSubstitution(driverSubstitution);
    }

    public void testConnection() {
        DataSourceDescriptor activeDataSource = getActiveDataSource();
        DataSourceDescriptor targetDataSource;

        if (canUseTemporaryDataSource(activeDataSource)) {
            targetDataSource = activeDataSource.getRegistry().createDataSource(activeDataSource);
            // Generate new ID to avoid session conflicts in QM
            targetDataSource.setId(DataSourceDescriptor.generateNewId(activeDataSource.getDriver()));
            targetDataSource.setTemporary(true);
            targetDataSource.getPreferenceStore().setValue(
                ModelPreferences.META_SEPARATE_CONNECTION,
                SeparateConnectionBehavior.NEVER.name()
            );
        } else {
            int decision = ConfirmationDialog.confirmAction(
                getShell(),
                ConfirmationDialog.WARNING,
                ConnectionPreferences.CONFIRM_TEST_CONNECTION_PERSIST,
                ConfirmationDialog.CONFIRM
            );
            if (decision != IDialogConstants.OK_ID) {
                return;
            }
            targetDataSource = activeDataSource;
        }

        saveSettings(targetDataSource);
        if (targetDataSource.isTemporary()) {
            targetDataSource.getConnectionConfiguration().setRuntimeAttribute(
                DBPConnectionConfiguration.ATTRIBUTE_TEST_CONNECTION_SOURCE, activeDataSource);
        }

        if (activeDataSource == targetDataSource) {
            persistDataSource();
        }

        if (targetDataSource.isSharedCredentials()) {
            if (!targetDataSource.getProject().getWorkspace().hasRealmPermission(RMConstants.PERMISSION_PROJECT_ADMIN)) {
                UIUtils.showMessageBox(getShell(), "Credentials edit restricted",
                    "Shared credentials edit is available for administrators only.",
                    SWT.ICON_ERROR);
            } else {
                UIUtils.showMessageBox(getShell(), "Use credentials manager",
                    "Direct connection test is not available for shared connections.\nGo to shared credentials manager dialog.",
                    SWT.ICON_WARNING);
            }
            return;
        }

        firePropertyChangeEvent(PROP_CONNECTION_TEST, null, targetDataSource);

        ConnectionFeatures.CONNECTION_TEST.use(Map.of("driver", targetDataSource.getDriver().getPreconfiguredId()));

        try {
            final ConnectionTestJob op = new ConnectionTestJob(targetDataSource, session -> {
                for (IWizardPage page : getPages()) {
                    testInPage(session, page);
                }
            });

            try {
                getRunnableContext().run(true, true, monitor -> {
                    // Wait for job to finish
                    op.setOwnerMonitor(monitor);
                    op.schedule();
                    while (op.getState() == Job.WAITING || op.getState() == Job.RUNNING) {
                        if (monitor.isCanceled()) {
                            op.cancel();
                            throw new InterruptedException();
                        }
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                    if (op.getConnectError() != null) {
                        throw new InvocationTargetException(op.getConnectError());
                    }
                    if (op.getConnectStatus() == Status.CANCEL_STATUS) {
                        throw new InterruptedException("cancel");
                    }
                });

                var oldUserPassword = activeDataSource.getActualConnectionConfiguration().getUserPassword();
                var newUserPassword = targetDataSource.getActualConnectionConfiguration().getUserPassword();
                var oldSavePassword = activeDataSource.isSavePassword();
                var newSavePassword = targetDataSource.isSavePassword();
                if (!Objects.equals(newUserPassword, oldUserPassword) || oldSavePassword != newSavePassword) {
                    activeDataSource.setSavePassword(newSavePassword);
                    DBUtils.fireObjectUpdate(activeDataSource, targetDataSource.getActualConnectionConfiguration());
                }

                new ConnectionTestDialog(
                    getShell(),
                    targetDataSource,
                    op.getServerVersion(),
                    op.getClientVersion(),
                    op.getConnectTime()).open();

            } catch (InterruptedException ex) {
                if (!"cancel".equals(ex.getMessage())) {
                    DBWorkbench.getPlatformUI().showError(UIConnectionMessages.dialog_connection_wizard_start_dialog_interrupted_title,
                        UIConnectionMessages.dialog_connection_wizard_start_dialog_interrupted_message);
                }
            } catch (InvocationTargetException ex) {
                Throwable targetException = ex.getTargetException();
                DBWorkbench.getPlatformUI().showError(
                    UIConnectionMessages.dialog_connection_wizard_start_dialog_error_title,
                    GeneralUtils.getExceptionMessage(targetException),
                    GeneralUtils.makeExceptionStatus(targetException)
                );
            } catch (Throwable ex) {
                DBWorkbench.getPlatformUI().showError(
                    UIConnectionMessages.dialog_connection_wizard_start_dialog_error_title,
                    null,
                    GeneralUtils.makeExceptionStatus(ex));
            }
        } finally {
            if (activeDataSource != targetDataSource) {
                targetDataSource.dispose();
            }
        }
    }

    @Override
    public boolean isNew() {
        return false;
    }

    private void testInPage(DBCSession session, IDialogPage page) {
        if (page instanceof IDataSourceConnectionTester) {
            if (page.getControl() != null && !page.getControl().isDisposed()) {
                ((IDataSourceConnectionTester) page).testConnection(session);
            }
        }
        if (page instanceof IDialogPageProvider && isPageActive(page)) {
            for (IDialogPage subPage : ArrayUtils.safeArray(((IDialogPageProvider) page).getDialogPages(false, false))) {
                testInPage(session, subPage);
            }
        }
    }

    @Override
    public void addPropertyChangeListener(@NotNull IPropertyChangeListener listener) {
        propertyListeners.add(listener);
    }

    @Override
    public void firePropertyChangeEvent(@NotNull String property, @Nullable Object oldValue, @Nullable Object newValue) {
        if (Objects.equals(oldValue, newValue)) {
            return;
        }

        for (IPropertyChangeListener listener : propertyListeners) {
            listener.propertyChange(new PropertyChangeEvent(this, property, oldValue, newValue));
        }
    }

    public boolean openSettingsPage(String pageId) {
        final IWizardPage page = getPage(pageId);
        if (page != null) {
            getContainer().showPage(page);
            return true;
        }
        return false;
    }

    @NotNull
    protected DBPConnectionConfiguration getDefaultConnectionConfiguration() {
        DBPConnectionType type = DBPConnectionType.getDefaultConnectionType();

        DBPConnectionConfiguration config = new DBPConnectionConfiguration();
        config.setConnectionType(type);
        config.setCloseIdleConnection(type.isAutoCloseConnections());

        return config;
    }

    private static boolean canUseTemporaryDataSource(@NotNull DataSourceDescriptor descriptor) {
        List<DBWHandlerConfiguration> handlers = DBWorkbench.isDistributed()
            ? DBWUtils.getActualNetworkHandlers(descriptor)
            : descriptor.getConnectionConfiguration().getHandlers();
        for (DBWHandlerConfiguration handler : handlers) {
            if (handler.isEnabled() && handler.getHandlerDescriptor().isDistributed()) {
                return false;
            }
        }
        return true;
    }

    protected enum PersistResult {
        UNCHANGED,
        CHANGED,
        ERROR
    }
}
