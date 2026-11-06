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
package com.seizia.chromite.ui.app.standalone.update;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.osgi.util.NLS;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.IElementUpdater;
import org.eclipse.ui.menus.UIElement;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import com.seizia.chromite.core.CoreMessages;
import com.seizia.chromite.registry.updater.VersionDescriptor;
import com.seizia.chromite.registry.updater.VersionDescriptor.PatchSeverity;
import com.seizia.chromite.ui.ActionUtils;
import com.seizia.chromite.ui.SwtIcons;
import com.seizia.chromite.ui.UIIcon;
import com.seizia.chromite.ui.UIUtils;
import com.seizia.chromite.ui.app.standalone.ChromiteApplication;
import org.osgi.framework.Version;

import java.util.Map;

public class VersionUpdateHandler extends AbstractHandler implements IElementUpdater {
    private static final int ANIMATION_DELAY = 100;

    public static final String COMMAND_UPDATE = "com.seizia.chromite.ui.versionUpdate";
    public static final String COMMAND_RELEASE_NOTES = "com.seizia.chromite.ui.versionUpdate.releaseNotes";
    public static final String PROPERTY_AVAILABLE = "com.seizia.chromite.ui.versionUpdate.available";

    private static Version currentVersion;
    private static int currentPatch;
    private static VersionDescriptor newVersion;
    private static boolean updateStarted;
    private static boolean downloading;
    private static int animationFrame;

    static void showNotification(@NotNull Version current, @NotNull VersionDescriptor available) {
        currentVersion = current;
        currentPatch = ChromiteVersionChecker.getProductPatchNumber();
        newVersion = available;
        ActionUtils.evaluatePropertyState(PROPERTY_AVAILABLE);
        ActionUtils.fireCommandRefresh(COMMAND_UPDATE);
    }

    static boolean isUpdateAvailable() {
        return currentVersion != null && newVersion != null;
    }

    static void startUpdate(@NotNull Version current, @NotNull VersionDescriptor available) {
        UIUtils.runInUIThread(() -> {
            if (!updateStarted) {
                currentVersion = current;
                currentPatch = ChromiteVersionChecker.getProductPatchNumber();
                newVersion = available;
                ActionUtils.runCommand(COMMAND_UPDATE, PlatformUI.getWorkbench());
            }
        });
    }

    @Override
    public boolean isEnabled() {
        return !updateStarted && super.isEnabled();
    }

    @Override
    @Nullable
    public Object execute(@NotNull ExecutionEvent event) throws ExecutionException {
        Version current = currentVersion;
        VersionDescriptor available = newVersion;
        if (current == null || available == null) {
            return null;
        }
        if (COMMAND_RELEASE_NOTES.equals(event.getCommand().getId())) {
            ChromiteApplication.getInstance().notifyVersionUpgrade(current, available, true);
            if (ChromiteVersionChecker.isSuppressed(available)) {
                newVersion = null;
                currentVersion = null;
                ActionUtils.evaluatePropertyState(PROPERTY_AVAILABLE);
            }
        } else if (!updateStarted) {
            updateStarted = true;
            downloading = true;
            setBaseEnabled(false);
            refreshUpdateButton();
            animateDownload();
            if (!VersionUpdateDialog.performUpdate(available, this::downloadFinished)) {
                downloading = false;
                updateStarted = false;
                setBaseEnabled(true);
                refreshUpdateButton();
            }
        }
        return null;
    }

    @Override
    public void updateElement(@NotNull UIElement element, @NotNull Map parameters) {
        var severity = currentVersion == null || newVersion == null
            ? PatchSeverity.NONE
            : newVersion.getMaxSeverity(currentVersion, currentPatch);
        var icon = downloading
            ? UIIcon.LOADING.get(animationFrame % UIIcon.LOADING.size())
            : VersionUpdatePresentation.getSeverityIcon(severity);
        element.setIcon(SwtIcons.getImageDescriptor(icon));
        if (severity != PatchSeverity.NONE) {
            // Command contributions do not expose native toolbar button colors.
            element.setTooltip(NLS.bind(CoreMessages.dialog_version_update_severity, VersionUpdatePresentation.getSeverityLabel(severity)));
        } else {
            element.setTooltip(CoreMessages.dialog_version_update_title);
        }
    }

    private void downloadFinished(@NotNull IStatus status) {
        UIUtils.asyncExec(() -> {
            downloading = false;
            if (!status.isOK()) {
                updateStarted = false;
                setBaseEnabled(true);
            }
            refreshUpdateButton();
        });
    }

    private static void animateDownload() {
        UIUtils.timerExec(ANIMATION_DELAY, () -> {
            if (downloading) {
                animationFrame++;
                refreshUpdateButton();
                animateDownload();
            }
        });
    }

    private static void refreshUpdateButton() {
        ActionUtils.fireCommandRefresh(COMMAND_UPDATE);
    }
}
